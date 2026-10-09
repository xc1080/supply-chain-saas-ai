"""Local fusion demo: existing Java authority + Simlect ranking + LangGraph."""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Literal, TypedDict

import httpx
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from langgraph.graph import END, START, StateGraph

from retrieval import KNOWLEDGE, cosine, normalize_products, number, product_text, rank_products, resolve_query, retrieve_knowledge
from business_catalog import profile_for, manufacturer_source, compatibility_assessment, mentioned_gateways
from business_planning import public_restock_intent


JAVA_URL = os.getenv("FUSION_JAVA_URL", "http://127.0.0.1:8035").rstrip("/")
TIMEOUT = httpx.Timeout(25.0, connect=5.0)
VECTOR_CACHE: dict[str, list[float]] = {}
app = FastAPI(title="供应链 AI 融合 Demo", version="0.1.0")


class HistoryMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")
    role: Literal["user", "assistant"]
    content: str = Field(max_length=1000)


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    message: str = Field(min_length=1, max_length=1000)
    history: list[HistoryMessage] = Field(default_factory=list, max_length=6)


class ChatState(TypedDict, total=False):
    message: str
    history: list[dict]
    authorization: str
    catalog: list[dict]
    products: list[dict]
    sources: list[dict]
    trace: list[dict]
    mode: dict
    answer: str
    citations: list[str]
    context: dict


def credential(name: str, fallback: str) -> str:
    return os.getenv(name, "").strip() or os.getenv(fallback, "").strip()


def llm_key() -> str:
    return os.getenv("FUSION_LLM_KEY", "").strip() or os.getenv("AI_BAILIAN_API_KEY", "").strip() or os.getenv("DEEPSEEK_API_KEY", "").strip()


def llm_defaults() -> tuple[str, str]:
    if os.getenv("AI_BAILIAN_API_KEY", "").strip():
        return "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"
    return "https://api.deepseek.com", "deepseek-chat"


def embedding_key() -> str:
    return credential("FUSION_EMBEDDING_KEY", "AI_BAILIAN_API_KEY")


def safe_status(error: Exception) -> str:
    if isinstance(error, httpx.HTTPStatusError):
        return f"HTTP {error.response.status_code}"
    return type(error).__name__


async def java_rows(client: httpx.AsyncClient, path: str, authorization: str) -> list[dict]:
    rows = []
    page = 1
    while True:
        response = await client.get(JAVA_URL + path, params={"pageSize": 500, "pageNum": page}, headers={"Authorization": authorization})
        if response.status_code in (401, 403):
            raise HTTPException(response.status_code, "Java 登录状态失效" if response.status_code == 401 else "没有访问业务数据的权限")
        response.raise_for_status()
        body = response.json()
        code = body.get("code", 200)
        if code in (401, 403):
            raise HTTPException(code, "Java 登录状态失效" if code == 401 else "没有访问业务数据的权限")
        if code != 200:
            raise HTTPException(502, "Java 业务数据查询失败")
        batch = body.get("rows", [])
        if not isinstance(batch, list):
            raise HTTPException(502, "Java 返回的数据格式不正确")
        rows.extend(batch)
        total = body.get("total")
        if not batch or (isinstance(total, int) and len(rows) >= total) or (total is None and len(batch) < 500):
            return rows
        page += 1
        if page > 20:
            raise HTTPException(502, "当前演示的业务数据规模超过查询上限")


async def fetch_data(state: ChatState) -> dict:
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            products, inventory = await asyncio.gather(
                java_rows(client, "/baseDate/product/list", state["authorization"]),
                java_rows(client, "/inventory/inventoryItemInquiry/list", state["authorization"]),
            )
    except HTTPException:
        raise
    except (httpx.HTTPError, ValueError):
        raise HTTPException(502, "无法读取 Java 商品或库存接口，请检查后端服务") from None
    catalog = normalize_products(products, inventory)
    return {"catalog": catalog, "trace": [{"step": "fetch_data", "status": "ok", "detail": f"通过当前登录权限读取 {len(catalog)} 个正常货品；库存按 planQuantity 汇总。"}]}


def redact_query(query: str) -> str:
    # The demo has no need for contact information in model requests.
    query = re.sub(r"\b1[3-9]\d{9}\b", "[已移除电话号码]", query)
    query = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", "[已移除邮箱]", query)
    return query


async def embed_documents(texts: list[str]) -> list[list[float]]:
    keys = [hashlib.sha256(text.encode()).hexdigest() for text in texts]
    missing_keys = list(dict.fromkeys(key for key in keys if key not in VECTOR_CACHE))
    if missing_keys:
        key_to_text = dict(zip(keys, texts))
        endpoint = os.getenv("FUSION_EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1").rstrip("/") + "/embeddings"
        # Batch only public catalog attributes and the bundled demo knowledge.
        for index in range(0, len(missing_keys), 10):
            batch_keys = missing_keys[index:index + 10]
            async with httpx.AsyncClient(timeout=TIMEOUT) as client:
                response = await client.post(endpoint, headers={"Authorization": f"Bearer {embedding_key()}"}, json={"model": os.getenv("FUSION_EMBEDDING_MODEL", "text-embedding-v4"), "input": [key_to_text[key] for key in batch_keys], "dimensions": int(os.getenv("FUSION_EMBEDDING_DIMENSIONS", "256")), "encoding_format": "float"})
                response.raise_for_status()
                data = response.json().get("data", [])
            if len(data) != len(batch_keys):
                raise ValueError("Embedding response count mismatch")
            ordered = sorted(data, key=lambda item: item["index"])
            for key, item in zip(batch_keys, ordered):
                vector = item.get("embedding")
                if not isinstance(vector, list) or not vector or not all(isinstance(v, (int, float)) for v in vector):
                    raise ValueError("Invalid embedding response")
                VECTOR_CACHE[key] = vector
        if len(VECTOR_CACHE) > 5000:
            current = {key: VECTOR_CACHE[key] for key in keys}
            VECTOR_CACHE.clear()
            VECTOR_CACHE.update(current)
    return [VECTOR_CACHE[key] for key in keys]


