"""Read-only product retrieval; prices and stock always come from Java."""

from __future__ import annotations

import math
import re
import sys
from collections import Counter
from pathlib import Path
from business_catalog import profile_for
from sku_catalog import property_data

# Reuse the upstream implementation rather than approximating its ranking.
SIMLECT_AGENT = Path(__file__).resolve().parent.parent / "Simlect-AI-Mall" / "Simlect-backend" / "Simlect-agent"
sys.path.insert(0, str(SIMLECT_AGENT))
from app.rag.rrf import rrf_merge  # noqa: E402


KNOWLEDGE = [
    {"id": "DEMO-SCOPE", "title": "Demo 的操作范围", "content": "【Demo 演示规则】助手只查询、推荐商品并解释知识库。下单、采购、出入库、退款等写操作必须由人员在业务系统创建单据并按权限和审批流程执行。助手不能自动扣库存或更改单据。"},
    {"id": "DEMO-PROTOCOL", "title": "智能家居协议与网关", "content": "【Demo 选型知识】Zigbee 设备通常需要兼容的网关。Wi-Fi 直连是某些设备的特性，是否无需网关以商品规格和说明为准。Matter 是互操作标准，不能单凭该词保证所有设备互通。购买前需要核对协议、网关、平台兼容性。"},
    {"id": "DEMO-STOCK", "title": "库存数据的来源", "content": "【Demo 演示规则】账面库存来自若依库存查询接口，按货品汇总。商城和多步助手使用业务服务核对的可售库存，已扣除订单预留和活动待抢配额。查询不预留商品，下单时仍需重新校验；待支付订单超时关闭后释放预留。"},
    {"id": "DEMO-PRICE", "title": "价格与预算", "content": "【Demo 演示规则】报价展示若依货品的参考售价 univalence，预算条件用于筛选。参考售价不代表已签约成交价，也不包含另行确认的安装、配送和服务费用。"},
    {"id": "DEMO-AFTERSALE", "title": "售后与采购流程", "content": "【Demo 演示规则，非真实商家承诺】退款、退换货和质保需要人员核对原始订单、合同、货品与售后条件，再进入相应业务审批。本知识库没有承诺具体退货期限或赔付金额。采购需要记录供应商、采购单、收货与入库凭证；助手只能提供流程说明。"},
]

TOPICS = {
    "灯": ("灯", "照明", "灯泡", "吸顶灯", "夜灯"),
    "锁": ("锁", "门锁", "指纹", "开门"),
    "窗帘": ("窗帘", "电机", "遮光"),
    "传感器": ("传感器", "感应", "温湿度", "人体"),
    "开关": ("开关", "插座", "面板"),
    "网关": ("网关", "中枢", "hub"),
    "手机": ("手机", "iphone"),
    "电脑": ("电脑", "笔记本"),
}
PROTOCOLS = {
    "zigbee": ("zigbee",), "wifi": ("wifi", "wi-fi", "wi fi", "无线直连"),
    "bluetooth": ("bluetooth", "蓝牙"), "matter": ("matter",), "thread": ("thread",),
    "sub-ghz": ("sub-ghz", "sub ghz", "subghz"),
}


def number(value, default=None):
    try:
        parsed = float(value)
        return parsed if math.isfinite(parsed) else default
    except (TypeError, ValueError):
        return default


def grams(text: str) -> Counter:
    text = re.sub(r"\s+", "", text.lower())
    return Counter(text[i:i + 2] for i in range(max(len(text) - 1, 0)))


def cosine(a, b) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    denominator = math.sqrt(sum(x * x for x in a)) * math.sqrt(sum(x * x for x in b))
    return sum(x * y for x, y in zip(a, b)) / denominator if denominator else 0.0


def lexical_similarity(query: str, text: str) -> float:
    a, b = grams(query), grams(text)
    denominator = math.sqrt(sum(v * v for v in a.values())) * math.sqrt(sum(v * v for v in b.values()))
    return sum(v * b.get(k, 0) for k, v in a.items()) / denominator if denominator else 0.0


def product_text(product: dict) -> str:
    # Only catalog attributes; no business customer/supplier objects.
    text = " ".join(str(product.get(k) or "") for k in ("name", "spec", "category", "remark"))
    if product.get("skuCatalog"):
        text += " " + str(product["skuCatalog"]["spuName"]) + " " + " ".join(
            item["propertyName"] + " " + item["propertyValue"] for item in property_data(product))
    profile = profile_for(str(product.get("code", "")))
    if profile:
        text += " " + " ".join([profile["brand"], profile["model"], *profile.get("facts", [])])
    return text


