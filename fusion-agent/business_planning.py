"""Read-only business drafts; all money and stock facts come from Java.

The model may choose a tool, but cannot invent a bill of materials, approve a
substitution, reserve inventory, or turn an unknown arrival date into a promise.
"""
from __future__ import annotations

from decimal import Decimal, InvalidOperation
import re

from business_catalog import profile_for, requested_gateways, compatibility_assessment, evidence_snapshot

MAX_ITEMS = 20
MAX_QUANTITY = 999
NUMBERS = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5,
           "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
AMOUNT = r"(?:\d{1,4}|[一二两三四五六七八九十])"
COUNT_UNIT = r"(?:个|盏|只|台|套|件|颗|枚)"
ITEM_UNIT = r"(?:个|盏|只|台|件|颗|枚)"
PLATFORMS = ("HomeKit", "Apple Home", "米家", "Tuya", "涂鸦", "Alexa", "Google Home", "SmartThings", "Home Assistant")


def bundle_intent(message: str) -> bool:
    return bool(re.search(r"套装|整套|配齐|全屋|整屋|组合(?:方案|报价|选型)|卧室(?:方案|配置)|一起(?:买|配)|报价草稿", message)) or (
        "预算" in message and len(re.findall(AMOUNT + r"\s*" + COUNT_UNIT, message)) >= 2)


def effective_plan_message(message: str, history: list[dict]) -> str:
    """Continue a bounded clarification without relying on assistant guesses."""
    if bundle_intent(message):
        return message
    users = [item["content"] for item in history[-6:] if item.get("role") == "user" and isinstance(item.get("content"), str)]
    start = next((index for index in range(len(users) - 1, -1, -1) if bundle_intent(users[index])), None)
    if start is None or not clarification_intent(message):
        return message
    return "；".join(users[start:] + [message])


def clarification_intent(message: str) -> bool:
    return bool(re.search(r"预算.{0,6}\d|\d+\s*(?:个|盏|件|套)|已确认|已核对|未确认|未核对|没确认|(?:英国|UK|地区|区域).{0,8}(?:适用|安装)|(?:网关|型号).{0,8}(?:是|换成|改用)|(?:我(?:现在|目前)?有|我已有|已有).{0,15}(?:网关|[A-Za-z]+\d)", message, re.I)
                or re.fullmatch(r"\s*[¥￥]?\d+(?:\.\d+)?\s*元?\s*", message)
                or any(platform.casefold() in message.casefold() for platform in PLATFORMS))


def public_restock_intent(message: str) -> bool:
    if re.search(r"采购|备货(?:计划|草稿|建议)|补货(?:计划|草稿|建议)|缺货风险|活动配额|读取销量|审批", message):
        return False
    return bool(re.search(r"(?:什么时候|何时|多久|几天|预计|有没有|会不会).*(?:补货|到货)|(?:补货|到货).*(?:时间|日期|通知|多久|了吗)", message))


def replenishment_intent(message: str) -> bool:
    return not public_restock_intent(message) and bool(re.search(r"补货|备货|缺货风险|采购建议|采购草稿|活动配额(?:建议|调整)", message))


def _count(text: str) -> int | None:
    if text in NUMBERS:
        return NUMBERS[text]
    if not text.isdigit():
        return None
    value = int(text)
    return value if 0 < value <= MAX_QUANTITY else None


def _decimal(value, *, allow_zero=True) -> Decimal:
    if isinstance(value, bool):
        raise ValueError("Invalid business number")
    try:
        number = Decimal(str(value))
    except (InvalidOperation, TypeError):
        raise ValueError("Missing business number") from None
    if not number.is_finite() or number < 0 or (not allow_zero and number == 0):
        raise ValueError("Invalid business number")
    return number


def _model_pattern(model: str) -> str:
    return r"(?<![A-Za-z0-9])" + re.escape(model) + r"(?![A-Za-z0-9])"