async def retrieve(state: ChatState) -> dict:
    query, constraints, inherited = resolve_query(state["message"], state.get("history", []))
    vectors = None
    knowledge_vectors = None
    mode = "local"
    trace = list(state["trace"])
    if inherited:
        trace.append({"step": "context", "status": "ok", "detail": "跟进问题继承最近用户目标与未替换的协议/预算；当轮条件优先。"})
    if embedding_key():
        try:
            # Only eligible catalog fields go to the embedding provider.
            texts = [redact_query(query)] + [product_text(p) for p in state["catalog"]] + [s["title"] + s["content"] for s in KNOWLEDGE]
            embeddings = await embed_documents(texts)
            vectors = {p["id"]: cosine(embeddings[0], vector) for p, vector in zip(state["catalog"], embeddings[1:])}
            offset = 1 + len(state["catalog"])
            knowledge_vectors = {source["id"]: cosine(embeddings[0], embeddings[offset + index]) for index, source in enumerate(KNOWLEDGE)}
            mode = "hybrid"
            trace.append({"step": "embedding", "status": "ok", "detail": "商品和本地知识库使用真实 embedding；文档向量缓存在当前进程内。"})
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as error:
            trace.append({"step": "embedding", "status": "fallback", "detail": f"Embedding 不可用（{safe_status(error)}），使用本地字符向量。"})
    else:
        trace.append({"step": "embedding", "status": "fallback", "detail": "未配置 embedding，使用本地字符向量；这不是语义 embedding。"})
    # A product-detail consultation keeps its server-validated product context.
    # Explicit searches can clear product_id at the caller, as Simlect does.
    product_id = str((state.get("context") or {}).get("product_id") or "")
    service_query = any(word in state["message"] for word in ("订单", "物流", "发货", "收货", "付款", "支付", "单号", "退款", "退换", "售后", "出库", "入库", "审批")) and not any(word in state["message"] for word in ("推荐", "找商品", "换一款", "类似"))
    products = [] if service_query else ([p for p in state["catalog"] if p["id"] == product_id] if product_id else rank_products(query, state["catalog"], vectors, constraints=constraints))
    sources = retrieve_knowledge(query, knowledge_vectors)
    if products:
        for source_id in ("DEMO-STOCK", "DEMO-PRICE"):
            source = next(s for s in KNOWLEDGE if s["id"] == source_id)
            if all(s["id"] != source_id for s in sources):
                sources.append(source)
    for product in products:
        price = "未设置" if product["price"] is None else f"{product['price']:g} 元"
        sources.append({"id": "P-" + product["id"], "title": product["name"] + "（本次商品与库存查询）", "content": f"货品编号：{product['code']}；规格：{product['spec']}；参考售价：{price}；{product.get('stock_kind', '账面库存')}：{product['stock']:g}；说明：{product['remark']}"})
        evidence = manufacturer_source(product)
        if evidence:
            sources.append(evidence)
    trace.append({"step": "retrieve", "status": "ok", "detail": f"硬过滤预算、协议、网关和库存后返回 {len(products)} 个匹配货品；采用 Simlect 原版 RRF 合并排名。"})
    return {"products": products, "sources": sources, "trace": trace, "mode": {"llm": "local", "retrieval": mode, "model": None}}


def server_orders(state: ChatState) -> list[dict]:
    """Only the customer facade may provide this private, owner-scoped context."""
    query = state["message"]
    order_words = ("订单", "物流", "发货", "付款", "支付", "退款", "售后", "收货", "单号", "这单", "那一单", "上一单", "买了什么")
    prior_query = next((item["content"] for item in reversed(state.get("history", [])) if item.get("role") == "user"), "")
    follows_order = bool(re.search(r"^(?:那|这|刚才|上面|多少|包含|有哪些)", query)) and any(word in prior_query for word in order_words)
    if not any(word in query for word in order_words) and not follows_order:
        return []
    orders = (state.get("context") or {}).get("orders") or []
    if not isinstance(orders, list):
        return []
    result = []
    for order in orders[:20]:
        if not isinstance(order, dict):
            continue
        identifier = str(order.get("id") or order.get("orderNo") or "")[:80]
        if not identifier:
            continue
        items = order.get("items") or []
        safe_items = [{"name": redact_query(str(item.get("productName") or "未提供商品名")[:260]), "spec": redact_query(str(item.get("propertyInfo") or "")[:260]), "quantity": number(item.get("quantity", item.get("buyCount")))} for item in items[:30] if isinstance(item, dict)] if isinstance(items, list) else []
        quantities = [item["quantity"] for item in safe_items]
        result.append({"source": "O-" + identifier, "order_no": str(order.get("orderNo") or identifier), "status": str(order.get("status") or "UNKNOWN"), "after_sales_status": str(order.get("afterSalesStatus") or ""), "refunded_amount": number(order.get("refundedAmount")), "total": number(order.get("totalAmount")), "quantity": sum(quantities) if quantities and all(q is not None for q in quantities) else None, "items": safe_items})
    return result


