package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** Explicit test-provider facts only: an empty parcel has no invented carrier history. */
@Component
@Profile({"local","commerce"})
public class CommerceLocalLogisticsProvider implements CommerceLogisticsProvider {
    private final JdbcTemplate jdbc;
    public CommerceLocalLogisticsProvider(DataSource source){this.jdbc=new JdbcTemplate(source);}
    @Override public String name(){return "LOCAL_SIMULATED";}
    @Override public String source(){return "SIMULATED";}
    @Override public Observation query(Subject subject) {
        List<Map<String,Object>> events=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_logistics_event WHERE tenant_id=? AND shop_id=? AND subject_type=? AND subject_id=? AND provider=? ORDER BY occurred_at,event_id",subject.tenantId,subject.shopId,subject.type,subject.id,name())) {
            Map<String,Object> event=new LinkedHashMap<>();event.put("eventId",row.get("event_id"));event.put("status",row.get("status"));
            event.put("statusLabel",CommerceLogisticsService.statusLabel(String.valueOf(row.get("status"))));
            event.put("occurredAt",time(row.get("occurred_at")));event.put("location",row.get("location"));event.put("description",row.get("description"));event.put("recordedAt",time(row.get("recorded_at")));
            events.add(event);
        }
        return new Observation(LocalDateTime.now().toString(),events);
    }

    /** Caller authorizes and locks the associated order/case, serializing idempotent writes. */
    void record(Subject subject,String key,String status,LocalDateTime occurred,String location,String description,long actor) {
        String payload=hash(Arrays.asList(status,occurred.toString(),location,description));
        List<String> existing=jdbc.queryForList("SELECT payload_hash FROM commerce_logistics_event WHERE tenant_id=? AND shop_id=? AND subject_type=? AND subject_id=? AND request_key=?",String.class,subject.tenantId,subject.shopId,subject.type,subject.id,key);
        if(!existing.isEmpty()) {
            if(!payload.equals(existing.get(0)))throw new ServiceException("同一模拟物流请求不能更改承运节点",409);
            return;
        }
        jdbc.update("INSERT INTO commerce_logistics_event(tenant_id,shop_id,subject_type,subject_id,request_key,payload_hash,provider,status,occurred_at,location,description,actor_user_id,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",subject.tenantId,subject.shopId,subject.type,subject.id,key,payload,name(),status,java.sql.Timestamp.valueOf(occurred),location,description,actor);
    }
    private static String time(Object value){return value==null?null:String.valueOf(value).replace(' ','T').replace(".0","");}
    private static String hash(Object payload) {
        try {String serialized=com.alibaba.fastjson2.JSON.toJSONString(payload);byte[] digest=MessageDigest.getInstance("SHA-256").digest(serialized.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte part:digest)result.append(String.format("%02x",part));return result.toString();}
        catch(Exception error){throw new IllegalStateException(error);}
    }
}
