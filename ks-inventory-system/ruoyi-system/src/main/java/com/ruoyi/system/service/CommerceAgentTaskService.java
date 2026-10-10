package com.ruoyi.system.service;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Durable procurement tasks. Models propose bounded lines; Java owns facts, approvals and execution. */
@Service
@Profile({"local","commerce"})
public class CommerceAgentTaskService {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final CommercePlanningService planning;
    private final CommerceMerchantService merchants;
    private final Clock clock;
    @Autowired public CommerceAgentTaskService(DataSource source,CommercePlanningService planning,CommerceMerchantService merchants) {
        this(source,planning,merchants,Clock.systemDefaultZone());
    }
    CommerceAgentTaskService(DataSource source,CommercePlanningService planning,CommerceMerchantService merchants,Clock clock) {
        this.source=source;this.jdbc=new JdbcTemplate(source);this.planning=planning;this.merchants=merchants;this.clock=clock;
    }
    public void initializeSchema() {
        ResourceDatabasePopulator script=new ResourceDatabasePopulator(new ClassPathResource("db/commerce-agent-tasks.sql"));
        script.setSqlScriptEncoding("UTF-8");script.execute(source);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> create(Map<String,Object> body,long actor) {
        authority(actor,CommerceCapability.SUPPLY_DRAFT);String key=key(body),goal=text(body.get("goal"),500,"经营目标");
        SortedMap<Long,Long> requested=requested(body);String mode=String.valueOf(body.getOrDefault("plannerMode","rules"));
        require(Arrays.asList("rules","model").contains(mode),"规划模式错误",400);
        String run=body.get("plannerRunId")==null?null:text(body.get("plannerRunId"),64,"规划记录");
        if(run!=null)require(run.matches("[A-Za-z0-9_-]+"),"规划记录格式错误",400);
        String fingerprint=hash(Arrays.asList(goal,requested));lockShop();
        List<Map<String,Object>> old=jdbc.queryForList("SELECT * FROM commerce_agent_task WHERE tenant_id=? AND shop_id=? AND request_key=?",tenant(),shop(),key);
        if(!old.isEmpty()){require(actor==n(old.get(0).get("created_by"))&&fingerprint.equals(old.get(0).get("request_hash")),"同一任务请求不能改变目标、数量或创建人",409);return view(old.get(0),false);}
        Map<String,Object> facts=facts(requested,null,actor);List<Map<String,Object>> plan=plan(requested,facts);
        // Initial model proposals must be valid in their entirety, rather than silently purchasing a different quantity.
        require(plan.size()==requested.size(),"建议商品已无缺口或未上架，请重新读取经营数据",409);
        for(Map<String,Object> line:plan)require(n(line.get("quantity"))==requested.get(n(line.get("productId"))),"模型建议超出实时采购缺口，请重新规划",409);
        String task=id("BT"),factHash=hash(facts);Date day=Date.valueOf(today());Timestamp now=Timestamp.valueOf(now());
        jdbc.update("INSERT INTO commerce_agent_task(task_id,tenant_id,shop_id,request_key,request_hash,goal,requested_json,planner_mode,planner_run_id,status,plan_version,fact_hash,fact_date,plan_json,created_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,'WAITING_APPROVAL',1,?,?,?,?,?,?)",task,tenant(),shop(),key,fingerprint,goal,JSON.toJSONString(lines(requested)),mode,run,factHash,day,JSON.toJSONString(plan),actor,now,now);
        version(task,1,facts,plan,"首次读取实时缺口；等待独立审批",actor);event(task,1,"CREATED",key,fingerprint,actor,"持久采购任务创建；未预留采购承诺或实物库存");
        return view(owned(task,false),false);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(long actor) {
        authority(actor,CommerceCapability.READ);List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_agent_task WHERE tenant_id=? AND shop_id=? ORDER BY updated_at DESC,task_id DESC LIMIT 100",tenant(),shop()))result.add(view(row,false));return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> get(String task,long actor) {authority(actor,CommerceCapability.READ);return view(owned(task,false),true);}
    @Transactional(readOnly=true)
    public Map<String,Object> byRequest(String key,long actor) {
        authority(actor,CommerceCapability.SUPPLY_DRAFT);require(key.matches("[A-Za-z0-9_:-]{1,80}"),"请求标识错误",400);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_agent_task WHERE tenant_id=? AND shop_id=? AND request_key=? AND created_by=?",tenant(),shop(),key,actor);
        require(!rows.isEmpty(),"业务任务不存在",404);return view(rows.get(0),false);
    }
    /** HTTP-only guard: task-managed commitments must pass their task's fact/version checks. */
    public void assertStandaloneDraft(String draft) {
        require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_agent_task WHERE tenant_id=? AND shop_id=? AND draft_id=?",Long.class,tenant(),shop(),draft)==0L,"该采购草稿属于持久任务，请从任务入口审批、执行或撤销",409);
    }
    public void assertStandaloneRequest(Map<String,Object> body) {
        String value=key(body).toUpperCase(Locale.ROOT);
        require(!value.startsWith("BTPROP:")&&!value.startsWith("BTREVIEW:")&&!value.startsWith("BTEXEC:")&&!value.startsWith("DRAFT:"),"请求标识使用了业务系统保留的命名空间",400);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> refresh(String task,Map<String,Object> body,long actor) {
        authority(actor,CommerceCapability.SUPPLY_DRAFT);key(body);lockShop();Map<String,Object> row=owned(task,true);operator(row,actor);
        require(open(row),"已结束任务不能重新规划",409);
        boolean changed=revalidate(row,actor,"恢复任务时重新核验实时缺口与日期");Map<String,Object> result=view(owned(task,false),true);result.put("replanRequired",changed);return result;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> review(String task,Map<String,Object> body,long actor) {
        authority(actor,CommerceCapability.SUPPLY_REVIEW);String key=key(body),decision=String.valueOf(body.get("decision")),note=text(body.get("note"),160,"审批意见");long requestedVersion=positive(body.get("planVersion"),Long.MAX_VALUE,"计划版本");
        require(Arrays.asList("APPROVE","REJECT").contains(decision),"审批结论错误",400);lockShop();Map<String,Object> row=owned(task,true);
        require(actor!=n(row.get("created_by")),"创建人不能审批自己的业务任务",403);
        String fingerprint=hash(Arrays.asList(requestedVersion,decision,note));
        List<Map<String,Object>> replay=jdbc.queryForList("SELECT * FROM commerce_agent_task_event WHERE task_id=? AND action IN('APPROVED','REJECTED') AND request_key=?",task,key);
        if(!replay.isEmpty()){require(n(replay.get(0).get("actor_id"))==actor&&fingerprint.equals(replay.get(0).get("request_hash")),"同一审批请求不能改变版本、审批人或结论",409);return view(row,false);}
        require(open(row)&&requestedVersion==n(row.get("plan_version")),"计划版本已变化，请读取新方案后审批",409);
        // Return a committed replan result. Throwing here would roll back cancellation of the stale approval.
        if(revalidate(row,actor,"审批前事实已变化，原方案需重新审批")){Map<String,Object> result=view(owned(task,false),true);result.put("replanRequired",true);return result;}
        require("WAITING_APPROVAL".equals(row.get("status")),"任务不处于待审批状态",409);
        if("REJECT".equals(decision)) {
            jdbc.update("UPDATE commerce_agent_task SET status='REJECTED',updated_at=? WHERE task_id=?",Timestamp.valueOf(now()),task);
            event(task,requestedVersion,"REJECTED",key,fingerprint,actor,note);return view(owned(task,false),true);
        }
        long creator=n(row.get("created_by"));creatorActive(creator);
        authority(creator,CommerceCapability.SUPPLY_DRAFT); // Never borrow the reviewer's procurement permissions.
        Map<String,Object> draft=planning.createDraft(map("requestKey",draftKey(task,requestedVersion),"items",JSON.parseArray(String.valueOf(row.get("plan_json")))),creator);
        require(n(draft.get("createdBy"))==creator&&"PENDING_APPROVAL".equals(draft.get("status")),"关联采购请求已被占用，需人工核验；未批准任务",409);
        String draftId=String.valueOf(draft.get("draftId"));planning.reviewDraft(draftId,map("requestKey","BTREVIEW:"+task+":"+requestedVersion,"decision","APPROVE","note",note),actor);
        jdbc.update("UPDATE commerce_agent_task SET status='APPROVED',approved_by=?,approved_version=?,draft_id=?,updated_at=? WHERE task_id=?",actor,requestedVersion,draftId,Timestamp.valueOf(now()),task);
        event(task,requestedVersion,"APPROVED",key,fingerprint,actor,note);return view(owned(task,false),true);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> execute(String task,Map<String,Object> body,long actor) {
        authority(actor,CommerceCapability.SUPPLY_DRAFT);String key=key(body);long v=positive(body.get("planVersion"),Long.MAX_VALUE,"计划版本");
        long warehouse=positive(body.get("warehouseId"),Long.MAX_VALUE,"仓库");String reference=text(body.get("sourceReference"),80,"供应商确认依据"),expected=text(body.get("expectedAt"),40,"预计到货日期");
        String fingerprint=hash(Arrays.asList(v,warehouse,reference,expected));lockShop();Map<String,Object> row=owned(task,true);operator(row,actor);
        if("EXECUTED".equals(row.get("status"))) {
            require(key.equals(row.get("execution_key"))&&fingerprint.equals(row.get("execution_hash")),"已执行任务只能用原业务键与原确认依据恢复结果",409);return view(row,true);
        }
        require("APPROVED".equals(row.get("status"))&&v==n(row.get("plan_version"))&&v==n(row.get("approved_version")),"该版本尚未独立审批，不能执行",409);
        require(n(row.get("approved_by"))!=n(row.get("created_by")),"缺少独立审批",403);
        creatorActive(n(row.get("created_by")));creatorActive(n(row.get("approved_by")));
        authority(n(row.get("created_by")),CommerceCapability.SUPPLY_DRAFT);
        authority(n(row.get("approved_by")),CommerceCapability.SUPPLY_REVIEW);
        if(revalidate(row,actor,"执行前事实或日期已变化，取消旧审批并重新规划")){Map<String,Object> result=view(owned(task,false),true);result.put("replanRequired",true);return result;}
        Map<String,Object> result=planning.executeDraft(String.valueOf(row.get("draft_id")),map("requestKey",executionKey(task,v),"warehouseId",warehouse,"sourceReference",reference,"expectedAt",expected),actor);
        jdbc.update("UPDATE commerce_agent_task SET status='EXECUTED',execution_key=?,execution_hash=?,result_json=?,updated_at=? WHERE task_id=?",key,fingerprint,JSON.toJSONString(result),Timestamp.valueOf(now()),task);
        event(task,v,"EXECUTED",key,fingerprint,actor,"已登记供应商确认；采购草稿与业务任务在同一事务提交");return view(owned(task,false),true);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> cancel(String task,Map<String,Object> body,long actor) {
        authority(actor,CommerceCapability.SUPPLY_DRAFT);String key=key(body),reason=text(body.get("reason"),160,"取消原因");lockShop();Map<String,Object> row=owned(task,true);operator(row,actor);
        if("CANCELLED".equals(row.get("status"))){List<Map<String,Object>> replay=jdbc.queryForList("SELECT * FROM commerce_agent_task_event WHERE task_id=? AND action='CANCELLED' AND request_key=?",task,key);require(replay.size()==1&&n(replay.get(0).get("actor_id"))==actor&&hash(reason).equals(replay.get(0).get("request_hash")),"已撤销任务只能重放原撤销请求",409);return view(row,true);}
        require(open(row),"已结束任务不能撤销；已确认供货需走供应商变更审批",409);invalidateDraft(row,actor,reason);
        jdbc.update("UPDATE commerce_agent_task SET status='CANCELLED',approved_by=NULL,approved_version=NULL,updated_at=? WHERE task_id=?",Timestamp.valueOf(now()),task);
        event(task,n(row.get("plan_version")),"CANCELLED",key,hash(reason),actor,reason);return view(owned(task,false),true);
    }
    private boolean revalidate(Map<String,Object> row,long actor,String reason) {
        SortedMap<Long,Long> requested=parseRequested(row);Map<String,Object> current=facts(requested,(String)row.get("draft_id"),actor);
        if(hash(current).equals(row.get("fact_hash")))return false;
        invalidateDraft(row,actor,reason);long v=n(row.get("plan_version"))+1;
        // Re-read after removing only this task's old commitment. No other task or receipt is modified.
        current=facts(requested,null,actor);List<Map<String,Object>> plan=plan(requested,current);
        jdbc.update("UPDATE commerce_agent_task SET status=?,plan_version=?,fact_hash=?,fact_date=?,plan_json=?,approved_by=NULL,approved_version=NULL,draft_id=NULL,updated_at=? WHERE task_id=?",plan.isEmpty()?"NO_ACTION":"WAITING_APPROVAL",v,hash(current),Date.valueOf(today()),JSON.toJSONString(plan),Timestamp.valueOf(now()),row.get("task_id"));
        version(String.valueOf(row.get("task_id")),v,current,plan,reason,actor);event(String.valueOf(row.get("task_id")),v,"REPLANNED",null,null,actor,reason);return true;
    }
    private Map<String,Object> facts(SortedMap<Long,Long> requested,String ownDraft,long actor) {
        // The ordinary transaction writers also lock products before changing stock.
        // Hold selected, shop-owned product locks until the approval/execution commits,
        // so a receipt cannot fill the gap between revalidation and supplier registration.
        for(Long product:requested.keySet())jdbc.queryForList("SELECT p.product_id FROM product p JOIN commerce_product_shop ps ON ps.product_id=p.product_id WHERE ps.shop_id=? AND p.product_id=? FOR UPDATE",shop(),product);
        Map<Long,Long> own=new HashMap<>();if(ownDraft!=null)for(Map<String,Object> line:jdbc.queryForList("SELECT product_id,quantity FROM commerce_supply_line WHERE draft_id=? AND shop_id=? AND state IN('PENDING_APPROVAL','APPROVED')",ownDraft,shop()))own.put(n(line.get("product_id")),n(line.get("quantity")));
        List<Map<String,Object>> result=new ArrayList<>();
        for(Object raw:(List<?>)planning.replenishment(actor).get("items")) {
            Map<String,Object> p=(Map<String,Object>)raw;long product=n(p.get("productId"));if(!requested.containsKey(product))continue;
            Map<String,Object> normalized=new LinkedHashMap<>();
            for(String field:Arrays.asList("productId","productCode","productName","onHandStock","reservedStock","activityStock","unavailableStock","availableStock","incomingStock","incomingDueWithinLead","overdueIncoming","salesUnits","reorderPoint","targetStock","supplierLeadDays","leadTimeKnown","forecastDemand","rawSuggestedQuantity"))normalized.put(field,p.get(field));
            long committed=Math.max(0,n(p.get("committedSupplyQuantity"))-own.getOrDefault(product,0L));
            normalized.put("committedSupplyQuantity",committed);normalized.put("suggestedQuantity",Math.max(0,n(p.get("rawSuggestedQuantity"))-committed));result.add(normalized);
        }
        result.sort(Comparator.comparingLong(p->n(p.get("productId"))));return map("asOfDate",today().toString(),"items",result);
    }
    private List<Map<String,Object>> plan(SortedMap<Long,Long> requested,Map<String,Object> facts) {
        List<Map<String,Object>> result=new ArrayList<>();for(Object raw:(List<?>)facts.get("items")){Map<String,Object> fact=(Map<String,Object>)raw;long product=n(fact.get("productId")),qty=Math.min(requested.get(product),n(fact.get("suggestedQuantity")));if(qty>0)result.add(map("productId",product,"productCode",fact.get("productCode"),"productName",fact.get("productName"),"quantity",qty,"currentSuggestedQuantity",fact.get("suggestedQuantity"),"leadTimeKnown",fact.get("leadTimeKnown"),"supplierLeadDays",fact.get("supplierLeadDays")));}return result;
    }
    private void invalidateDraft(Map<String,Object> row,long actor,String reason) {
        if(row.get("draft_id")==null)return;String draft=(String)row.get("draft_id");
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_replenishment_draft WHERE draft_id=? AND shop_id=? FOR UPDATE",draft,shop());
        require(rows.size()==1&&n(rows.get(0).get("actor_id"))==n(row.get("created_by")),"关联采购草稿归属异常，需人工核验",409);
        require(Arrays.asList("PENDING_APPROVAL","APPROVED").contains(rows.get(0).get("status")),"关联采购已被外部执行或撤销，需人工核验",409);
        jdbc.update("UPDATE commerce_replenishment_draft SET status='CANCELLED' WHERE draft_id=?",draft);jdbc.update("UPDATE commerce_supply_line SET state='CANCELLED' WHERE draft_id=?",draft);
        jdbc.update("INSERT INTO commerce_supply_event(event_id,shop_id,draft_id,product_id,action,reference_id,actor_id,created_at) VALUES (?,?,?,NULL,'TASK_INVALIDATED',?,?,?)",id("SE"),shop(),draft,row.get("task_id"),actor,Timestamp.valueOf(now()));
    }
    private void version(String task,long v,Map<String,Object> facts,List<Map<String,Object>> plan,String reason,long actor){jdbc.update("INSERT INTO commerce_agent_task_version VALUES (?,?,?,?,?,?,?,?,?)",task,v,hash(facts),Date.valueOf(today()),JSON.toJSONString(facts),JSON.toJSONString(plan),reason,actor,Timestamp.valueOf(now()));}
    private void event(String task,long v,String action,String key,String fingerprint,long actor,String detail){jdbc.update("INSERT INTO commerce_agent_task_event VALUES (?,?,?,?,?,?,?,?,?)",id("BE"),task,v,action,key,fingerprint,actor,detail,Timestamp.valueOf(now()));}
    private Map<String,Object> view(Map<String,Object> row,boolean detail) {
        Map<String,Object> result=map("taskId",row.get("task_id"),"tenantId",row.get("tenant_id"),"shopId",row.get("shop_id"),"requestKey",row.get("request_key"),"goal",row.get("goal"),"plannerMode",row.get("planner_mode"),"plannerRunId",row.get("planner_run_id"),"status",row.get("status"),"planVersion",n(row.get("plan_version")),"factVersion",row.get("fact_hash"),"factDate",String.valueOf(row.get("fact_date")),"needsDateRefresh",open(row)&&!today().toString().equals(String.valueOf(row.get("fact_date"))),"requestedItems",JSON.parseArray(String.valueOf(row.get("requested_json"))),"items",JSON.parseArray(String.valueOf(row.get("plan_json"))),"createdBy",row.get("created_by"),"approvedBy",row.get("approved_by"),"approvedVersion",row.get("approved_version"),"draftId",row.get("draft_id"),"executionKey",row.get("execution_key"),"result",row.get("result_json")==null?null:JSON.parseObject(String.valueOf(row.get("result_json"))),"createdAt",row.get("created_at"),"updatedAt",row.get("updated_at"),"stockPosted",false,"paymentCalled",false);
        // DATETIME stores seconds and UUIDs are not sequence numbers. A task has
        // one effective decision/execution per version, so its state transitions
        // supply the deterministic order even when every event shares a timestamp.
        if(detail){result.put("versions",jdbc.queryForList("SELECT plan_version AS planVersion,fact_hash AS factVersion,fact_date AS factDate,plan_json AS planJson,reason,created_by AS createdBy,created_at AS createdAt FROM commerce_agent_task_version WHERE task_id=? ORDER BY plan_version",row.get("task_id")));result.put("events",jdbc.queryForList("SELECT action,plan_version AS planVersion,actor_id AS actorId,detail,created_at AS createdAt FROM commerce_agent_task_event WHERE task_id=? ORDER BY plan_version,CASE action WHEN 'CREATED' THEN 0 WHEN 'REPLANNED' THEN 0 WHEN 'APPROVED' THEN 1 WHEN 'REJECTED' THEN 1 WHEN 'EXECUTED' THEN 2 WHEN 'CANCELLED' THEN 2 ELSE 3 END,created_at,event_id",row.get("task_id")));}return result;
    }
    private Map<String,Object> owned(String id,boolean lock){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_agent_task WHERE task_id=? AND tenant_id=? AND shop_id=?"+(lock?" FOR UPDATE":""),id,tenant(),shop());require(!rows.isEmpty(),"业务任务不存在",404);return rows.get(0);}
    private void operator(Map<String,Object> row,long actor){Map<String,Object> member=merchants.requireCapability(shop(),actor,CommerceCapability.SUPPLY_DRAFT);require(actor==n(row.get("created_by"))||"OWNER".equals(member.get("member_role")),"只能继续自己的采购任务",403);}
    private void creatorActive(long creator){require(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0'",Long.class,creator)==1L,"任务创建人或审批人已停用，不能沿用旧授权执行采购",403);}
    private void authority(long actor,CommerceCapability capability){merchants.requireCapability(shop(),actor,capability);}
    private void lockShop(){jdbc.queryForList("SELECT shop_id FROM commerce_shop WHERE shop_id=? FOR UPDATE",shop());}
    private static boolean open(Map<String,Object> row){return Arrays.asList("WAITING_APPROVAL","APPROVED").contains(row.get("status"));}
    private static SortedMap<Long,Long> parseRequested(Map<String,Object> row){return requested(map("items",JSON.parseArray(String.valueOf(row.get("requested_json")))));}
    private static SortedMap<Long,Long> requested(Map<String,Object> body){Object raw=body.get("items");require(raw instanceof List&&!((List<?>)raw).isEmpty()&&((List<?>)raw).size()<=20,"任务需包含1至20个商品",400);SortedMap<Long,Long> r=new TreeMap<>();for(Object value:(List<?>)raw){require(value instanceof Map,"商品建议格式错误",400);Map<?,?> item=(Map<?,?>)value;long product=positive(item.get("productId"),Long.MAX_VALUE,"商品"),qty=positive(item.get("quantity"),999,"采购上限");require(r.put(product,qty)==null,"任务商品不能重复",400);}return r;}
    private static List<Map<String,Object>> lines(SortedMap<Long,Long> requested){List<Map<String,Object>> r=new ArrayList<>();requested.forEach((product,qty)->r.add(map("productId",product,"quantity",qty)));return r;}
    private static long positive(Object value,long max,String name){String s=String.valueOf(value);require(s.matches("[0-9]{1,18}"),name+"需为整数",400);long n=Long.parseLong(s);require(n>0&&n<=max,name+"超出范围",400);return n;}
    private static String text(Object value,int max,String name){require(value instanceof String&&!((String)value).trim().isEmpty()&&((String)value).trim().length()<=max,name+"格式错误",400);return ((String)value).trim();}
    private static String key(Map<String,Object> body){String value=text(body.get("requestKey"),80,"业务请求键");require(value.matches("[A-Za-z0-9_:-]+"),"业务请求键格式错误",400);return value;}
    private static String draftKey(String task,long v){return "BTPROP:"+task+":"+v;}
    private static String executionKey(String task,long v){return "BTEXEC:"+task+":"+v;}
    private static String tenant(){return TenantContext.id();}private static String shop(){return CommerceShopContext.id();}
    private LocalDate today(){return LocalDate.now(clock);}private LocalDateTime now(){return LocalDateTime.now(clock);}
    private static long n(Object value){return value==null?0:((Number)value).longValue();}
    private static String id(String prefix){return prefix+UUID.randomUUID().toString().replace("-","").substring(0,28);}
    private static String hash(Object value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(value).getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b));return s.toString();}catch(Exception ex){throw new IllegalStateException(ex);}}
    private static Map<String,Object> map(Object... args){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)r.put((String)args[i],args[i+1]);return r;}
    private static void require(boolean condition,String message,int code){if(!condition)throw new ServiceException(message,code);}
}
