"""Curated manufacturer evidence, separate from tenant-owned prices and inventory.

The bundled catalog is a reproducible business scenario. Its simulated costs,
suppliers and opening quantities must never become customer facts or embeddings.
"""
from __future__ import annotations

from copy import deepcopy
from functools import lru_cache
import json
from pathlib import Path
import re
from urllib.parse import urlparse

DATA_PATH = Path(__file__).parent / "data" / "scenario_catalog.json"
PUBLIC_FIELDS = ("code", "brand", "model", "region", "spec", "protocols", "gatewayRequired",
                 "compatibleGatewayCodes", "compatibilityStatus", "sources", "facts", "imageKind")


@lru_cache(maxsize=1)
def load_scenario() -> dict:
    if not DATA_PATH.exists():
        return {"schemaVersion": 1, "products": [], "suppliers": []}
    data = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1 or not isinstance(data.get("products"), list):
        raise ValueError("Unsupported scenario catalog schema")
    products = data["products"]
    codes = [product["code"] for product in products]
    if len(codes) != len(set(codes)):
        raise ValueError("Duplicate scenario product codes")
    for product in products:
        if not re.fullmatch(r"LAB-[A-Za-z0-9_-]{1,48}", product["code"]):
            raise ValueError("Invalid scenario product code")
        if not product.get("sources") or not isinstance(product.get("facts"), list):
            raise ValueError("Manufacturer evidence is required")
        if product.get("compatibilityStatus") not in {"verified", "unknown", "not_required"}:
            raise ValueError("Invalid compatibility evidence status")
        for source in product["sources"]:
            if urlparse(source["url"]).scheme != "https" or not source.get("title"):
                raise ValueError("A labelled HTTPS manufacturer source is required")
        if any(code not in codes for code in product.get("compatibleGatewayCodes", [])):
            raise ValueError("Unknown gateway product reference")
        simulation = product.get("simulation", {})
        for key in ("salePrice", "costPrice", "openingStock", "reorderPoint", "targetStock", "supplierLeadDays"):
            value = simulation.get(key)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0:
                raise ValueError("Invalid simulated business value: " + key)
    return data


def profile_for(code: str) -> dict | None:
    product = next((row for row in load_scenario()["products"] if row["code"] == code), None)
    if product is None:
        return None
    profile = {key: deepcopy(product[key]) for key in PUBLIC_FIELDS if key in product}
    profile["dataKind"] = "manufacturer_reference_with_simulated_trade"
    by_code = {row["code"]: row for row in load_scenario()["products"]}
    profile["compatibleGatewayModels"] = [by_code[key]["model"] for key in product.get("compatibleGatewayCodes", [])]
    return profile


def manufacturer_source(product: dict) -> dict | None:
    profile = profile_for(str(product.get("code", "")))
    if profile is None:
        return None
    return {"id": "M-" + profile["code"], "title": product["name"] + "（厂商型号资料）",
            "content": json.dumps(profile, ensure_ascii=False), "urls": [source["url"] for source in profile["sources"]]}


def _mentioned(model: str, query: str) -> bool:
    # Do not match H100 in H1000 or T1 in T100. Spaces/hyphens remain meaningful.
    return bool(re.search(r"(?<![A-Za-z0-9])" + re.escape(model) + r"(?![A-Za-z0-9])", query, re.I))


def mentioned_gateways(query: str) -> list[dict]:
    return [row for row in load_scenario()["products"]
            if row.get("imageKind") == "gateway" and _mentioned(row["model"], query)]


def requested_gateways(query: str) -> list[dict]:
    """Exclude explicitly absent equipment, without treating every mention as ownership."""
    result = []
    for row in mentioned_gateways(query):
        pattern = r"(?<![A-Za-z0-9])" + re.escape(row["model"]) + r"(?![A-Za-z0-9])"
        for match in re.finditer(pattern, query, re.I):
            before = re.split(r"[，,。；;！？\n]", query[:match.start()])[-1]
            after = query[match.end():]
            excluded = re.search(r"(?:没有|没用|不用|不使用|不再用|未使用|不要|不是|排除)[^，,。；;！？A-Za-z0-9]{0,12}$", before)
            excluded = excluded or re.match(r"\s*(?:网关)?\s*(?:没有|没用|不用|不使用|不要)(?:[，,。；;！？]|$)", after)
            if not excluded:
                result.append(row)
                break
    return result


def compatibility_assessment(product: dict, query: str) -> dict:
    profile = profile_for(str(product.get("code", "")))
    if not profile:
        return {"status": "unknown", "reason": "尚无该型号的厂商兼容依据"}
    base = {"sourceId": "M-" + profile["code"], "region": profile.get("region"),
            "gatewayRequired": profile.get("gatewayRequired"),
            "compatibleGatewayModels": profile["compatibleGatewayModels"]}
    if profile.get("gatewayRequired") is False and profile.get("compatibilityStatus") == "not_required":
        return {**base, "status": "gateway_not_required", "reason": "厂商资料列明无需配套网关；平台与安装条件仍按型号资料核对"}
    mentioned = requested_gateways(query)
    if not mentioned:
        return {**base, "status": "needs_gateway_model" if profile.get("gatewayRequired") else "unknown",
                "reason": "需要具体网关型号才能核对；不能仅凭协议名称确认"}
    matches = [row["model"] for row in mentioned if row["code"] in profile.get("compatibleGatewayCodes", [])]
    requested = [row["model"] for row in mentioned]
    unknown = [model for model in requested if model not in matches]
    if matches and not unknown and profile.get("compatibilityStatus") == "verified":
        return {**base, "status": "verified_pair", "matchedGatewayModels": matches, "requestedGatewayModels": requested,
                "reason": "已收录的厂商资料明确列出该型号组合"}
    return {**base, "status": "unknown_pair", "requestedGatewayModels": requested,
            "matchedGatewayModels": matches if profile.get("compatibilityStatus") == "verified" else [],
            "unverifiedGatewayModels": unknown if profile.get("compatibilityStatus") == "verified" else requested,
            "reason": "收录资料未证实该组合；不据此断言兼容或不兼容"}