def _negated_latest_model(model: str, message: str) -> bool:
    mentions = list(re.finditer(_model_pattern(model), message, re.I))
    if not mentions:
        return False
    last = mentions[-1]
    before = re.split(r"[，,、。；;！？]", message[:last.start()])[-1]
    after = message[last.end():]
    return bool(re.search(r"(?:没有|不用|不要|不使用|排除)[^A-Za-z0-9]{0,10}$", before)
                or re.match(r"\s*(?:网关)?\s*(?:不用|不使用|不要|不再用)(?:了)?(?:[，,、。；;！？]|$)", after))


def _quantity_for(message: str, pattern: str) -> int | None:
    pattern = "(?:" + pattern + ")"
    before = list(re.finditer(r"(" + AMOUNT + r")\s*" + ITEM_UNIT + r"\s*(?:[^，,、。；;0-9]{0,8}?)" + pattern, message, re.I))
    after = list(re.finditer(pattern + r"\s*(?:[×x*：:]|各|需要|要)?\s*(" + AMOUNT + r")\s*(?:" + ITEM_UNIT + r")?", message, re.I))
    found = max(before + after, key=lambda match: match.start(), default=None)
    return _count(found.group(1)) if found else None


def capability(product: dict) -> str:
    """A sensor detecting a door is not a replacement for a leak detector."""
    profile = profile_for(str(product.get("code", ""))) or {}
    text = " ".join([str(product.get("name", "")), *profile.get("facts", [])])
    for name, pattern in (("leak", r"漏水|浸水"), ("temperature", r"温湿度|温度"),
                          ("motion", r"人体|移动检测"), ("contact", r"门窗|开合")):
        if re.search(pattern, text):
            return name
    kind = profile.get("imageKind")
    if kind:
        return kind
    for name, pattern in (("lamp", "灯|照明"), ("gateway", "网关|中枢"), ("switch", "开关"), ("plug", "插座"),
                          ("curtain", "窗帘"), ("lock", "门锁")):
        if re.search(pattern, str(product.get("name", ""))):
            return name
    return "unknown"


REQUIREMENTS = (("leak", r"漏水(?:传感器|检测器)?|浸水传感器"),
                ("temperature", r"温湿度(?:传感器)?|温度传感器"),
                ("motion", r"人体(?:移动)?(?:传感器|感应器)?|移动传感器"),
                ("contact", r"门窗(?:传感器|感应器)?"),
                ("lamp", r"智能灯|灯泡|照明灯|灯"),
                ("switch", r"智能开关|开关"), ("plug", r"智能插座|插座"),
                ("curtain", r"窗帘(?:电机)?"), ("lock", r"智能门锁|门锁"))


