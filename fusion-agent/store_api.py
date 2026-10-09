"""Simlect C-side contracts backed by Java catalog and isolated demo sessions."""
from __future__ import annotations

import asyncio
import json
import os
import re
import secrets
import sqlite3
import threading
import time
from contextlib import contextmanager
from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib.parse import quote

import httpx
from fastapi import APIRouter, HTTPException, Request, Response, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from langgraph.graph import END, START, StateGraph
from commerce_api import CommerceAPI
from shared_store import RedisSharedStore, SESSION_TTL, ALIVE
from public_read_cache import PublicReadCache
from business_catalog import profile_for, evidence_snapshot, registry_version
from product_media import IMAGES, product_cover
from execution_runtime import (initialize_messages, expire_messages, retain_messages, create_message,
                               renew_message, finish_message, cancel_message, LostLease, redact, CURRENT_MESSAGE, bind_message_run)

DB_PATH = Path(os.getenv("FUSION_STORE_DB", str(Path(__file__).parent.parent / "runtime" / "store-demo.sqlite3")))
COOKIE = os.getenv("FUSION_SESSION_COOKIE", "simlect_demo_session")
PRIVATE_HEADERS = {"Cache-Control": "private, no-store"}
ORIGINS = (set(os.environ["FUSION_ALLOWED_ORIGINS"].split(",")) - {""}) if os.getenv("FUSION_ALLOWED_ORIGINS") else {"http://127.0.0.1:6001", "http://localhost:6001", "http://127.0.0.1:5173", "http://127.0.0.1:7050"}
_DB_INITIALIZED: set[Path] = set()
_DB_INIT_LOCK = threading.Lock()

# Quote records share the session's Redis slot with its cart. A confirmation
# commits every row and the idempotency marker in one Lua transaction.
QUOTE_READ = ALIVE + "return {redis.call('HGET',KEYS[4],ARGV[1]) or ''}"
QUOTE_SAVE = ALIVE + """
local old=redis.call('HGET',KEYS[4],ARGV[1])
if old then
  local current=cjson.decode(old)
  if tonumber(current.revision) ~= tonumber(ARGV[2]) or current.confirmationStatus == 'ADDED' then return {old} end
elseif tonumber(ARGV[2]) ~= 0 then return {''} end
redis.call('HSET',KEYS[4],ARGV[1],ARGV[3]);redis.call('EXPIRE',KEYS[4],ttl)
return {ARGV[3]}
"""
QUOTE_ADD = ALIVE + """
local old=redis.call('HGET',KEYS[4],ARGV[1])
if not old then return {''} end
local plan=cjson.decode(old)
if plan.confirmationStatus == 'ADDED' or tonumber(plan.revision) ~= tonumber(ARGV[2]) then return {old} end
if tonumber(plan.expiresAt) <= tonumber(ARGV[3]) then return {-410} end
local candidates=cjson.decode(ARGV[4]);local staged={};local newRows=0
for _,candidate in ipairs(candidates) do
  local previous=redis.call('HGET',KEYS[3],candidate.product_id)
  local row={id=candidate.id,product_id=candidate.product_id,quantity=0,created=ARGV[5]}
  if previous then row=cjson.decode(previous) else newRows=newRows+1 end
  row.quantity=tonumber(row.quantity)+tonumber(candidate.quantity)
  if row.quantity < 1 or row.quantity > tonumber(candidate.available) then return {-409} end
  table.insert(staged,row)
end
if redis.call('HLEN',KEYS[3])+newRows > 200 then return {-422} end
for _,row in ipairs(staged) do redis.call('HSET',KEYS[3],row.product_id,cjson.encode(row)) end
redis.call('EXPIRE',KEYS[3],ttl)
plan.confirmationStatus='ADDED';plan.confirmedAt=tonumber(ARGV[3])
local encoded=cjson.encode(plan)
redis.call('HSET',KEYS[4],ARGV[1],encoded);redis.call('EXPIRE',KEYS[4],ttl)
return {encoded}
"""


@contextmanager
def db():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB_PATH, timeout=10)
    connection.row_factory = sqlite3.Row
    # Schema setup belongs to first use/startup, not every visitor read.
    with _DB_INIT_LOCK:
        if DB_PATH not in _DB_INITIALIZED:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.executescript("""
        CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, expires REAL NOT NULL, context_product TEXT, keywords TEXT NOT NULL DEFAULT '[]');
        CREATE TABLE IF NOT EXISTS carts(id TEXT PRIMARY KEY, session_id TEXT NOT NULL, product_id TEXT NOT NULL, quantity INTEGER NOT NULL CHECK(quantity>0), UNIQUE(session_id,product_id));
        CREATE TABLE IF NOT EXISTS orders(id TEXT PRIMARY KEY, session_id TEXT NOT NULL, status INTEGER NOT NULL DEFAULT 0, payload TEXT NOT NULL, request_key TEXT NOT NULL, request_hash TEXT NOT NULL, UNIQUE(session_id,request_key));
        CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, question TEXT NOT NULL, answer TEXT NOT NULL DEFAULT '', status INTEGER NOT NULL DEFAULT 1, biz_type TEXT NOT NULL DEFAULT 'chat', sent_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS proposals(token TEXT PRIMARY KEY, session_id TEXT NOT NULL, order_id TEXT NOT NULL, status INTEGER NOT NULL DEFAULT 0, expires REAL NOT NULL);
            """)
            initialize_messages(connection)
            connection.commit()
            _DB_INITIALIZED.add(DB_PATH)
    try:
        with connection:
            yield connection
    finally:
        connection.close()


def integer(value):
    if isinstance(value, bool) or not re.fullmatch(r"-?\d+", str(value)):
        raise HTTPException(422, "数量必须是整数")
    return int(value)


def model_question(question):
    # The original storefront renders a product card in the user bubble. Its
    # client-supplied metadata is presentation only; business facts come from Java.
    return re.sub(r"<<<PRODUCT_CONSULT>>>.*?<<<END_CARD>>>\s*", "", question, flags=re.S).strip()


def ok(value=None):
    return {"code": 200, "data": value}