def product_protocols(product: dict) -> set[str]:
    profile = profile_for(str(product.get("code", "")))
    if profile:
        return set(profile.get("protocols", []))
    # A remark explaining a gateway must not turn Wi-Fi into Zigbee.
    text = (" ".join(str(product.get(k) or "") for k in ("name", "spec")) + " " +
            " ".join(item["propertyValue"] for item in property_data(product))).lower()
    text = re.sub(r"(?:无需|不用|不需要|无)[^，。；,;]{0,12}网关", "", text)
    return {key for key, aliases in PROTOCOLS.items() if any(alias in text for alias in aliases)}


def target_request(query: str) -> str:
    """Separate equipment the customer owns from the product being requested."""
    ownership = r"(?:我(?:现在|目前)?已经有|我(?:现在|目前)?有|我已有|已有|已经有|已配备|家里有|我家有)"
    target = query.lower()
    existing_context = re.match(ownership + r"[^，。；;！？]*?(?:[,，;；。！？]|适合|想配|还想|想买|想选|推荐)", target)
    if existing_context:
        target = target[existing_context.end():]
    elif "支持哪些" in target:
        target = target.split("支持哪些", 1)[1]
    else:
        gateway_question = re.match(r"[a-z0-9 /-]+\s*(?:网关|中枢|hub)\s*(?:能|可以)?(?:配|连接|接入)(?:哪些|什么)", target)
        if gateway_question:
            target = target[gateway_question.end():]
    # A suffix such as “推荐 Zigbee 传感器，我已有 Wi-Fi 网关” must
    # preserve the requested Zigbee protocol while removing the owned Wi-Fi hub.
    target = re.sub(ownership + r"[^，。；;！？]{0,60}?网关", "", target)
    return target


def query_constraints(query: str) -> dict:
    lower = query.lower()
    minimum = None
    maximum = None
    budget = re.search(r"(?:预算|不超过|不高于|最高|最多|小于|低于)\s*[¥￥]?\s*(\d+(?:\.\d+)?)", query)
    under = re.search(r"(\d+(?:\.\d+)?)\s*(?:元|块)?\s*(?:以内|以下|内)", query)
    above = re.search(r"(\d+(?:\.\d+)?)\s*(?:元|块)?\s*(?:以上|起)", query)
    price_range = re.search(r"(\d+(?:\.\d+)?)\s*(?:-|到|至|~)\s*(\d+(?:\.\d+)?)\s*(?:元|块)", query)
    if budget or under:
        maximum = float((budget or under).group(1))
    if above:
        minimum = float(above.group(1))
    if price_range:
        minimum, maximum = map(float, price_range.groups())
    no_gateway_expression = r"(?:无需|不要|不用|不需要|没有|还没有|还没|没装|未配备|未购买|没买|暂无|无)(?:\s*(?:兼容的?|zigbee|wi-?fi)\s*)?网关"
    no_gateway = bool(re.search(no_gateway_expression, lower))
    target = target_request(query)
    protocol_context = re.sub(no_gateway_expression, "", target)
    # A phrase such as “不要 Zigbee” means exclusion, not requirement.
    required, excluded = [], []
    for protocol, aliases in PROTOCOLS.items():
        present = next((alias for alias in aliases if alias in protocol_context), None)
        if present:
            if re.search(r"(?:不要|不支持|排除|不用)\s*" + re.escape(present), protocol_context):
                excluded.append(protocol)
            else:
                required.append(protocol)
    topics = [key for key, aliases in TOPICS.items() if any(alias in target for alias in aliases)]
    if no_gateway:
        topics = [topic for topic in topics if topic != "网关"]
    inventory = any(x in lower for x in ("库存", "缺货", "没货", "还有多少")) and not any(x in lower for x in ("推荐", "买", "选"))
    cleaned = re.sub(r"(?:帮我|请|推荐|找|想要|有没有|需要|预算|以内|以下|以上|库存|多少|查询|元|\d+|[，。！？、\s])", "", lower)
    generic = not topics and not required and not cleaned
    if any(x in lower for x in ("智能家居", "全屋", "全部商品", "所有商品")) and not topics:
        generic = True
    return {"min_price": minimum, "max_price": maximum, "protocols": required, "excluded_protocols": excluded, "no_gateway": no_gateway, "topics": topics, "inventory": inventory, "generic": generic}