def request_from_message(message: str, catalog: list[dict]) -> dict:
    """Extract only explicit quantities; unspecified room designs require input."""
    budget_matches = re.findall(r"(?:总预算|预算|整套不超过|合计不超过)\s*(?:改成|调整为|是|为)?\s*[¥￥]?\s*(\d+(?:\.\d+)?)", message)
    budget = float(budget_matches[-1]) if budget_matches else None
    units_matches = re.findall(r"(?:共|一共|需要|做|配)\s*(\d+)\s*套", message)
    units = _count(units_matches[-1]) if units_matches else 1
    missing = []
    if units is None:
        missing.append({"field": "units", "question": "需要配置多少套？每套数量应在支持范围内。"})
        units = 1
    if budget is None:
        missing.append({"field": "budget", "question": "这批设备的总预算是多少？"})
    known_gateways = requested_gateways(message)
    owned_gateways = []
    for gateway in known_gateways:
        pattern = _model_pattern(gateway["model"])
        if _negated_latest_model(gateway["model"], message):
            continue
        if re.search(r"(?:已有|已经有|我(?:现在|目前)?有|现有|家里有|已配备|用的是|现在用|改用|换成|换用)[^，,。；;]{0,35}" + pattern, message, re.I):
            owned_gateways.append(gateway["model"])
    for unknown in re.findall(r"(?:已有|我(?:现在|目前)?有|网关(?:型号)?是|网关换成|网关改用)\s*([A-Za-z][A-Za-z0-9-]{1,25})", message, re.I):
        negated = _negated_latest_model(unknown, message)
        if not negated and unknown not in owned_gateways:
            owned_gateways.append(unknown)
    items, selected = [], set()
    for product in catalog:
        profile = profile_for(str(product.get("code", "")))
        model = profile.get("model") if profile else None
        if not model or not re.search(_model_pattern(model), message, re.I):
            continue
        pattern = _model_pattern(model)
        if model in owned_gateways and _quantity_for(message, pattern) is None:
            continue
        # Explicitly absent / rejected models are not purchase requirements.
        if re.search(r"(?:不要|不买|排除|没有|不用)[^，,。；;A-Za-z0-9]{0,10}" + pattern, message, re.I):
            continue
        quantity = _quantity_for(message, pattern)
        if quantity is None:
            missing.append({"field": "quantity:" + str(product["id"]), "question": model + " 每套需要多少件？"})
            continue
        items.append({"productId": str(product["id"]), "quantity": quantity})
        selected.add(capability(product))
    requirements = []
    for kind, pattern in REQUIREMENTS:
        if kind in selected or not re.search(pattern, message):
            continue
        # A name of an explicitly requested model may also mention its function.
        quantity = _quantity_for(message, pattern)
        if quantity is not None:
            requirements.append({"kind": kind, "quantity": quantity})
        elif not any(item["field"].startswith("quantity:") for item in missing):
            missing.append({"field": "quantity:" + kind, "question": "每套需要多少" + re.search(pattern, message).group() + "？"})
    sensor_kinds = {"motion", "temperature", "contact", "leak"}
    if "传感器" in message and not selected.intersection(sensor_kinds) and not any(row["kind"] in sensor_kinds for row in requirements):
        missing.append({"field": "sensorPurpose", "question": "传感器要检测人体、门窗、温湿度还是漏水？这些用途不能互相替代。"})
    if not items and not requirements and not any(item["field"].startswith("quantity:") for item in missing):
        missing.append({"field": "requirements", "question": "想配哪些设备、每种多少件？例如灯和人体传感器。"})
    return {"items": items, "requirements": requirements, "budget": budget, "units": units,
            "requestedFirmwareVersion": (re.search(r"固件(?:版本)?(?:为|是)?\s*[:：]?\s*([A-Za-z0-9_.-]{1,40})",message).group(1) if re.search(r"固件(?:版本)?(?:为|是)?\s*[:：]?\s*([A-Za-z0-9_.-]{1,40})",message) else None),
            "requestedRegion": "CN" if re.search(r"(?:中国|国内|大陆).{0,8}(?:安装|使用)|(?:安装|使用).{0,8}(?:中国|国内|大陆)",message) else None,
            "ownedGatewayModels": owned_gateways,
            "requestedPlatforms": [platform for platform in PLATFORMS if platform.casefold() in message.casefold()],
            "installationConfirmed": bool(re.search(r"(?:已确认|已核对|确认了).{0,12}(?:安装|电源|频段)|(?:安装|电源|频段).{0,12}(?:已确认|已核对|没问题)", message)),
            "regionConfirmed": bool(re.search(r"(?:英国|UK|海外|区域|地区).{0,12}(?:已确认|已核对|适用|安装)|(?:已确认|已核对).{0,12}(?:区域|地区|英国|UK)", message, re.I)),
            "missing": missing, "message": message}