def page(rows, params=None):
    params = params or {}
    try:
        number = max(1, int(params.get("pageNo") or 1))
        size = min(50, max(1, int(params.get("pageSize") or 20)))
    except (TypeError, ValueError):
        number, size = 1, 20
    return {"list": rows[(number-1)*size:number*size], "pageNo": number, "pageSize": size,
            "pageTotal": max(1, (len(rows)+size-1)//size), "totalCount": len(rows)}


def product_shape(product):
    category = product.get("category", "智能家居").removeprefix("演示·")
    profile = product.get("profile") or profile_for(product["code"])
    cover = product_cover(product["code"], profile=profile)
    return {"productId": product["id"], "productCode": product["code"], "productName": product["name"],
            "categoryId": category, "categoryName": category, "cover": cover, "images": [cover],
            "minPrice": product["price"], "price": product["price"], "stock": product["stock"],
            "availableStock": product["stock"], "stockLabel": "可售库存",
            "status": 1, "description": product["remark"], "productDesc": f"{product['remark']}\n\n规格：{product['spec']}",
            "spec": product["spec"], "technicalProfile": profile, "imageIsIllustration": True,
            "demo": product["code"].startswith("DEMO-")}


def address_input(params, address_id):
    def text(key, maximum):
        value = str(params.get(key) or "").strip()
        if not value or len(value) > maximum or (key == "address" and len(value) < 5) or re.search(r"[\x00-\x1f\x7f]", value):
            raise HTTPException(422, {"addressee": "收货人须为1至64字", "phone": "联系电话格式不正确", "address": "收货地址须为5至300字"}[key])
        return value
    phone = text("phone", 24)
    if not re.fullmatch(r"[+0-9 ()-]{6,24}", phone) or not re.search(r"[0-9]", phone): raise HTTPException(422, "联系电话格式不正确")
    return {"addressId": address_id, "addressee": text("addressee", 64), "phone": phone,
            "address": text("address", 300), "defaultType": 1 if str(params.get("defaultType")) == "1" else 0}


def user_shape(session):
    return {"userId": session[:16], "nickName": ("Studio · " if os.getenv("FUSION_TENANT") == "studio" else "") + "演示访客", "userName": "演示访客", "tenantId": os.getenv("FUSION_TENANT", "demo"), "avatar": "/demo-media/fallback.svg", "demo": True}


def active_order_workflow(order):
    if isinstance(order.get("availableActions"), list):
        return any(action in order["availableActions"] for action in ("PAY", "CANCEL", "SHIP", "RECEIVE"))
    return order.get("afterSalesStatus") in (None, "", "REJECTED")


def order_shape(row):
    payload = json.loads(row["payload"])
    payload["orderStatus"] = row["status"]
    payload["demo"] = True
    payload["legacy"] = True
    payload["statusName"] = "历史演示单（未接库存）" if row["status"] == 0 else "历史演示单（已取消）"
    return payload


def get_order(session, order_id):
    with db() as connection:
        row = connection.execute("SELECT * FROM orders WHERE id=? AND session_id=?", (order_id, session)).fetchone()
    if not row:
        raise HTTPException(404, "没有找到当前访客的订单")
    return order_shape(row)


def owned_orders(session):
    with db() as connection:
        rows = connection.execute("SELECT * FROM orders WHERE session_id=? ORDER BY rowid DESC", (session,)).fetchall()
    return [order_shape(row) for row in rows]


def cancel_order(session, order_id):
    get_order(session, order_id)
    with db() as connection:
        connection.execute("UPDATE orders SET status=4 WHERE id=? AND session_id=? AND status=0", (order_id, session))
    return get_order(session, order_id)


def build_store_router(service):
    router = APIRouter()
    shared_store = RedisSharedStore(DB_PATH)
    tenant_scope, shop_scope = shared_store.tenant, shared_store.shop
    router.add_event_handler("startup", shared_store.start)
    router.add_event_handler("shutdown", shared_store.close)
    valid_session = shared_store.valid_session

    async def current_session(request):
        session = await valid_session(request.cookies.get(COOKIE))
        if not session:
            raise HTTPException(401, "请重新进入演示商城")
        return session

    sockets: dict[str, set[WebSocket]] = {}
    token_cache = {"value": "", "until": 0.0}
    token_lock = asyncio.Lock()
    proposal_lock = asyncio.Lock()
    tasks: dict[int, asyncio.Task] = {}
    def recover_interrupted_messages():
        with db() as connection:
            expire_messages(connection, tenant_scope, shop_scope)
            retain_messages(connection)

    router.add_event_handler("startup", recover_interrupted_messages)

    async def stop_tasks():
        active = list(tasks.values())
        for task in active: task.cancel()
        if active: await asyncio.gather(*active, return_exceptions=True)
    router.add_event_handler("shutdown", stop_tasks)

    async def reader_token():
        if token_cache["until"] > time.time():
            return token_cache["value"]
        async with token_lock:
            if token_cache["until"] > time.time():
                return token_cache["value"]
            client = await commerce.get_client()
            user = os.getenv("FUSION_COMMERCE_USER", "").strip()
            password = os.getenv("FUSION_COMMERCE_PASSWORD", "").strip()
            if not user or not password:
                raise HTTPException(503, "商城专用业务账号尚未配置")
            response = await client.post(service.JAVA_URL + "/login", json={
                "username": user, "password": password})
            response.raise_for_status()
            body = response.json()
            if body.get("code") != 200 or not body.get("token"):
                raise HTTPException(502, "商品数据服务登录失败")
            token_cache.update(value="Bearer " + body["token"], until=time.time()+900)
            return token_cache["value"]

    commerce = CommerceAPI(service, reader_token)
    router.add_event_handler("startup", commerce.start)
    router.add_event_handler("shutdown", commerce.close)
    # Each router has one trusted tenant/shop identity. No owner-specific data enters these caches.
    cache_seconds = max(0.0, float(os.getenv("FUSION_PUBLIC_CACHE_SECONDS", "2")))
    catalog_cache = PublicReadCache(cache_seconds)
    campaign_cache = PublicReadCache(cache_seconds)

    def invalidate_public_reads():
        catalog_cache.invalidate()
        campaign_cache.invalidate()

    async def customer_orders(session):
        orders = owned_orders(session) + await commerce.orders(session)
        return sorted(orders, key=lambda order: order["createTime"], reverse=True)

    async def customer_order(session, order_id):
        with db() as connection:
            legacy = connection.execute("SELECT * FROM orders WHERE id=? AND session_id=?", (order_id, session)).fetchone()
        return order_shape(legacy) if legacy else await commerce.detail(session, order_id)

    async def cancel_customer_order(session, order_id):
        order = await customer_order(session, order_id)
        if order.get("legacy"):
            return cancel_order(session, order_id)
        result = await commerce.action(session, order_id, "cancel")
        invalidate_public_reads()
        return result

    async def public_catalog():
        return await catalog_cache.get("catalog", read_public_catalog)

    async def read_public_catalog():
        inventory = await commerce.inventory()
        catalog = []
        # A single read-only Java projection replaces full ERP product + warehouse scans.
        for stock in inventory:
            if stock.get("listed") not in (True, 1, "1") or str(stock.get("productStatus", "0")) != "0" or not stock.get("snapshotReady", True):
                continue
            catalog.append({"id": str(stock["productId"]), "code": stock["productCode"],
                            "name": stock["productName"], "spec": str(stock.get("spec") or ""),
                            "price": float(stock["price"]) if stock.get("price") is not None else None,
                            "category": str(stock.get("categoryName") or "智能家居"),
                            "remark": str(stock.get("description") or ""), "stock": float(stock["availableStock"]),
                            "stock_kind": "可售库存", "profile": profile_for(stock["productCode"])})
        return catalog

    async def public_campaigns():
        async def load():
            activities, products = await asyncio.gather(commerce.request("GET", "/commerce/activities"), public_catalog())
            catalog = {str(p["id"]): p for p in products}
            return [{**a, "originalPrice": catalog[str(a["productId"])]["price"],
                     "spec": catalog[str(a["productId"])]["spec"],
                     "description": catalog[str(a["productId"])]["remark"],
                     "categoryName": product_shape(catalog[str(a["productId"])])["categoryName"]}
                    for a in activities if str(a["productId"]) in catalog]
        rows = await campaign_cache.get("campaigns", load)
        now = datetime.now()
        return [{**row, "serverTime": now.isoformat(timespec="seconds")}
                for row in rows if datetime.fromisoformat(row["endsAt"].replace(" ", "T")) > now]

    async def load_customer_catalog(state):
        catalog = await public_catalog()
        return {"catalog": catalog, "trace": [{"step": "fetch_data", "status": "ok", "detail": "读取当前上架商品、账面库存和订单预留后的可售库存。"}]}

    async def fetch_bundle_quote(items, units):
        return await commerce.request("POST", "/commerce/planning/quote", body={
            "items": [{"productId": str(item["productId"]), "quantity": integer(item["quantity"])} for item in items],
            "units": integer(units)})

    def quote_field(token):
        if not re.fullmatch(r"qt_[a-f0-9]{32}", str(token)):
            raise HTTPException(404, "没有找到当前访客的报价草稿")
        return "quote-draft:" + token

    async def read_quote(session, token):
        result = await shared_store._eval(QUOTE_READ, session, quote_field(token))
        if not result or not result[0]: raise HTTPException(404, "没有找到当前访客的报价草稿")
        plan = json.loads(result[0])
        if plan.get("confirmationStatus") != "ADDED" and plan["expiresAt"] <= time.time():
            plan["confirmationStatus"] = "EXPIRED"
        return plan

    async def save_quote(session, plan, previous_revision=0):
        result = await shared_store._eval(QUOTE_SAVE, session, quote_field(plan["quoteToken"]),
                                          previous_revision, json.dumps(plan, ensure_ascii=False))
        if not result or not result[0]: raise HTTPException(404, "报价草稿已失效，请重新报价")
        return json.loads(result[0])

    async def confirm_bundle_quote(session, token, revision):
        plan = await read_quote(session, token)
        if plan["confirmationStatus"] == "ADDED" or plan["revision"] != integer(revision): return plan
        if plan["confirmationStatus"] == "EXPIRED": raise HTTPException(409, "报价已过期，请重新生成方案")
        if plan["status"] != "READY_FOR_REVIEW": raise HTTPException(409, "请先补全方案条件或解决缺货，再确认加购")
        # The persisted request is server-issued. Re-check manufacturer facts
        # and current listed SKUs as well as prices, without substituting a BOM.
        from business_planning import apply_authoritative_quote, prepare_bundle, alternative_candidates
        previous = {str(item["productId"]): Decimal(str(item["unitPrice"])) for item in plan["items"]}
        catalog = await read_public_catalog()
        request_snapshot = {**plan["request"], "requirements": [],
                            "items": [{"productId": item["productId"], "quantity": item["quantity"]} for item in plan["items"]]}
        fresh, _ = prepare_bundle(request_snapshot, catalog)
        fresh.update({key: plan[key] for key in ("quoteToken", "revision", "expiresAt", "confirmationStatus")})
        quote_result = await fetch_bundle_quote(plan["items"], plan["units"])
        try: fresh = apply_authoritative_quote(fresh, quote_result, catalog)
        except ValueError: raise HTTPException(502, "报价数据未能核验，请稍后重新报价") from None
        fresh["alternatives"] = alternative_candidates(fresh, catalog)
        changed = any(Decimal(str(item["unitPrice"])) != previous[str(item["productId"])] for item in fresh["items"])
        evidence_changed=plan.get('evidenceSnapshot')!=fresh.get('evidenceSnapshot')
        if changed or evidence_changed or fresh["status"] != "READY_FOR_REVIEW":
            fresh.update(revision=plan["revision"] + 1, confirmationStatus="REPRICE_REQUIRED" if changed else 'EVIDENCE_REVIEW_REQUIRED' if evidence_changed and fresh['status']=='READY_FOR_REVIEW' else "REVIEW_REQUIRED")
            return await save_quote(session, fresh, plan["revision"])
        if registry_version()!=fresh['evidenceSnapshot']['registryVersion']:
            raise HTTPException(409,'选型资料刚刚更新，请重新核对方案')
        candidates = [{"id": secrets.token_hex(8), "product_id": item["productId"],
                       "quantity": item["quantity"] * fresh["units"], "available": item["availableStock"]}
                      for item in fresh["items"]]
        result = await shared_store._eval(QUOTE_ADD, session, quote_field(token), plan["revision"],
                                          time.time(), json.dumps(candidates), time.time_ns())
        if result and result[0] == -410: raise HTTPException(409, "报价已过期，请重新生成方案")
        if not result or not result[0]: raise HTTPException(404, "报价草稿已失效，请重新生成")
        return json.loads(result[0])

    graph_builder = StateGraph(service.ChatState)
    graph_builder.add_node("fetch_data", load_customer_catalog)
    graph_builder.add_node("retrieve", service.retrieve)
    graph_builder.add_node("answer", service.answer)
    graph_builder.add_edge(START, "fetch_data")
    graph_builder.add_edge("fetch_data", "retrieve")
    graph_builder.add_edge("retrieve", "answer")
    graph_builder.add_edge("answer", END)
    customer_graph = graph_builder.compile()

    async def send(session, payload):
        for socket in tuple(sockets.get(session, ())):
            try:
                await socket.send_json(payload)
            except (WebSocketDisconnect, RuntimeError, OSError):
                sockets.get(session, set()).discard(socket)

    async def make_answer(session, message_id, question, product_id, lease, resume_id=None):
        parent = asyncio.current_task()
        def bind_run(run_id):
            with db() as connection: bind_message_run(connection,message_id,lease,run_id)
        message_context=CURRENT_MESSAGE.set(bind_run)
        async def keepalive():
            while True:
                await asyncio.sleep(1)
                try:
                    with db() as connection: renew_message(connection, message_id, lease)
                except LostLease:
                    parent.cancel()
                    return
        heartbeat = asyncio.create_task(keepalive())
        try:
            orders = await customer_orders(session)
            # Write intent yields a user confirmation card; the LLM never executes it.
            cancellation = re.search(r"取消|撤销", question) and "订单" in question and not re.search(r"(?:不要|别|不想|不需要|无需).{0,5}(?:取消|撤销)|(?:如何|怎么|怎样).{0,4}(?:取消|撤销)", question)
            if cancellation:
                requested = next((order for order in orders if order["orderId"] in question), None)
                pending = [order for order in orders if order["orderStatus"] == 0]
                target = requested or (pending[0] if len(pending) == 1 and not re.search(r"SC\d", question) else None)
                if target and target["orderStatus"] == 0:
                    action_token = "act_" + secrets.token_hex(16)
                    with db() as connection:
                        connection.execute("INSERT INTO proposals VALUES(?,?,?,?,?)", (action_token, session, target["orderId"], 0, time.time()+600))
                    payload = {"type": "ACTION_CONFIRM", "token": action_token, "actionType": "cancel_order", "label": "取消订单", "summary": "核对订单后确认取消，确认前订单不会改变。", "intro": "找到了这笔未支付订单。请核对后再取消。", "confirmText": "确认取消订单", "riskTip": "确认后释放这笔订单的预留库存；未发生真实收款，无需退款。" if not target.get("legacy") else "历史演示单未关联库存，取消不改变库存。", "status": 0, "orderId": target["orderId"], "orderAmount": target["amount"], "items": target["orderItemList"]}
                    result_text, biz = json.dumps(payload, ensure_ascii=False), "cancel_order"
                else:
                    result_text, biz = "请提供要取消的订单号；只能取消当前访客的未支付订单。", "chat"
            else:
                with db() as connection:
                    previous = connection.execute("SELECT question,answer FROM messages WHERE session_id=? AND (tenant_id=? OR tenant_id='') AND shop_id=? AND id<? AND status=2 ORDER BY id DESC LIMIT 3", (session,tenant_scope,shop_scope,message_id)).fetchall()
                    recent_plans = connection.execute("SELECT answer FROM messages WHERE session_id=? AND (tenant_id=? OR tenant_id='') AND shop_id=? AND id<? AND status=2 AND answer LIKE '%\"businessPlan\"%' ORDER BY id DESC LIMIT 1", (session,tenant_scope,shop_scope,message_id)).fetchall()
                history = []
                for row in reversed(previous):
                    history.append({"role": "user", "content": model_question(row["question"])[:1000]})
                    text = row["answer"]
                    if text.startswith('{'):
                        structured = json.loads(text)
                        text = structured.get("intro") or structured.get("answer") or structured.get("summary") or ""
                    history.append({"role": "assistant", "content": text[:1000]})
                states = {0: "UNPAID", 1: "PAID", 2: "SHIPPED", 3: "RECEIVED", 4: "CANCELED"}
                context_orders = [{"id": o["orderId"], "orderNo": o["orderId"], "status": "LEGACY" if o.get("legacy") and o["orderStatus"] == 0 else states[o["orderStatus"]], "totalAmount": o["amount"], "items": o["orderItemList"], "createdAt": o["orderTime"], "afterSalesStatus": o.get("afterSalesStatus"), "refundedAmount": o.get("refundedAmount")} for o in orders[:10]]
                state = {"message": question, "history": history,
                    "context": {"channel": "customer", "product_id": product_id, "orders": context_orders}}
                if recent_plans:
                    saved_plan = json.loads(recent_plans[0]["answer"]).get("businessPlan") or {}
                    if isinstance(saved_plan.get("request"), dict):
                        state["context"]["previousBundleRequest"] = saved_plan["request"]
                if hasattr(service, "run_customer_plan"):
                    identity = await commerce.request("GET", "/commerce/context")
                    tenant = identity["tenantId"]
                    if tenant != os.getenv("FUSION_TENANT", "demo"): raise HTTPException(403,"商城服务与租户绑定不一致")
                    result = await service.run_customer_plan(state, load_customer_catalog, tenant, session, run_id=resume_id, quote_fetcher=fetch_bundle_quote)
                else:
                    result = await customer_graph.ainvoke(state)
                meta = {"mode": result["mode"], "sources": result["sources"], "citations": result.get("citations", []), "trace":result.get("trace",[]), "runId":result.get("runId"), "planStatus":result.get("planStatus")}
                if result.get("businessPlan", {}).get("type") == "CONSUMER_BUNDLE":
                    plan = {**result["businessPlan"], "quoteToken": "qt_" + secrets.token_hex(16),
                            "revision": 1, "expiresAt": time.time() + 900, "confirmationStatus": "PENDING"}
                    plan['evidenceSnapshot']=evidence_snapshot([item['code'] for item in plan['items'] if item.get('code')],plan.get('request',{}).get('ownedGatewayModels',[]))
                    meta["businessPlan"] = await save_quote(session, plan)
                if result["products"]:
                    payload = {"type": "PRODUCT_SEARCH_RESULT", "intro": result["answer"], "products": [product_shape(p) for p in result["products"]], **meta}
                    biz = "product_search"
                else:
                    payload = {"type": "CHAT_RESULT", "answer": result["answer"], **meta}
                    biz = "chat"
                result_text = json.dumps(payload, ensure_ascii=False)
            with db() as connection:
                finish_message(connection,message_id,lease,tenant_scope,shop_scope,session,result_text,biz,2)
        except asyncio.CancelledError:
            try:
                with db() as connection:
                    finish_message(connection,message_id,lease,tenant_scope,shop_scope,session,'回答已中断，请重试','chat',3)
            except LostLease: pass
        except LostLease:
            pass
        except Exception as error:
            text = "客服暂时无法读取业务数据或生成回答，请稍后重试。"
            if getattr(error, "run_id", None):
                text = json.dumps({"type":"CHAT_RESULT","answer":text,"runId":error.run_id,"planStatus":"FAILED"},ensure_ascii=False)
            with db() as connection:
                try: finish_message(connection,message_id,lease,tenant_scope,shop_scope,session,text,'chat',3)
                except LostLease: pass
        finally:
            CURRENT_MESSAGE.reset(message_context)
            heartbeat.cancel()
            await asyncio.gather(heartbeat, return_exceptions=True)
            tasks.pop(message_id, None)

    @router.websocket("/ws/")
    async def websocket_endpoint(socket: WebSocket):
        origin = socket.headers.get("origin")
        try:
            session = await valid_session(socket.cookies.get(COOKIE))
        except HTTPException:
            await socket.close(code=1013)
            return
        if not session or (origin and origin not in ORIGINS):
            await socket.close(code=1008)
            return
        await socket.accept()
        sockets.setdefault(session, set()).add(socket)
        with db() as connection:
            sequence = connection.execute("SELECT COALESCE(MAX(sequence),0) FROM message_events WHERE tenant_id=? AND shop_id=? AND session_id=?",(tenant_scope,shop_scope,session)).fetchone()[0]
        if socket.query_params.get('afterSequence') is not None:
            try: sequence=max(0,int(socket.query_params['afterSequence']))
            except ValueError:
                await socket.close(code=1008)
                sockets.get(session,set()).discard(socket)
                return
        async def deliver_events():
            nonlocal sequence
            while True:
                with db() as connection:
                    rows = connection.execute("SELECT sequence,payload FROM message_events WHERE tenant_id=? AND shop_id=? AND session_id=? AND sequence>? ORDER BY sequence LIMIT 100",(tenant_scope,shop_scope,session,sequence)).fetchall()
                for row in rows:
                    await socket.send_json({**json.loads(row['payload']), 'eventSequence':row['sequence']})
                    sequence = row['sequence']
                await asyncio.sleep(0.2 if rows else 0.5)
        delivery = asyncio.create_task(deliver_events())
        try:
            while True:
                text = await socket.receive_text()
                if text == "ping":
                    await socket.send_text("pong")
        except WebSocketDisconnect:
            pass
        finally:
            delivery.cancel()
            await asyncio.gather(delivery, return_exceptions=True)
            sockets.get(session, set()).discard(socket)

    @router.api_route("/api/{path:path}", methods=["GET", "POST"])
    async def dispatch(path: str, request: Request, response: Response):
        response.headers.update(PRIVATE_HEADERS)
        if request.headers.get("origin") and request.headers["origin"] not in ORIGINS:
            return JSONResponse({"code": 403, "info": "请求来源不受支持", "data": None}, status_code=403, headers=PRIVATE_HEADERS)
        try:
            params = dict(request.query_params)
            if request.method == "POST":
                if "application/json" in request.headers.get("content-type", ""):
                    params.update(await request.json())
                else:
                    params.update(dict(await request.form()))
            if path in ("account/autoLogin", "account/login"):
                session = await valid_session(request.cookies.get(COOKIE)) or await shared_store.create_session()
                response = JSONResponse(ok(user_shape(session)), headers=PRIVATE_HEADERS)
                response.set_cookie(COOKIE, session, httponly=True, samesite="lax", max_age=SESSION_TTL, path="/")
                return response
            if path == "seckill/listActivities":
                # Anonymous and identical for every visitor. Personal participation has its own endpoint.
                response = JSONResponse(ok(await public_campaigns()))
                response.headers["Cache-Control"] = "public, max-age=2" if request.method == "GET" else "private, no-store"
                return response
            session = await current_session(request)
            if path == "account/logout":
                await shared_store.logout(session)
                response = JSONResponse(ok(), headers=PRIVATE_HEADERS)
                response.delete_cookie(COOKIE, path="/")
                return response
            if path == "account/getUserInfo":
                return ok(user_shape(session))
            if path == "product/loadCategory":
                catalog = await public_catalog()
                categories = list(dict.fromkeys(product_shape(p)["categoryId"] for p in catalog))
                names = {"智能照明": "lighting", "智能传感器": "sensors", "智能网关": "gateway", "智能安防": "security"}
                return ok([{"categoryId": name, "categoryName": name, "pCategoryId": "0", "children": [], "pic": "/media/demo/categories/"+names.get(name,"lighting")+".svg"} for name in categories])
            if path in ("product/loadCommendProduct", "product/loadProduct", "product/search", "product/getProduct", "search/loadRecommendProducts"):
                catalog = await public_catalog()
                if path == "product/getProduct":
                    product = next((p for p in catalog if p["id"] == str(params.get("productId"))), None)
                    if not product:
                        raise HTTPException(404, "该商品不存在或已下架")
                    return ok({"productInfo": product_shape(product), "productPropertyList": [{"propertyId": "spec", "propertyName": "规格", "propertyValues": [{"propertyValueId": "default", "propertyValue": product["spec"]}]}],
                               "skuList": [{"skuId": product["id"], "propertyValueIds": "default", "propertyValueIdHash": "default", "price": product["price"], "stock": product["stock"]}]})
                keyword = str(params.get("keyword") or "").strip().lower()
                category = str(params.get("categoryId") or "")
                selected = [p for p in catalog if (not category or product_shape(p)["categoryId"] == category) and (not keyword or keyword in " ".join(str(p.get(k) or "") for k in ("name", "remark", "spec", "category")).lower())]
                for field, minimum in (("priceFrom", True), ("priceTo", False)):
                    value = params.get(field)
                    if value not in (None, ""):
                        bound = Decimal(str(value))
                        if not bound.is_finite() or bound < 0: raise HTTPException(422, "价格范围无效")
                        selected = [p for p in selected if p["price"] is not None and (Decimal(str(p["price"])) >= bound if minimum else Decimal(str(p["price"])) <= bound)]
                if params.get("sortField") == "price":
                    selected.sort(key=lambda p: (p["price"] is None, p["price"] or 0), reverse=params.get("sortType") == "desc")
                products = [product_shape(p) for p in selected]
                return ok(products if path in ("product/loadCommendProduct", "search/loadRecommendProducts") else page(products, params))
            if path in ("search/loadHotKeywords", "search/loadGuessKeywords"):
                return ok(["智能灯", "Zigbee", "传感器", "智能门锁"])
            if path.startswith("search/") and "Keyword" in path:
                word = str(params.get("keyword") or "")[:100]
                operation = "save" if path.endswith("saveKeyword") and word else "remove" if path.endswith("removeRecentKeyword") else "clear" if path.endswith("clearRecentKeywords") else "load"
                words = await shared_store.keywords(session, operation, word)
                return ok(words if path.endswith("loadRecentKeywords") else None)
            if path.startswith("productCart/"):
                catalog = {p["id"]: p for p in await public_catalog()}
                if path.endswith("add2Cart"):
                    pid = str(params.get("productId") or "")
                    product = catalog.get(pid)
                    if not product: raise HTTPException(404, "货品不存在或已下架")
                    if params.get("propertyValueIds") not in (None, "", "default"): raise HTTPException(422, "商品规格不存在")
                    delta = integer(params.get("buyCount", 1))
                    if delta == 0 or abs(delta) > 1000: raise HTTPException(422, "购买数量无效")
                    await shared_store.add_cart(session, pid, delta, product["stock"])
                    return ok()
                if path.endswith("deleteCart"):
                    await shared_store.delete_cart(session, str(params.get("cartId")))
                    return ok()
                rows = await shared_store.carts(session)
                entries = []
                for row in rows:
                    product = catalog.get(row["product_id"])
                    if not product: continue
                    entries.append({"cartId": row["id"], "productId": product["id"], "productName": product["name"], "productCover": product_shape(product)["cover"], "productOnSale": True, "propertyValueIds": "default", "propertyValueIdHash": "default", "propertyData": [{"propertyName": "规格", "propertyValue": product["spec"]}], "price": product["price"], "addPrice": product["price"], "stock": product["stock"], "buyCount": row["quantity"]})
                return ok(page(entries, params))
            if path == "userAddress/loadDataList":
                return ok(await shared_store.addresses(session))
            if path.startswith("userAddress/"):
                if request.method != "POST": raise HTTPException(405, "请使用POST修改收货地址")
                operation = path.removeprefix("userAddress/")
                address_id = str(params.get("addressId") or "")
                if operation == "addAddress":
                    address_id = secrets.token_hex(12)
                    await shared_store.addresses(session, "save", address_id, address_input(params, address_id))
                    return ok({"addressId": address_id})
                if not re.fullmatch(r"(?:[a-f0-9]{24}|demo-address)", address_id): raise HTTPException(422, "收货地址编号无效")
                if operation == "updateAddress":
                    await shared_store.addresses(session, "update", address_id, address_input(params, address_id))
                elif operation in ("delAddress", "updateDefault"):
                    await shared_store.addresses(session, "delete" if operation == "delAddress" else "default", address_id)
                else: raise HTTPException(404, "收货地址操作不存在")
                return ok()
            if path == "order/postOrder":
                if request.method!='POST':raise HTTPException(405,'请使用POST提交订单')
                if params.get("payMethod") != "demo": raise HTTPException(422, "当前仅接本地支付沙箱，不提供真实收款")
                shipping_address = await shared_store.shipping_address(session, str(params.get("addressId") or ""))
                lines = params.get("orderList")
                if isinstance(lines, str): lines = json.loads(lines)
                if not isinstance(lines, list) or not 1 <= len(lines) <= 30 or not all(isinstance(line, dict) for line in lines): raise HTTPException(422, "订单商品无效")
                request_key = str(params.get("clientRequestId") or secrets.token_hex(16))
                if not 1 <= len(request_key) <= 80: raise HTTPException(422, "下单请求标识无效")
                quantities = {}
                for line in lines:
                    pid = str(line.get("productId"))
                    count = integer(line.get("buyCount", 0))
                    if not 1 <= count <= 99: raise HTTPException(422, "单个商品数量须为1至99的整数")
                    quantities[pid] = quantities.get(pid, 0) + count
                    if line.get("propertyValueIds") not in (None, "", "default"): raise HTTPException(422, "商品规格不存在")
                # Java validates price, eligibility, stock and idempotency under
                # product locks. Replaying a successful request needs no stock.
                order = await commerce.create(session, request_key, quantities, shipping_address)
                invalidate_public_reads()
                if str(params.get("orderFrom")) == "0":
                    await shared_store.consume_cart(session, request_key, quantities)
                return ok({"orderId": order["orderId"], "payOrderId": order["orderId"], "demo": True})
            if path == "order/loadMyOrder":
                orders = await customer_orders(session)
                status = params.get("status")
                if status not in (None, ""): orders = [o for o in orders if o["orderStatus"] == int(status) and (int(status) not in (0, 1, 2) or active_order_workflow(o))]
                return ok(page(orders, params))
            if path in ("order/getMyOrderDetail", "order/getOrderInfo"):
                return ok(await customer_order(session, str(params.get("orderId") or params.get("payOrderId"))))
            if path == "order/cancelOrder":
                if request.method!='POST':raise HTTPException(405,'请使用POST取消订单')
                return ok(await cancel_customer_order(session, str(params.get("orderId"))))
            if path=='order/querySandboxPayment':
                if request.method!='POST':raise HTTPException(405,'请使用POST核对支付结果')
                order_id=str(params.get('orderId') or '')
                operation_id=str(params.get('operationId') or '')
                if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}',operation_id):raise HTTPException(422,'支付操作编号无效')
                await customer_order(session,order_id)
                result=await commerce.query_payment(session,order_id,operation_id)
                invalidate_public_reads()
                return ok(result)
            if path in ("order/sandboxPay", "order/receiveOrder"):
                if request.method!='POST':raise HTTPException(405,'请使用POST提交订单操作')
                order_id = str(params.get("orderId") or "")
                order = await customer_order(session, order_id)
                if order.get("legacy"): raise HTTPException(409, "历史演示订单未接库存，请重新下单体验支付沙箱")
                if path == "order/sandboxPay":
                    scenario = params.get("scenario", "success")
                    if scenario not in ("success", "failure",'timeout_after_success','delayed_success'): raise HTTPException(422, "支付沙箱场景无效")
                    payment_key = str(params.get("paymentRequestId") or "pay-" + order_id)
                    if not 1 <= len(payment_key) <= 80: raise HTTPException(422, "支付请求标识无效")
                    result = await commerce.action(session, order_id, "sandbox-pay", paymentRequestId=payment_key, scenario=scenario)
                    invalidate_public_reads()
                    return ok(result)
                return ok(await commerce.action(session, order_id, "receive"))
            if path == "seckill/myParticipation":
                from commerce_api import owner_id
                response = JSONResponse(ok(await commerce.request("GET", "/commerce/activities/participation",
                                                                  params={"ownerId": owner_id(session)})))
                response.headers["Cache-Control"] = "private, no-store"
                return response
            if path == "seckill/createOrder":
                from commerce_api import owner_id
                activity = str(params.get("activityId") or "")
                request_key = str(params.get("requestKey") or "")
                if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", activity) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", request_key):
                    raise HTTPException(422, "活动或请求编号无效")
                from commerce_api import store_order
                shipping_address = await shared_store.shipping_address(session, str(params.get("addressId") or ""))
                result = store_order(await commerce.request("POST", "/commerce/activities/" + activity + "/orders", body={"ownerId": owner_id(session), "requestKey": request_key, "shippingAddress": shipping_address}))
                invalidate_public_reads()
                return ok(result)
            if path == "seckill/submitOrder":
                if request.method != "POST": raise HTTPException(405, "请使用POST提交抢购")
                from commerce_api import owner_id
                activity = str(params.get("activityId") or "")
                request_key = str(params.get("requestKey") or "")
                if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", activity) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", request_key):
                    raise HTTPException(422, "活动或请求编号无效")
                # Acceptance is only a durable queue receipt. Never create a local order or claim stock here.
                shipping_address = await shared_store.shipping_address(session, str(params.get("addressId") or ""))
                return ok(await commerce.request("POST", "/commerce/activities/" + activity + "/checkout",
                                                 body={"ownerId": owner_id(session), "requestKey": request_key, "shippingAddress": shipping_address}))
            if path == "seckill/getCheckoutStatus":
                from commerce_api import owner_id
                job_id = str(params.get("jobId") or "")
                if not re.fullmatch(r"[a-f0-9]{32}", job_id): raise HTTPException(422, "排队记录编号无效")
                result = await commerce.request("GET", "/commerce/checkouts/" + job_id,
                                                params={"ownerId": owner_id(session)})
                if result.get("state") == "SUCCEEDED": invalidate_public_reads()
                return ok(result)
            if path == "order/getOrderCountInfo":
                orders = await customer_orders(session)
                orders = [o for o in orders if active_order_workflow(o)]
                return ok({"waitPay": sum(o["orderStatus"] == 0 for o in orders), "waitSend": sum(o["orderStatus"] == 1 for o in orders), "waitReceive": sum(o["orderStatus"] == 2 for o in orders), "waitComment": 0})
            if path in ("afterSales/apply", "afterSales/list", "afterSales/detail"):
                from commerce_api import owner_id
                if path == "afterSales/apply":
                    if request.method != "POST": raise HTTPException(405, "请使用POST申请售后")
                    order_id = str(params.get("orderId") or "")
                    order = await customer_order(session, order_id)
                    if order.get("legacy"): raise HTTPException(409, "历史演示单未接入售后流程")
                    reason, key = str(params.get("reason") or "").strip(), str(params.get("requestKey") or "")
                    if not isinstance(params.get("reason"), str) or not 1 <= len(reason) <= 200 or re.search(r"[\x00-\x1f\x7f]", reason): raise HTTPException(422, "请填写200字以内的售后原因")
                    if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", key): raise HTTPException(422, "售后请求编号无效")
                    application = {"ownerId": owner_id(session), "requestKey": key, "reason": reason}
                    kind = params.get("kind")
                    if kind is not None:
                        if kind not in ("UNSHIPPED_REFUND", "RETURN_REFUND"): raise HTTPException(422, "请选择未发货退款或退货退款")
                        application["kind"] = kind
                    if "items" in params:
                        lines = params["items"]
                        if not isinstance(lines, list) or not 1 <= len(lines) <= 30 or not all(isinstance(row, dict) for row in lines): raise HTTPException(422, "请选择售后商品和数量")
                        expected = {str(row["productId"]) for row in order["orderItemList"]}
                        requested = []
                        seen = set()
                        for row in lines:
                            pid, count = str(row.get("productId") or ""), integer(row.get("quantity", 0))
                            if pid not in expected or pid in seen or not 1 <= count <= 100000: raise HTTPException(422, "售后商品或数量无效")
                            seen.add(pid)
                            requested.append({"productId": pid, "quantity": count})
                        application["items"] = requested
                    result = await commerce.request("POST", "/commerce/orders/" + quote(order_id, safe="") + "/after-sales",
                                                    body=application)
                    invalidate_public_reads()
                    return ok(result)
                query = {"ownerId": owner_id(session)}
                if path == "afterSales/list":
                    query.update(pageNum=params.get("pageNo") or 1, pageSize=min(50, max(1, integer(params.get("pageSize") or 20))))
                    result = await commerce.request("GET", "/commerce/after-sales", params=query)
                    return ok({"list": result["rows"], "totalCount": result["total"], "pageNo": int(query["pageNum"]), "pageSize": query["pageSize"], "pageTotal": max(1, (int(result["total"])+query["pageSize"]-1)//query["pageSize"])})
                identity = str(params.get("afterSalesId") or "")
                if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", identity): raise HTTPException(422, "售后编号无效")
                return ok(await commerce.request("GET", "/commerce/after-sales/" + identity, params=query))
            if path == "agent/quoteDetail":
                return ok({"businessPlan": await read_quote(session, str(params.get("quoteToken") or ""))})
            if path == "agent/confirmQuote":
                if request.method != "POST": raise HTTPException(405, "请确认后再加购")
                return ok({"businessPlan": await confirm_bundle_quote(session, str(params.get("quoteToken") or ""), params.get("revision"))})
            if path == "agent/sendMessage":
                question = str(params.get("message") or "").strip()
                if not 1 <= len(question) <= 1000: raise HTTPException(422, "问题需在1000字以内")
                clean_question = model_question(question)
                if not clean_question: raise HTTPException(422, "请输入想咨询的问题")
                pid = str(params.get("consultProductId") or "")
                if pid:
                    await shared_store.context(session, pid, update=True)
                else:
                    pid = await shared_store.context(session)
                    if re.search(r"重新推荐|推荐其他|换一|其他商品|其他品类|取消咨询|结束咨询", clean_question) or ("推荐" in clean_question and not re.search(r"这款|这个|它|搭配", clean_question)):
                        pid = None
                        await shared_store.context(session, update=True)
                with db() as connection:
                    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                    message_id, lease = create_message(connection,tenant_scope,shop_scope,session,question,timestamp)
                task = asyncio.create_task(make_answer(session, message_id, clean_question, pid, lease))
                tasks[message_id] = task
                return ok({"messageId": message_id, "userMessage": question, "status": 1, "sendTime": timestamp})
            if path in ("agent/getRun", "agent/resumeRun"):
                from planner import inspect_run, owner_key
                run_id = str(params.get("runId") or "")
                identity = await commerce.request("GET", "/commerce/context")
                row = inspect_run(run_id,identity["tenantId"],owner_key(session))
                saved = json.loads(row["state"])
                if path.endswith("getRun"): return ok({"runId":run_id,"status":row["status"],"trace":saved.get("trace",[]),"version":row["version"]})
                if row["status"] not in ("FAILED","INTERRUPTED","CANCELLED"): raise HTTPException(409,"此任务无需恢复或仍在执行")
                with db() as connection:
                    question = str(saved.get("message") or "")
                    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                    message_id, lease = create_message(connection,tenant_scope,shop_scope,session,question,timestamp)
                tasks[message_id] = asyncio.create_task(make_answer(session,message_id,question,(saved.get("context") or {}).get("product_id"),lease,run_id))
                return ok({"messageId":message_id,"userMessage":question,"status":1,"sendTime":timestamp})
            if path == "agent/loadHistoryMessage":
                with db() as connection:
                    expire_messages(connection,tenant_scope,shop_scope)
                    rows = connection.execute("SELECT * FROM messages WHERE session_id=? AND (tenant_id=? OR tenant_id='') AND shop_id=? ORDER BY id DESC", (session,tenant_scope,shop_scope)).fetchall()
                    proposal_rows = connection.execute("SELECT * FROM proposals WHERE session_id=?", (session,)).fetchall()
                proposal_map = {p["token"]: p for p in proposal_rows}
                messages = []
                for row in rows:
                    text = row["answer"]
                    if row["biz_type"] == "cancel_order" and text.startswith("{"):
                        card = json.loads(text)
                        proposal = proposal_map.get(card.get("token"))
                        if proposal:
                            card["status"] = 3 if proposal["status"] == 0 and proposal["expires"] < time.time() else proposal["status"]
                            if card["status"] == 1: card["intro"] = "这笔订单已确认取消，关联的预留库存已释放；未发生真实扣款。"
                            elif card["status"] == 2: card["intro"] = "已撤销取消提案，订单保持原状态。"
                            text = json.dumps(card, ensure_ascii=False)
                    elif text.startswith("{"):
                        card = json.loads(text)
                        plan = card.get("businessPlan") or {}
                        if plan.get("quoteToken"):
                            try:
                                card["businessPlan"] = await read_quote(session, plan["quoteToken"])
                            except HTTPException as error:
                                if error.status_code != 404: raise
                                card["businessPlan"] = {**plan, "confirmationStatus": "EXPIRED"}
                            text = json.dumps(card, ensure_ascii=False)
                    messages.append({"messageId": row["id"], "userMessage": row["question"], "assistantMessage": text, "status": row["status"], "bizType": row["biz_type"], "sendTime": row["sent_at"]})
                return ok(page(messages, params))
            if path == "agent/messageStatus":
                mid=integer(params.get('messageId') or 0)
                with db() as connection:
                    expire_messages(connection,tenant_scope,shop_scope)
                    row=connection.execute("SELECT id,question,answer,status,biz_type,sent_at FROM messages WHERE id=? AND session_id=? AND (tenant_id=? OR tenant_id='') AND shop_id=?",(mid,session,tenant_scope,shop_scope)).fetchone()
                if not row: raise HTTPException(404,'消息不存在')
                return ok({'messageId':row['id'],'userMessage':row['question'],'assistantMessage':row['answer'],
                           'status':row['status'],'bizType':row['biz_type'],'sendTime':row['sent_at']})
            if path == "agent/cancelMessage":
                mid = int(params.get("messageId") or 0)
                with db() as connection:
                    changed = cancel_message(connection,tenant_scope,shop_scope,session,mid)
                if changed and mid in tasks: tasks[mid].cancel()
                return ok()
            if path in ("agent/clearProductConsult", "agent/pauseProductConsult"):
                await shared_store.context(session, update=True)
                return ok()
            if path == "agent/getProductConsultContext":
                pid = await shared_store.context(session)
                product = next((p for p in await public_catalog() if p["id"] == pid), None) if pid else None
                return ok({"productId": pid, "productName": product["name"], "active": True} if product else None)
            if path in ("agent/confirmAction", "agent/cancelAction"):
                token = str(params.get("actionToken") or "")
                async with proposal_lock:
                    with db() as connection:
                        proposal = connection.execute("SELECT * FROM proposals WHERE token=? AND session_id=?", (token, session)).fetchone()
                    if not proposal: raise HTTPException(404, "没有找到当前访客的操作提案")
                    if proposal["expires"] < time.time(): raise HTTPException(409, "提案已过期，请重新查询订单")
                    confirm = path.endswith("confirmAction")
                    status = 1 if confirm else 2
                    if proposal["status"] == 0:
                        # Idempotent Java cancellation commits first. If the local
                        # chat acknowledgement fails, retrying releases nothing twice.
                        if confirm: await cancel_customer_order(session, proposal["order_id"])
                        with db() as connection:
                            connection.execute("UPDATE proposals SET status=? WHERE token=? AND status=0", (status, token))
                    elif proposal["status"] != status: raise HTTPException(409, "该提案已经处理，不能再次执行")
                return ok({"actionType": "cancel_order", "success": confirm, "resultMessage": "订单已取消，关联的预留库存已释放；未发生真实扣款。" if confirm else "已保留订单，撤销取消提案。"})
            # Empty read collections are real: this demo has no coupons, reviews or notifications.
            if path in ("discountCoupon/loadUserCoupon", "discountCoupon/loadDiscountCoupon", "order/comment/loadComment", "order/comment/loadMyComment", "userNotification/loadNotification", "userFavorite/loadFavorite", "browseHistory/loadBrowse"):
                return ok(page([], params))
            if path == "order/comment/getProductCommentStats":
                return ok({"totalCount": 0, "goodCount": 0, "badCount": 0, "averageScore": None})
            if path in ("userNotification/getPopupNotification", "userMember/getLevelBadge"):
                return ok(None)
            if path == "userNotification/countUnread": return ok(0)
            if path == "userFavorite/isFavorite": return ok(False)
            raise HTTPException(501, "此功能未接入本地演示，请使用商品、购物车、演示订单或智能客服")
        except HTTPException as error:
            if error.status_code == 401:
                return JSONResponse({"code": 901, "info": error.detail, "data": None}, headers=PRIVATE_HEADERS)
            return JSONResponse({"code": error.status_code, "info": error.detail, "data": None},
                                status_code=503 if error.status_code == 503 else 200, headers=PRIVATE_HEADERS)
        except (ValueError, TypeError, KeyError, InvalidOperation, json.JSONDecodeError):
            return JSONResponse({"code": 422, "info": "请求格式不正确，请检查选中的商品、规格和数量", "data": None}, headers=PRIVATE_HEADERS)
        except httpx.HTTPError:
            token_cache["until"] = 0
            return JSONResponse({"code": 502, "info": "暂时无法读取业务服务，请稍后重试", "data": None}, headers=PRIVATE_HEADERS)

    return router