def resolve_query(message: str, history: list[dict]) -> tuple[str, dict, bool]:
    current = query_constraints(message)
    followup = bool(re.search(r"(?:^那|^这些|^这个|^这款|^换成|^更|^便宜|刚才|上面|以内|以下|预算)", message))
    if not followup or not history:
        return message, current, False
    previous = next((query_constraints(item["content"]) for item in reversed(history) if item.get("role") == "user" and query_constraints(item["content"])["topics"]), None)
    if previous is None:
        return message, current, False
    if not current["topics"]:
        current["topics"] = previous["topics"]
    if not current["protocols"] and not current["excluded_protocols"] and not current["no_gateway"]:
        current["protocols"] = previous["protocols"]
        current["excluded_protocols"] = previous["excluded_protocols"]
        current["no_gateway"] = previous["no_gateway"]
    if current["min_price"] is None and current["max_price"] is None and not re.search(r"不限预算|不限价格|不限制价格", message):
        current["min_price"] = previous["min_price"]
        current["max_price"] = previous["max_price"]
    current["generic"] = False
    effective = message + " " + " ".join(current["topics"] + current["protocols"])
    return effective, current, True


def hard_match(product: dict, constraints: dict) -> bool:
    text = product_text(product).lower()
    target_text = " ".join(str(product.get(key) or "") for key in ("name", "category")).lower()
    price = product.get("price")
    if constraints["max_price"] is not None and (price is None or price > constraints["max_price"]):
        return False
    if constraints["min_price"] is not None and (price is None or price < constraints["min_price"]):
        return False
    if not constraints["inventory"] and product.get("stock", 0) <= 0:
        return False
    if constraints["topics"] and not any(any(alias in target_text for alias in TOPICS[topic]) for topic in constraints["topics"]):
        return False
    protocols = product_protocols(product)
    for protocol in constraints["protocols"]:
        if protocol not in protocols:
            return False
    for protocol in constraints["excluded_protocols"]:
        if protocol in protocols:
            return False
    if constraints["no_gateway"]:
        profile = profile_for(str(product.get("code", "")))
        if profile:
            if profile.get("gatewayRequired") is not False:
                return False
        elif not (re.search(r"(?:无需|不需要|不用|无)(?:\s*zigbee\s*)?网关", text) or "直连" in text):
            return False
    return True


def rank_products(query: str, products: list[dict], vectors: dict[str, float] | None = None, limit: int = 5, constraints: dict | None = None) -> list[dict]:
    constraints = constraints or query_constraints(query)
    candidates = [p for p in products if hard_match(p, constraints)]
    scored = []
    for product in candidates:
        text = product_text(product)
        score = lexical_similarity(query, text)
        if constraints["generic"]:
            score += 0.01
        if constraints["topics"]:
            score += sum(1.0 for topic in constraints["topics"] if any(alias in text.lower() for alias in TOPICS[topic]))
        if str(product.get("code", "")).lower() in query.lower() and product.get("code"):
            score += 2
        # Do not return arbitrary products merely because a vector has a rank.
        if score > 0.045 or constraints["generic"]:
            scored.append((score, product))
    keyword_ids = [p["id"] for _, p in sorted(scored, key=lambda item: (-item[0], item[1]["id"]))]
    if vectors is not None:
        eligible = {p["id"] for p in candidates}
        vector_ids = [pid for pid, score in sorted(vectors.items(), key=lambda item: (-item[1], item[0])) if pid in eligible and score >= 0.5]
    else:
        vector_ids = [p["id"] for _, p in sorted(scored, key=lambda item: (-lexical_similarity(query, product_text(item[1])), item[1]["id"]))]
    ordered = rrf_merge(keyword_ids, vector_ids, limit)
    by_id = {p["id"]: p for p in candidates}
    return [by_id[pid] for pid in ordered]


def retrieve_knowledge(query: str, vectors: dict[str, float] | None = None) -> list[dict]:
    selected = [KNOWLEDGE[0]]
    scored = []
    for source in KNOWLEDGE[1:]:
        score = lexical_similarity(query, source["title"] + source["content"])
        if vectors:
            score = max(score, vectors.get(source["id"], 0) - 0.3)
        if score > 0.065:
            scored.append((score, source))
    selected.extend(source for _, source in sorted(scored, key=lambda item: -item[0])[:3])
    return selected


def normalize_products(rows: list[dict], inventory_rows: list[dict]) -> list[dict]:
    stock = Counter()
    for row in inventory_rows:
        product = row.get("product") or {}
        pid = str(row.get("productId") or product.get("productId") or "")
        if pid:
            stock[pid] += number(row.get("planQuantity"), 0)
    output = []
    for row in rows:
        if str(row.get("status", "0")) != "0":
            continue
        pid = str(row.get("productId") or "")
        if not pid:
            continue
        category = row.get("productTypeName") or (row.get("type") or {}).get("productTypeName") or ""
        code = str(row.get("productCode") or "")
        output.append({"id": pid, "code": code, "name": str(row.get("productName") or ""), "spec": str(row.get("productSpecifications") or ""), "price": number(row.get("univalence")), "stock": stock.get(pid, 0), "category": category, "remark": str(row.get("notes") or ""), "profile": profile_for(code)})
    return output