ORDER_STATE_NAMES = {"UNPAID": "待支付（本地沙箱，已预留库存）", "PAID": "沙箱支付成功、待发货（未真实收款）",
                     "SHIPPED": "已完成演示发货与库存出库（未寄送实物）", "RECEIVED": "已确认演示收货",
                     "CANCELED": "已取消（未真实收款，关联预留已释放）", "LEGACY": "历史演示单（未接库存和支付）"}


def order_status_text(order: dict) -> str:
    after_sales = {"REQUESTED": "售后申请待商家审核", "APPROVED": "售后审核通过，等待沙箱退款",
                   "AWAITING_RETURN": "售后审核通过，等待商家验收退货", "RETURN_RECEIVED": "退货已验收返库，等待沙箱退款",
                   "REFUNDED": "本地沙箱退款完成（未发生真实收付款）", "REJECTED": "售后申请已被商家拒绝"}
    state = order.get("after_sales_status")
    if state in after_sales:
        return after_sales[state]
    return ORDER_STATE_NAMES.get(order["status"], "状态需核对")


def answer_sources(state: ChatState) -> list[dict]:
    sources = list(state.get("sources", []))
    for order in server_orders(state):
        if not any(source["id"] == order["source"] for source in sources):
            status = order_status_text(order)
            sources.append({"id": order["source"], "title": "当前顾客的订单与库存业务记录", "content": f"订单号：{order['order_no']}；状态：{status}。支付仅为本地沙箱，无真实收款；演示物流不寄送实物。"})
    return sources


def fact_slots(state: ChatState) -> dict[str, str]:
    slots = {}
    query = state["message"]
    wants_price = any(word in query for word in ("多少钱", "售价", "价格", "贵", "便宜", "费用", "差价"))
    wants_stock = any(word in query for word in ("库存", "现货", "还有多少", "剩余", "有货"))
    for product in state.get("products", []):
        source = "P-" + product["id"]
        if wants_price and product.get("price") is not None:
            slots["{{price:" + source + "}}"] = f"{product['price']:g} 元"
        if wants_stock:
            slots["{{stock:" + source + "}}"] = f"{product['stock']:g}"
    _, constraints, _ = resolve_query(state["message"], state.get("history", []))
    maximum, minimum = constraints["max_price"], constraints["min_price"]
    if maximum is not None and minimum is not None:
        slots["{{budget}}"] = f"{minimum:g} 至 {maximum:g} 元"
    elif maximum is not None:
        slots["{{budget}}"] = f"{maximum:g} 元以内"
    elif minimum is not None:
        slots["{{budget}}"] = f"{minimum:g} 元起"
    for order in server_orders(state):
        source = order["source"]
        slots["{{order_no:" + source + "}}"] = order["order_no"]
        slots["{{order_status:" + source + "}}"] = order_status_text(order)
        if order["after_sales_status"] == "REFUNDED" and order["refunded_amount"] is not None:
            slots["{{order_refunded:" + source + "}}"] = f"{order['refunded_amount']:g} 元"
        if order["total"] is not None:
            slots["{{order_total:" + source + "}}"] = f"{order['total']:g} 元"
        if order["quantity"] is not None:
            slots["{{order_quantity:" + source + "}}"] = f"{order['quantity']:g}"
        for index, item in enumerate(order["items"]):
            if item["quantity"] is not None:
                slots["{{order_item_quantity:" + source + ":" + str(index) + "}}"] = f"{item['quantity']:g}"
    return slots