def merge_clarification(previous: dict, message: str, catalog: list[dict]) -> dict:
    """Merge only explicit updates into a server-owned previous draft request."""
    current = request_from_message(message, catalog)
    merged = validate_request(previous)
    merged["items"] = list(merged["items"])
    by_id = {item["productId"]: item for item in merged["items"]}
    by_product = {str(product["id"]): product for product in catalog}
    replaced_kinds = set()
    if re.search(r"换成|改成|替换为|替换成", message):
        replaced_kinds = {capability(by_product[item["productId"]]) for item in current["items"] if item["productId"] in by_product}
        by_id = {pid: item for pid, item in by_id.items() if pid not in by_product or capability(by_product[pid]) not in replaced_kinds}
    for item in current["items"]:
        if re.search(r"再加|增加|追加", message) and item["productId"] in by_id:
            item = {**item, "quantity": item["quantity"] + by_id[item["productId"]]["quantity"]}
        by_id[item["productId"]] = item
    merged["items"] = list(by_id.values())
    requirements = {item["kind"]: item for item in merged.get("requirements", [])}
    requirements = {kind: item for kind, item in requirements.items() if kind not in replaced_kinds}
    requirements.update({item["kind"]: item for item in current["requirements"]})
    merged["requirements"] = list(requirements.values())
    if current["budget"] is not None:
        merged["budget"] = current["budget"]
    elif merged["budget"] is None:
        bare_budget = re.fullmatch(r"\s*[¥￥]?(\d+(?:\.\d+)?)\s*元?\s*", message)
        if bare_budget:
            merged["budget"] = float(bare_budget.group(1))
    if re.search(r"(?:共|一共|需要|做|配)\s*\d+\s*套", message):
        merged["units"] = current["units"]
    if current["ownedGatewayModels"]:
        merged["ownedGatewayModels"] = current["ownedGatewayModels"]
    elif re.search(r"(?:没有|不用|不再用|不使用|暂无).{0,10}网关|网关.{0,10}(?:没有|不用)", message):
        merged["ownedGatewayModels"] = []
    if current["requestedPlatforms"]:
        merged["requestedPlatforms"] = current["requestedPlatforms"]
    for field in ('requestedFirmwareVersion','requestedHardwareRevision','requestedRegion'):
        if current.get(field): merged[field]=current[field]
    for field, pattern in (("installationConfirmed", r"安装|电源|接线|频段"), ("regionConfirmed", r"区域|地区|英国|UK")):
        if current[field]:
            merged[field] = True
        elif re.search("(?:" + pattern + r").{0,8}(?:没确认|未确认|未核对|不适用)|(?:没确认|未确认|未核对).{0,8}(?:" + pattern + ")", message, re.I):
            merged[field] = False
    kinds = {capability(by_product[item["productId"]]) for item in merged["items"] if item["productId"] in by_product}
    kinds.update(requirements)
    missing = []
    for item in previous.get("missing", []):
        field = item.get("field", "")
        if field in {"budget", "gateway", "regionConfirmed", "installationConfirmed", "platformVerification",'firmwareVerification','hardwareVerification','regionVerification','evidenceVerification'}:
            continue  # Recomputed against the merged values by prepare_bundle.
        if field == "requirements" and (merged["items"] or merged["requirements"]):
            continue
        if field.startswith("quantity:") and (field.split(":", 1)[1] in by_id or field.split(":", 1)[1] in kinds):
            continue
        if field == "sensorPurpose" and kinds.intersection({"motion", "contact", "temperature", "leak"}):
            continue
        if field.startswith("product:") and field.split(":", 1)[1] in kinds:
            continue
        missing.append(item)
    for item in current["missing"]:
        field = item["field"]
        if field in {"budget", "requirements"}:
            continue
        if field.startswith("quantity:") or field == "sensorPurpose":
            missing.append(item)
    merged["missing"] = missing
    return merged