def deterministic_answer(state: ChatState) -> str:
    """A short, honest fallback, never a second answer appended to model prose."""
    query = state["message"].lower()
    products = state.get("products", [])
    if public_restock_intent(query):
        return "目前没有可向顾客确认的补货或到货日期。商品卡可查看本次查询的可售库存；具体到货时间需要商家核实，不能仅凭库存推算。"
    if any(word in query for word in ("退款", "退换", "质保", "售后")):
        orders = server_orders(state)
        if orders:
            order = orders[0]
            if order["after_sales_status"]:
                return f"本次查询的订单 {order['order_no']}：{order_status_text(order)}。可在订单详情查看售后进度；支付和退款均为本地沙箱。"
        return "可以在订单详情选择商品和数量申请售后，由商家审核。未发货部分退款后释放预留；已发货部分先退货验收，再执行沙箱退款。完好商品恢复可售，待质检或损坏商品仍不可售。助手只查询和解释，不能代办；具体退换条件仍需商家核对。"
    if any(word in query for word in ("下单", "采购", "入库", "出库", "扣库存", "删除", "修改", "审批")) or ("支付" in query and not server_orders(state)):
        return "不能凭推荐结果直接办理。先核对商品与账面库存，再在页面或业务系统中创建相应单据并按权限处理；缺货时需要先补货。助手只查询和推荐，不会更改业务数据。"
    if server_orders(state) or "订单" in query:
        orders = server_orders(state)
        if orders:
            order = orders[0]
            parts = []
            if any(word in query for word in ("商品", "包含", "买了什么", "哪些", "几件")):
                details = [f"{item['name']}（{item['spec'] or '未提供规格'}）" + (f"，数量 {item['quantity']:g}" if item["quantity"] is not None else "") for item in order["items"]]
                parts.append("包含：" + "；".join(details) + "。" if details else "商品明细尚未提供，请在我的订单中核对详情。")
            status = order_status_text(order)
            parts.append(f"当前状态为{status}。")
            if any(word in query for word in ("金额", "总额", "合计", "多少钱", "价格")):
                parts.append(f"订单总额为 {order['total']:g} 元。" if order["total"] is not None else "订单金额暂未提供，请在我的订单中核对。")
            return f"本次查询的订单 {order['order_no']} " + "".join(parts) + "可在我的订单中查看详情；沙箱流程不扣真钱、不寄送实物。"
        return "还没有可核实的订单信息。请在我的订单中核对对应订单，或提供订单号；我不能据此判断支付、退款或物流状态。"
    if products:
        if any(word in query for word in ("库存", "还有多少", "剩余", "多少钱", "价格", "售价")):
            details = []
            for product in products:
                if any(word in query for word in ("多少钱", "价格", "售价")):
                    price = "尚未设置参考售价" if product["price"] is None else f"参考售价为 {product['price']:g} 元"
                    details.append(f"{product['name']}{price}。")
                if any(word in query for word in ("库存", "还有多少", "剩余")):
                    details.append(f"{product['name']}本次查询的{product.get('stock_kind', '账面库存')}为 {product['stock']:g}，下单时由业务服务再次校验。")
            return "\n".join(details)
        if any(word in query for word in ("兼容", "网关", "适配", "配合", "接入")):
            checks = [(product, compatibility_assessment(product, state["message"])) for product in products]
            verified = [(product, check) for product, check in checks if check["status"] == "verified_pair"]
            if verified:
                return "；".join(product["name"] + "与 " + "/".join(check["matchedGatewayModels"]) + " 的组合已在收录的厂商资料中列明" for product, check in verified) + "。请核对销售地区、设备版本及安装条件；商品卡价格和库存来自本次业务查询。"
            if any(check["status"] == "unknown_pair" for _, check in checks):
                return "收录的厂商资料尚未证实这些商品与所提网关型号的组合，不能仅凭协议名称确认兼容。可以先查看商品资料中已核验的网关型号，或选择明确无需额外网关的商品。"
        protocol_texts = [(product.get("name", "") + " " + product.get("spec", "")).lower() for product in products]
        has_zigbee = any("zigbee" in text for text in protocol_texts)
        has_wifi = any("wifi" in text or "wi-fi" in text for text in protocol_texts)
        if has_zigbee and has_wifi:
            return "这些结果符合本次筛选条件。已有兼容 Zigbee 网关时可以考虑 Zigbee 型号；希望省去额外网关，应核对标明直连的 Wi-Fi 型号。商品卡展示了本次查询的售价与库存。你更偏向日常照明，还是阅读使用？"
        if has_zigbee:
            return "本次找到符合条件的 Zigbee 商品，可以查看下方商品卡。选定前请核对已有网关是否兼容，规格与数量以本次查询为准。"
        return "本次找到符合条件的商品，可以查看下方商品卡。选定前请核对使用场景和设备兼容性，售价与库存以本次查询为准。"
    if any(word in query for word in ("推荐", "商品", "找", "库存", "灯", "门锁", "窗帘", "传感器", "手机", "电脑")):
        return "当前商品中没有找到满足本次条件的结果。可以调整预算或协议条件，或补充需要的商品类型；我不会用不相关商品凑结果。"
    if any(word in query for word in ("zigbee", "wifi", "wi-fi", "matter", "网关", "兼容")):
        return "需要结合商品协议、说明与已有网关核对兼容性。Zigbee 通常需要兼容网关；Wi-Fi 型号是否可直连应以商品说明为准。你具体在咨询哪款商品？"
    return "可以告诉我想选的智能家居商品、使用场景与预算，或直接询问商品规格、库存和业务流程。"


def parse_model_answer(raw: str, state: ChatState, sources: list[dict]) -> tuple[str, list[str]]:
    """Validate machine references, then resolve facts without trusting model numbers."""
    decoded = json.loads(raw)
    if not isinstance(decoded, dict) or set(decoded) != {"answer", "citations"}:
        raise ValueError("Invalid answer object")
    candidate, cited = decoded["answer"], decoded["citations"]
    if not isinstance(candidate, str) or not candidate.strip() or len(candidate) > 5000:
        raise ValueError("Invalid answer text")
    known_ids = {source["id"] for source in sources}
    if not isinstance(cited, list) or any(not isinstance(source_id, str) or source_id not in known_ids for source_id in cited):
        raise ValueError("Unknown answer citation")
    if state.get("products") and not cited:
        raise ValueError("Product answer is missing evidence")
    candidate = candidate.strip()
    # Keep references machine-readable; the UI reveals them in a separate fold.
    for source_id in known_ids:
        candidate = candidate.replace("[" + source_id + "]", "")
    for product in state.get("products", []):
        source_id = "P-" + product["id"]
        candidate = re.sub(r"(?<![A-Za-z0-9_{])" + re.escape(source_id) + r"(?![A-Za-z0-9_}\-])", lambda _match: product["name"], candidate)
    # Accept an exact, explicitly attributed order quantity, then resolve it
    # through the same business slot. Ambiguous orders and wrong numbers fail.
    order_items = {}
    for order in server_orders(state):
        if order["source"] in cited:
            for index, item in enumerate(order["items"]):
                if item["quantity"] is not None:
                    order_items.setdefault(item["name"], []).append((item, "{{order_item_quantity:" + order["source"] + ":" + str(index) + "}}"))
    for name, evidence in order_items.items():
        if len({item["quantity"] for item, _ in evidence}) != 1:
            continue
        item, slot = evidence[0]
        name_pattern = r"\s*".join(re.escape(char) for char in name)
        amount = re.escape(f"{item['quantity']:g}") + r"(?:\.0+)?(?![\d.])"
        candidate = re.sub(r"(?P<lead>(?:包含|购买了?|买了?|订购了?)\s*)" + amount + r"(?P<tail>\s*(?:件|台|套)\s*" + name_pattern + r")", lambda match: match["lead"] + slot + match["tail"], candidate)
        optional_spec = r"(?:\s*[（(]\s*" + re.escape(item["spec"]) + r"\s*[）)])?" if item["spec"] else ""
        candidate = re.sub(r"(?P<lead>" + name_pattern + optional_spec + r"\s*[，,:：]?\s*数量\s*(?:为|是)?\s*)" + amount, lambda match: match["lead"] + slot, candidate)
    slots = fact_slots(state)
    used_slots = re.findall(r"\{\{[^{}]+\}\}", candidate)
    if any(slot not in slots for slot in used_slots):
        raise ValueError("Unknown fact slot")
    # A fact slot for one product cannot be used in a sentence naming another.
    for sentence in re.split(r"[。！？\n]", candidate):
        for product in state.get("products", []):
            source_id = "P-" + product["id"]
            for kind in ("price", "stock"):
                slot = "{{" + kind + ":" + source_id + "}}"
                if slot in sentence and product["name"] not in sentence:
                    if len(state.get("products", [])) > 1 or any(other["name"] in sentence for other in state.get("products", []) if other is not product):
                        raise ValueError("Fact slot is attributed to another product")
    without_facts = candidate
    for slot in used_slots:
        without_facts = without_facts.replace(slot, "")
    _, constraints, _ = resolve_query(state["message"], state.get("history", []))
    # Repeating the user's validated budget is a query condition, not a live
    # price/stock assertion. Only this explicit budget phrase is exempted.
    if constraints["max_price"] is not None:
        amount = f"{constraints['max_price']:g}"
        without_facts = re.sub(r"(?:预算(?:上限)?|最高预算)\s*(?:为|是|在|不超过|不高于)?\s*[¥￥]?\s*" + re.escape(amount) + r"(?:\.0+)?\s*(?:元|块)(?:以内|以下)?", "", without_facts)
        without_facts = re.sub(re.escape(amount) + r"(?:\.0+)?\s*(?:元|块)\s*(?:的)?(?:单件)?预算", "", without_facts)
        without_facts = re.sub(r"(?<![\d.])" + re.escape(amount) + r"(?:\.0+)?\s*(?:元|块)\s*(?:以内|以下)", "", without_facts)
    evidence_products = list(state.get("products", [])) + [item for order in server_orders(state) for item in order["items"]]
    for product in evidence_products:
        without_facts = without_facts.replace(product["name"], "")
        # Exact catalog specs are evidence, rather than invented live quantities.
        spec = str(product.get("spec") or "")
        if spec:
            without_facts = without_facts.replace(spec, "")
        for unit in re.findall(r"\d+(?:\.\d+)?\s*(?:W|w|V|v|mAh|GHz|MHz|mm|cm|kg|K|寸)", spec):
            without_facts = without_facts.replace(unit, "")
        profile = profile_for(str(product.get("code", "")))
        if profile:
            for model in [profile["model"], *profile.get("compatibleGatewayModels", [])]:
                without_facts = re.sub(r"(?<![A-Za-z0-9])" + re.escape(model) + r"(?![A-Za-z0-9])", "", without_facts, flags=re.I)
            # Permit sourced technical identifiers/units, never the bare number.
            # A manufacturer's 13A rating must not authorize a made-up 13 yuan price.
            technical_evidence = " ".join([str(profile.get("spec", "")), *profile.get("facts", [])])
            technical_pattern = r"(?<![A-Za-z0-9.])(?:CR\d+[A-Za-z]*|IP\d{2}|E\d{2}|\d+(?:\.\d+)?\s*(?:mAh|GHz|MHz|mm|cm|kg|W|V|A|K))(?![A-Za-z0-9])"
            for token in re.findall(technical_pattern, technical_evidence, re.I):
                without_facts = re.sub(r"(?<![A-Za-z0-9.])" + re.escape(token) + r"(?![A-Za-z0-9])", "", without_facts, flags=re.I)
    for gateway in mentioned_gateways(state["message"]):
        without_facts = re.sub(r"(?<![A-Za-z0-9])" + re.escape(gateway["model"]) + r"(?![A-Za-z0-9])", "", without_facts, flags=re.I)
    if re.search(r"\d|[零一二三四五六七八九十百千万两多]+(?:元|台|件|套|天|年|月|小时)|(?:库存|售价|价格|数量)[^。！？\n]{0,8}[零一二三四五六七八九十百千万两]", without_facts):
        raise ValueError("Unverified numeric statement")
    if re.search(r"(?:已经|已)(?:为您|为你|帮您|帮你)?(?:成功|完成)?(?:退款|下单|采购|入库|出库|扣库存|修改|删除|审批|支付)|(?:已为您|已帮你|已帮您)(?:完成|办理)", without_facts):
        raise ValueError("Unsupported write completion")
    if re.search(r"(?:我可以|我能|我会|我来|我帮|需要我帮|要我帮)[^。！？\n]{0,45}(?:查[^。！？\n]{0,12}兼容清单|查询厂商|确认[^。！？\n]{0,15}(?:是否适配|具体网关|兼容性))", without_facts):
        raise ValueError("Unsupported manufacturer lookup capability")
    # Prompt instructions are not authorization. Unknown combinations must not
    # become either positive or negative compatibility promises in model prose.
    for product in state.get("products", []):
        if not profile_for(str(product.get("code", ""))):
            continue
        check = compatibility_assessment(product, state["message"])
        for sentence in re.split(r"[。！？\n]", candidate):
            if not re.search(r"兼容|适配|(?:能|可|可以|能够)(?:直接)?(?:配对|接入|搭配|配合|一起用)", sentence):
                continue
            if re.search(r"未证实|未确认|不能确认|不能保证|无法确认|不能据此|尚无|不代表|需[^。]{0,12}(?:核对|确认)|可能|待确认|是否|不一定|无法判断", sentence):
                continue
            named_products = []
            for item in state.get("products", []):
                item_profile = profile_for(str(item.get("code", "")))
                model = (item_profile or {}).get("model", "")
                if item["name"] in sentence or (model and re.search(r"(?<![A-Za-z0-9])" + re.escape(model) + r"(?![A-Za-z0-9])", sentence, re.I)):
                    named_products.append(item["id"])
            if named_products and product["id"] not in named_products:
                continue
            if check["status"] != "verified_pair":
                raise ValueError("Unverified manufacturer compatibility conclusion")
            if check["sourceId"] not in cited:
                raise ValueError("Manufacturer compatibility citation is required")
    feature_evidence = " ".join(product_text(product) for product in state.get("products", []))
    for clause in re.split(r"[，,；;。！？\n]", without_facts):
        if re.search(r"是否|核对|未提供|未说明|不代表|不能保证|不自动", clause):
            continue
        if re.search(r"(?:支持|可|能)[^。]{0,15}(?:手机\s*App|远程控制|远程操作|场景联动)|即连即用|即装即用|无需配置", clause, re.I):
            if not any(feature in feature_evidence.lower() for feature in ("app", "远程", "场景联动", "即连即用", "即装即用", "无需配置")):
                raise ValueError("No evidence for App, remote control, automation or plug-and-play features; only describe provided specifications")
    if (state.get("context") or {}).get("channel") == "customer" and re.search(r"已支付|已付款|已发货|已送达|运输中|退款成功", without_facts):
        raise ValueError("No verified payment or shipping facts")
    if re.search(r"planQuantity|univalence|PRODUCT_SEARCH_RESULT|\b(?:verified_pair|unknown_pair|needs_gateway_model|gateway_not_required|manufacturer_reference_with_simulated_trade)\b|\[DEMO-|\[P-|<[^>]+>|```", candidate):
        raise ValueError("Internal or markup output")
    for slot in used_slots:
        candidate = candidate.replace(slot, slots[slot])
    if "{{" in candidate or "}}" in candidate:
        raise ValueError("Unresolved fact slot")
    return candidate.strip(), list(dict.fromkeys(cited))