def validate_request(raw: dict) -> dict:
    if not isinstance(raw, dict):
        raise ValueError("Invalid bundle request")
    items = raw.get("items", [])
    if not isinstance(items, list) or len(items) > MAX_ITEMS:
        raise ValueError("Invalid item count")
    clean_items, ids = [], set()
    for item in items:
        if not isinstance(item, dict):
            raise ValueError("Invalid bundle item")
        pid, quantity = str(item.get("productId", "")), item.get("quantity")
        if not re.fullmatch(r"\d{1,18}", pid) or pid in ids or isinstance(quantity, bool) or not isinstance(quantity, int) or not 1 <= quantity <= MAX_QUANTITY:
            raise ValueError("Invalid bundle item")
        ids.add(pid)
        clean_items.append({"productId": pid, "quantity": quantity})
    units = raw.get("units", 1)
    if isinstance(units, bool) or not isinstance(units, int) or not 1 <= units <= MAX_QUANTITY:
        raise ValueError("Invalid bundle units")
    budget = raw.get("budget")
    if budget is not None:
        budget = float(_decimal(budget, allow_zero=False))
    owned = raw.get("ownedGatewayModels", [])
    if not isinstance(owned, list) or len(owned) > 10 or any(not isinstance(model, str) or len(model) > 60 for model in owned):
        raise ValueError("Invalid gateway models")
    platforms = raw.get("requestedPlatforms", [])
    if not isinstance(platforms, list) or len(platforms) > 10 or any(not isinstance(platform, str) or len(platform) > 60 for platform in platforms):
        raise ValueError("Invalid platform requirements")
    missing = raw.get("missing", [])
    if not isinstance(missing, list):
        raise ValueError("Invalid missing inputs")
    for field in ('requestedFirmwareVersion','requestedHardwareRevision','requestedRegion'):
        value=raw.get(field)
        if value is not None and (not isinstance(value,str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,40}',value)):
            raise ValueError('Invalid version/region requirement')
    return {**raw, "items": clean_items, "units": units, "budget": budget,
            "ownedGatewayModels": owned,
            "requestedPlatforms": platforms,
            "installationConfirmed": raw.get("installationConfirmed") is True,
            "regionConfirmed": raw.get("regionConfirmed") is True, "missing": list(missing)}


def _pair_check(product: dict, gateway_models: list[str]) -> dict:
    profile = profile_for(str(product.get("code", "")))
    if profile and profile.get("imageKind") == "gateway" and profile.get('evidenceStatus')=='PUBLISHED':
        return {"status": "gateway_component", "sourceId": "M-" + profile["code"],
                'evidenceVersion':profile['evidenceVersion'],'validationScope':profile['validationScope']}
    return compatibility_assessment(product, "；".join(gateway_models))


def prepare_bundle(raw: dict, catalog: list[dict]) -> tuple[dict, list[dict]]:
    request = validate_request(raw)
    by_id = {str(product["id"]): product for product in catalog}
    if any(item["productId"] not in by_id for item in request["items"]):
        raise ValueError("A requested product is not authorized for this shop")
    missing = list(request["missing"])
    if request["budget"] is None and not any(item.get("field") == "budget" for item in missing):
        missing.append({"field": "budget", "question": "这批设备的总预算是多少？"})
    items = list(request["items"])
    for requirement in request.get("requirements", []):
        if not isinstance(requirement, dict) or requirement.get("kind") not in {row[0] for row in REQUIREMENTS}:
            raise ValueError("Invalid functional requirement")
        quantity = requirement.get("quantity")
        if isinstance(quantity, bool) or not isinstance(quantity, int) or not 1 <= quantity <= MAX_QUANTITY:
            raise ValueError("Invalid requirement quantity")
        candidates = [product for product in catalog if capability(product) == requirement["kind"]]
        # Concrete manufacturer evidence precedes price preference. Unknown
        # combinations remain reviewable, rather than silently called compatible.
        def priority(product):
            status = _pair_check(product, request["ownedGatewayModels"])["status"]
            return (status not in {"verified_pair", "gateway_not_required"},
                    float(product.get("stock") or 0) < quantity * request["units"],
                    product.get("price") if product.get("price") is not None else float("inf"), str(product["id"]))
        if candidates:
            product = sorted(candidates, key=priority)[0]
            if not any(item["productId"] == str(product["id"]) for item in items):
                items.append({"productId": str(product["id"]), "quantity": quantity})
        else:
            missing.append({"field": "product:" + requirement["kind"], "question": "当前店铺没有能核对这项需求的设备，需要调整方案。"})
    if len(items) > MAX_ITEMS:
        raise ValueError("Too many bundle components")
    selected = [by_id[item["productId"]] for item in items]
    gateway_models = request["ownedGatewayModels"] + [(profile_for(p.get("code", "")) or {}).get("model", "")
        for p in selected if capability(p) == "gateway"]
    checks = [{"productId": str(p["id"]), "name": p["name"], **_pair_check(p, gateway_models)} for p in selected]
    if any(check["status"] == "needs_gateway_model" for check in checks):
        missing.append({"field": "gateway", "question": "这些设备需要配套网关。已有网关是什么型号，还是需要一并购买？"})
    has_region = any((profile_for(p.get("code", "")) or {}).get("region") for p in selected)
    if has_region and not request["regionConfirmed"]:
        missing.append({"field": "regionConfirmed", "question": "样本为 UK 或海外区域型号，安装地区和电源、无线频段是否已核对适用？"})
    if selected and not request["installationConfirmed"]:
        missing.append({"field": "installationConfirmed", "question": "请先核对灯口、电源、接线及设备安装条件；这些条件是否已确认？"})
    if request["requestedPlatforms"] and any(not set(request["requestedPlatforms"]).issubset(set((profile_for(p.get("code", "")) or {}).get("platforms", []))) for p in selected):
        missing.append({"field": "platformVerification", "question": "现有型号资料未证实这些设备全部支持指定的平台，需要商家补充厂商平台依据。"})
    if any(check['status']=='evidence_withdrawn' for check in checks):
        missing.append({'field':'evidenceVerification','question':'所选型号的依据已撤销或未发布，需要重新核对有效厂商资料。'})
    for field,source_key,missing_key,label in [('requestedFirmwareVersion','firmwareVersions','firmwareVerification','固件版本'),
                                             ('requestedHardwareRevision','hardwareRevisions','hardwareVerification','硬件版本')]:
        if request.get(field) and any(request[field] not in (profile_for(p.get('code','')) or {}).get(source_key,[]) for p in selected):
            missing.append({'field':missing_key,'question':'现有资料未验证指定'+label+'，需要该版本的厂商兼容依据。'})
    if request.get('requestedRegion') and any(not str((profile_for(p.get('code','')) or {}).get('region','')).upper().startswith(request['requestedRegion'].upper()) for p in selected):
        missing.append({'field':'regionVerification','question':'现有型号资料未验证指定安装地区；地区确认不能替代型号电源、频段和法规适配依据。'})
    compatible = all(check["status"] in {"verified_pair", "gateway_not_required", "gateway_component"} for check in checks)
    compatibility = "COMPATIBLE" if checks and compatible else "UNKNOWN"
    if any(check["status"] == "needs_gateway_model" for check in checks):
        compatibility = "NEEDS_INPUT"
    plan = {"type": "CONSUMER_BUNDLE", "status": "NEEDS_INPUT" if missing else "REVIEW_REQUIRED",
            "units": request["units"], "items": items, "missing": missing,
            "budget": {"limit": request["budget"], "total": None, "withinBudget": None, "scope": "PRODUCTS_ONLY"},
            "compatibility": {"status": compatibility, "scope": "gateway_model_pairs", "checks": checks,
                              'firmwareStatus':'UNVERIFIED','hardwareStatus':'UNVERIFIED','regionStatus':'USER_ACKNOWLEDGED' if request['regionConfirmed'] else 'NEEDS_INPUT'},
            'evidenceSnapshot':evidence_snapshot([p['code'] for p in selected],request['ownedGatewayModels']),
            "inventory": {"requestedUnits": request["units"], "promisableUnits": None, "shortages": []},
            "delivery": {"status": "UNKNOWN", "date": None, "promise": False},
            "alternatives": [], "requiresApproval": True, "executable": False,
            "assumptions": ["查询不占库存；确认下单前由业务服务重新校验价格和可售库存。",
                            "预算合计仅包含所选设备，不含未报价的安装、配送与服务费用。",
                            "网关配对依据不代表整屋自动化、平台联动或安装已通过验证。"]}
    plan["request"] = {"items": items, "units": request["units"], "budget": request["budget"],
                       "ownedGatewayModels": request["ownedGatewayModels"],
                       "requestedPlatforms": request["requestedPlatforms"],
                       "installationConfirmed": request["installationConfirmed"],
                       "regionConfirmed": request["regionConfirmed"],
                       **{field:request.get(field) for field in ('requestedFirmwareVersion','requestedHardwareRevision','requestedRegion')},
                       "requirements": request.get("requirements", []), "missing": missing}
    return plan, selected


def apply_authoritative_quote(plan: dict, quote: dict, catalog: list[dict]) -> dict:
    """Reject incomplete or inconsistent authority responses instead of fallback."""
    if not isinstance(quote, dict) or not isinstance(quote.get("items"), list):
        raise ValueError("Authority quote is missing")
    requested = {item["productId"]: item["quantity"] for item in plan["items"]}
    quoted = {str(item["productId"]): item for item in quote["items"]}
    if len(quote["items"]) != len(quoted) or set(quoted) != set(requested):
        raise ValueError("Authority quote has inconsistent items")
    by_id = {str(product["id"]): product for product in catalog}
    total, bottleneck, items, shortages = Decimal(0), None, [], []
    for pid, quantity in requested.items():
        row = quoted[pid]
        if row.get("quantity") != quantity:
            raise ValueError("Authority quote quantity mismatch")
        price, stock = _decimal(row.get("unitPrice")), _decimal(row.get("availableStock"))
        line = price * quantity * plan["units"]
        total += line
        possible = int(stock // quantity)
        bottleneck = possible if bottleneck is None else min(bottleneck, possible)
        shortage = max(Decimal(0), Decimal(quantity * plan["units"]) - stock)
        product = by_id[pid]
        items.append({"productId": pid, "code": product["code"], "name": product["name"], "quantity": quantity,
                      "unitPrice": float(price), "lineTotal": float(line), "availableStock": float(stock)})
        if shortage:
            shortages.append({"productId": pid, "name": product["name"], "quantity": float(shortage)})
    if quote.get("totalAmount") is None or _decimal(quote["totalAmount"]) != total:
        raise ValueError("Authority quote total mismatch")
    promised = quote.get("promisableUnits")
    if isinstance(promised, bool) or not isinstance(promised, int) or promised < 0 or promised > (bottleneck or 0):
        raise ValueError("Authority quote overpromises inventory")
    capacity = quote.get("deliveryCapacity")
    if capacity is not None and (isinstance(capacity, bool) or not isinstance(capacity, int) or capacity < 0 or promised > capacity):
        raise ValueError("Invalid delivery capacity")
    plan["items"] = items
    plan["budget"].update(total=float(total), withinBudget=None if plan["budget"]["limit"] is None else total <= _decimal(plan["budget"]["limit"]))
    plan["inventory"].update(promisableUnits=promised, shortages=shortages)
    plan["inventory"]["inventoryPromisableUnits"] = bottleneck or 0
    # Arrival date is a separate merchant fact. Stock alone cannot prove it.
    known_delivery = quote.get("deliveryStatus") == "KNOWN" and isinstance(quote.get("deliveryDate"), str) and bool(quote["deliveryDate"])
    plan["delivery"] = {"status": "KNOWN" if known_delivery else "UNKNOWN", "date": quote.get("deliveryDate") if known_delivery else None,
                        "promise": False, "capacity": capacity, "dispatchDate": quote.get("dispatchDate"),
                        "basis": quote.get("deliveryBasis")}
    if not plan["missing"] and plan["compatibility"]["status"] == "COMPATIBLE" and plan["budget"]["withinBudget"] is True and promised >= plan["units"]:
        plan["status"] = "READY_FOR_REVIEW"
    return plan


def alternative_candidates(plan: dict, catalog: list[dict]) -> list[dict]:
    """Offer reviewable alternatives; never silently substitute unlike devices."""
    by_id = {str(p["id"]): p for p in catalog}
    gateway_models = []
    for check in plan["compatibility"]["checks"]:
        gateway_models.extend(check.get("requestedGatewayModels", []))
    alternatives = []
    for shortage in plan["inventory"]["shortages"]:
        original = by_id[shortage["productId"]]
        item = next(item for item in plan["items"] if item["productId"] == shortage["productId"])
        for product in catalog:
            if str(product["id"]) == shortage["productId"] or capability(product) != capability(original) or capability(product) == "unknown":
                continue
            if float(product.get("stock") or 0) < item["quantity"] * plan["units"]:
                continue
            assessment = _pair_check(product, gateway_models)
            if assessment["status"] not in {"verified_pair", "gateway_not_required"}:
                continue
            profile = profile_for(product["code"]) or {}
            alternatives.append({"replacesProductId": shortage["productId"], "productId": str(product["id"]),
                "name": product["name"], "quantity": item["quantity"], "requiresApproval": True,
                "reason": "同类用途候选；需确认功能差异后重新报价，不自动替换。",
                "differences": profile.get("facts", [])[:3], "compatibility": assessment})
    return alternatives[:5]


REPLENISHMENT_FIELDS = ("productId", "productCode", "productName", "onHandStock", "reservedStock", "availableStock",
    "incomingStock", "incomingKnown", "salesUnits", "salesWindowDays", "reorderPoint", "targetStock",
    "supplierLeadDays", "leadTimeKnown", "suggestedQuantity", "reason", "risk", "dataKind", "activityStock",
    "unavailableStock", "incomingDueWithinLead", "overdueIncoming", "incomingCoverage", "salesBasis",
    "forecastDemand", "projectedStock")


def replenishment_draft(raw: dict, *, channel: str) -> dict:
    if channel != "workspace":
        raise PermissionError("Merchant planning is unavailable in customer chat")
    if not isinstance(raw, dict) or not isinstance(raw.get("items"), list):
        raise ValueError("Authority replenishment data is missing")
    items = [{key: item[key] for key in REPLENISHMENT_FIELDS if key in item} for item in raw["items"]]
    return {"type": "MERCHANT_REPLENISHMENT", "status": "DRAFT", "requiresApproval": True,
            "executable": False, "items": items, "assumptions": [
                "建议由业务服务按授权店铺的销量、占用、在途与补货参数生成。",
                "未知在途与交期不得当成零在途或立即可交付；供应商确认后方可创建采购单。",
                "本工具不创建采购单、不调整活动配额，也不修改库存。"]}


def plan_answer(plan: dict) -> str:
    """Format checked values deterministically; no model can change this quote."""
    if plan["type"] == "MERCHANT_REPLENISHMENT":
        count = len([item for item in plan["items"] if float(item.get("suggestedQuantity") or 0) > 0])
        return f"已读取当前店铺的备货数据，形成 {count} 项补货建议草稿。请核对销量窗口、在途和供应商交期，再由采购人员审批执行；本次没有创建采购单或修改库存。"
    if plan["missing"]:
        return "先补充一个关键条件：" + plan["missing"][0]["question"] + (" 已选设备和待核对条件已保留在方案草稿中。" if plan["items"] else "")
    budget = plan["budget"]
    text = f"已形成 {plan['units']} 套设备的报价草稿，设备合计 ¥{budget['total']:.2f}。" if budget["total"] is not None else "已整理设备清单，报价服务暂未提供可核验的价格。"
    if budget["withinBudget"] is False:
        text += "整套金额超过总预算，需要减少数量或确认替代方案。"
    if plan["inventory"]["shortages"]:
        names = "、".join(item["name"] for item in plan["inventory"]["shortages"])
        text += names + " 可售数量不足，当前库存最多支持 " + str(plan["inventory"]["inventoryPromisableUnits"]) + " 套。"
    capacity = plan["delivery"].get("capacity")
    if capacity is not None and capacity < plan["units"]:
        text += "商家当前登记的发货处理余量仅支持 " + str(capacity) + " 套，需要另行确认发货安排。"
    if plan["compatibility"]["status"] != "COMPATIBLE":
        text += "现有厂商资料未证实全部具体型号配对，不能据此承诺兼容。"
    if plan["alternatives"]:
        text += "另列有同类用途替代候选，功能存在差异，确认后需重新核价和检查库存。"
    if plan["delivery"]["status"] == "UNKNOWN":
        text += "商家尚未提供已确认的到货时间，本方案不承诺交付日期。"
    text += "这是待确认草稿，尚未加购、下单或占用库存。"
    return text