async def answer(state: ChatState) -> dict:
    trace = list(state.get("trace", []))
    mode = dict(state.get("mode") or {"llm": "local", "retrieval": "local", "model": None})
    sources = answer_sources(state)
    fallback = deterministic_answer(state)
    if public_restock_intent(state["message"]) and (state.get("context") or {}).get("channel") != "workspace":
        return {"answer": fallback, "citations": [], "sources": sources,
                "mode": {**mode, "llm": "local", "model": None},
                "trace": trace + [{"step": "answer_validation", "status": "ok", "detail": "未提供已核实的公开到货日期，不读取商家供货资料"}]}
    if llm_key():
        try:
            prompt = (Path(__file__).parent / "prompts" / "assistant.txt").read_text(encoding="utf-8")
            slots = fact_slots(state)
            context = {
                "question": redact_query(state["message"]),
                "history": [{"role": item["role"], "content": redact_query(item["content"][:1000])} for item in state.get("history", [])[-6:]],
                "channel": (state.get("context") or {}).get("channel", "workspace"),
                "consulting_product": (state.get("context") or {}).get("product_id"),
                "compatibility_checks": state.get("compatibility", []),
                "products": [{"source": "P-" + product["id"], "name": product["name"], "spec": product.get("spec", ""), "remark": product.get("remark", ""), "price": "{{price:P-" + product["id"] + "}}" if "{{price:P-" + product["id"] + "}}" in slots else "已通过预算筛选，具体售价见商品卡", "stock": "{{stock:P-" + product["id"] + "}}" if "{{stock:P-" + product["id"] + "}}" in slots else "具体库存见商品卡", "stock_kind": product.get("stock_kind", "账面库存"), "has_stock": product.get("stock", 0) > 0} for product in state.get("products", [])],
                "orders": [{"source": order["source"], "order_no": "{{order_no:" + order["source"] + "}}", "status": "{{order_status:" + order["source"] + "}}", "status_meaning": order_status_text(order), "refunded_amount": "{{order_refunded:" + order["source"] + "}}" if "{{order_refunded:" + order["source"] + "}}" in slots else "未提供", "total": "{{order_total:" + order["source"] + "}}" if order["total"] is not None else "未提供", "quantity": "{{order_quantity:" + order["source"] + "}}" if order["quantity"] is not None else "未提供", "items": [{"name": item["name"], "spec": item["spec"], "quantity": "{{order_item_quantity:" + order["source"] + ":" + str(index) + "}}" if item["quantity"] is not None else "未提供"} for index, item in enumerate(order["items"])]} for order in server_orders(state)],
                "sources": [{**source, "content": source["content"].replace("planQuantity", "账面库存").replace("univalence", "参考售价")} for source in sources if not source["id"].startswith(("P-", "O-"))] + [{"id": source["id"], "title": source["title"]} for source in sources if source["id"].startswith(("P-", "O-"))],
                "allowed_fact_slots": list(slots),
            }
            payload = {"model": os.getenv("FUSION_LLM_MODEL", llm_defaults()[1]), "temperature": 0, "max_tokens": 1100, "response_format": {"type": "json_object"}, "messages": [{"role": "system", "content": prompt}, {"role": "user", "content": json.dumps(context, ensure_ascii=False)}]}
            endpoint = os.getenv("FUSION_LLM_BASE_URL", llm_defaults()[0]).rstrip("/") + "/chat/completions"
            async with httpx.AsyncClient(timeout=TIMEOUT) as client:
                for attempt in range(2):
                    response = await client.post(endpoint, headers={"Authorization": f"Bearer {llm_key()}"}, json=payload)
                    response.raise_for_status()
                    raw = response.json()["choices"][0]["message"]["content"]
                    try:
                        candidate, cited = parse_model_answer(raw, state, sources)
                        break
                    except ValueError as invalid:
                        if attempt:
                            raise
                        trace.append({"step": "answer_validation", "status": "repair", "detail": "首个回答未通过事实或格式校验，按具体校验结果请求模型修正一次。"})
                        payload = {**payload, "messages": [*payload["messages"], {"role": "assistant", "content": raw}, {"role": "user", "content": "上个回答未通过服务端校验：" + str(invalid) + "。请重新返回完整的 answer/citations JSON。只用当前资料和允许的数值占位符；不要自行写售价、库存、订单数量或数字序号，不补充资料外的规格、功能或查询能力。用必要的短段落答全最新问题，选品时不用重复商品卡的数值。"}]}
            mode.update({"llm": "online", "model": payload["model"]})
            trace.append({"step": "answer", "status": "ok", "detail": "真实大模型结合本次查询、相关资料与历史对话组织完整回答；数值由业务查询替换，依据单独展示。"})
            return {"answer": candidate, "citations": cited, "sources": sources, "trace": trace, "mode": mode}
        except (httpx.HTTPError, OSError, ValueError, KeyError, IndexError, TypeError) as error:
            trace.append({"step": "answer", "status": "fallback", "detail": f"模型调用或事实校验未通过（{safe_status(error)}），改用本次查询和本地规则简要回答。"})
    else:
        trace.append({"step": "answer", "status": "fallback", "detail": "未配置大模型，当前回答由本次查询与本地规则生成。"})
    mode.update({"llm": "local", "model": None})
    return {"answer": fallback, "citations": [source["id"] for source in sources], "sources": sources, "trace": trace, "mode": mode}


async def agent_identity(authorization):
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        response = await client.get(JAVA_URL + "/commerce/context", headers={"Authorization":authorization})
        data = response.json()
    if data.get("code") != 200: raise HTTPException(403,"当前登录无权访问租户助手")
    return data["data"]

async def workspace_catalog(state):
    result = await fetch_data(state)
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        response = await client.get(JAVA_URL + "/commerce/inventory",headers={"Authorization":state["authorization"]})
        data = response.json()
    if data.get("code") != 200: raise HTTPException(502,"无法核对可售库存")
    stock = {str(item["productId"]):item for item in data["data"]}
    result["catalog"] = [{**p,"stock":float(stock[p["id"]]["availableStock"]),"stock_kind":"可售库存"} for p in result["catalog"] if p["id"] in stock]
    return result

async def planning_authority(state, method, path, *, body=None):
    """Authenticated read-only calls; authority failure has no local fallback."""
    authorization = state.get("authorization")
    if not authorization:
        raise HTTPException(401, "请先登录业务系统")
    headers = {"Authorization": authorization}
    shop = (state.get("context") or {}).get("shopId")
    if shop:
        if not isinstance(shop, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", shop):
            raise HTTPException(422, "店铺标识不合法")
        headers["X-Shop-ID"] = shop
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            response = await client.request(method, JAVA_URL + path, headers=headers, json=body)
            response.raise_for_status()
            data = response.json()
        if data.get("code") in (401, 403, 404, 409, 422):
            raise HTTPException(data["code"], "当前业务规划查询未获授权或参数不满足条件")
        if data.get("code") != 200 or not isinstance(data.get("data"), dict):
            raise HTTPException(502, "业务规划数据不可用")
        return data["data"]
    except HTTPException:
        raise
    except (httpx.HTTPError, ValueError, TypeError):
        raise HTTPException(502, "无法读取业务规划数据；未生成虚构报价或库存") from None


async def consumer_quote(state, items, units):
    return await planning_authority(state, "POST", "/commerce/planning/quote", body={"items": items, "units": units})


async def merchant_replenishment(state):
    if (state.get("context") or {}).get("channel") != "workspace":
        raise HTTPException(403, "备货规划仅供已授权商家在工作台使用")
    return await planning_authority(state, "GET", "/commerce/planning/replenishment")


async def run_customer_plan(state, fetcher, tenant, owner, run_id=None, *, quote_fetcher=None):
    from planner import run_agent
    return await run_agent(sys.modules[__name__], state, tenant, owner, fetcher, run_id=run_id,
                           quote_fetcher=quote_fetcher)


def agent_response(result):
    return {key: result[key] for key in ("answer", "citations", "products", "sources", "trace", "mode", "runId", "planStatus", "businessPlan") if key in result}


builder = StateGraph(ChatState)
builder.add_node("fetch_data", fetch_data)
builder.add_node("retrieve", retrieve)
builder.add_node("answer", answer)
builder.add_edge(START, "fetch_data")
builder.add_edge("fetch_data", "retrieve")
builder.add_edge("retrieve", "answer")
builder.add_edge("answer", END)
graph = builder.compile()


@app.get("/health")
async def health():
    return {"status": "ok", "java_url": JAVA_URL, "llm_configured": bool(llm_key()), "embedding_configured": bool(embedding_key()), "flow": ["plan", "read_only_tools", "verify", "answer"], "retrieval": "hybrid" if embedding_key() else "local", "writes": False}


@app.post("/chat")
async def chat(request: ChatRequest, authorization: str | None = Header(None)):
    if not authorization or not re.fullmatch(r"Bearer\s+\S+", authorization, re.I):
        raise HTTPException(401, "请先登录业务系统")
    message = request.message.strip()
    if not message:
        raise HTTPException(422, "消息不能为空")
    from planner import run_agent, AgentExecutionError
    identity = await agent_identity(authorization)
    try:
        result = await run_agent(sys.modules[__name__], {"message":message,"history":[item.model_dump() for item in request.history],"authorization":authorization,"trace":[],"context":{"channel":"workspace"}}, identity["tenantId"], "workspace:"+str(identity["userId"]), workspace_catalog)
    except AgentExecutionError as error:
        raise HTTPException(503, {"message":"任务暂未完成，可重试已保存的执行记录", "runId":error.run_id}) from None
    return agent_response(result)


# Keep the original Simlect storefront contracts separate from the authenticated
# business assistant. Both consume the same server-side retrieval/answer flow.
import sys
from fastapi.staticfiles import StaticFiles
from store_api import build_store_router

app.include_router(build_store_router(sys.modules[__name__]))
from planner import recover
app.router.add_event_handler("startup", recover)
app.mount("/media/demo", StaticFiles(directory=Path(__file__).parent / "media"), name="demo-media")


@app.get("/agent/runs/{run_id}")
async def inspect_agent(run_id: str, authorization: str | None = Header(None)):
    if not authorization: raise HTTPException(401,"请先登录")
    from planner import inspect_run, owner_key
    identity = await agent_identity(authorization)
    row = inspect_run(run_id,identity["tenantId"],owner_key("workspace:"+str(identity["userId"])))
    saved = json.loads(row["state"])
    return {"runId":run_id,"status":row["status"],"version":row["version"],"trace":saved.get("trace",[])}

@app.post("/agent/runs/{run_id}/resume")
async def resume_agent(run_id: str, authorization: str | None = Header(None)):
    if not authorization: raise HTTPException(401,"请先登录")
    from planner import run_agent, inspect_run, owner_key, AgentExecutionError
    identity = await agent_identity(authorization)
    owner = "workspace:"+str(identity["userId"])
    row = inspect_run(run_id,identity["tenantId"],owner_key(owner))
    saved = json.loads(row["state"])
    try:
        result = await run_agent(sys.modules[__name__],{**saved,"authorization":authorization},identity["tenantId"],owner,workspace_catalog,run_id=run_id)
    except AgentExecutionError as error: raise HTTPException(503,{"message":"任务恢复未完成","runId":error.run_id}) from None
    return agent_response(result)
