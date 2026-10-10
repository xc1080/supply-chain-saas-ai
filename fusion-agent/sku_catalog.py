"""Public SKU projection. A family groups concrete ERP product IDs; it owns no stock.

Merchant attributes describe a variant. They never inherit manufacturer evidence
from another SKU, and cannot authorize a price, stock or compatibility promise.
"""
from __future__ import annotations

import re
from fastapi import HTTPException

IDENTIFIER = re.compile(r"[a-z0-9_]{1,32}")


def public_sku_catalog(raw):
    if raw is None:
        return None
    try:
        if not isinstance(raw, dict) or not re.fullmatch(r"[a-f0-9]{32}", raw["spuId"]):
            raise ValueError()
        properties, attributes = [], raw["attributes"]
        if not isinstance(attributes, dict) or not 1 <= len(raw["properties"]) <= 6:
            raise ValueError()
        keys, selected = set(), []
        for prop in raw["properties"]:
            pid, name = prop["propertyId"], prop["propertyName"]
            if not IDENTIFIER.fullmatch(pid) or pid in keys or not isinstance(name, str) or not 1 <= len(name) <= 40:
                raise ValueError()
            keys.add(pid)
            values, ids = [], set()
            if not 1 <= len(prop["propertyValues"]) <= 30:
                raise ValueError()
            for value in prop["propertyValues"]:
                vid, label = value["propertyValueId"], value["propertyValue"]
                if not IDENTIFIER.fullmatch(vid) or vid in ids or not isinstance(label, str) or not 1 <= len(label) <= 40:
                    raise ValueError()
                ids.add(vid)
                values.append({"propertyValueId": vid, "propertyValue": label})
            if attributes.get(pid) not in ids:
                raise ValueError()
            selected.append(attributes[pid])
            properties.append({"propertyId": pid, "propertyName": name, "propertyValues": values})
        if set(attributes) != keys or raw["propertyValueIds"] != "-".join(selected):
            raise ValueError()
        if not isinstance(raw["spuName"], str) or not 1 <= len(raw["spuName"]) <= 100:
            raise ValueError()
        # Explicit allowlist: never forward arbitrary internal Java fields.
        return {"spuId": raw["spuId"], "spuName": raw["spuName"], "properties": properties,
                "attributes": dict(attributes), "propertyValueIds": "-".join(selected)}
    except (KeyError, ValueError, TypeError, AttributeError):
        raise HTTPException(502, "商品规格资料未能核验") from None


def property_data(product):
    meta = product.get("skuCatalog")
    if not meta:
        return [{"propertyName": "规格", "propertyValue": product["spec"]}]
    return [{"propertyName": prop["propertyName"],
             "propertyValue": next(value["propertyValue"] for value in prop["propertyValues"]
                                   if value["propertyValueId"] == meta["attributes"][prop["propertyId"]])}
            for prop in meta["properties"]]


def selection_key(product):
    return (product.get("skuCatalog") or {}).get("propertyValueIds", "default")


def validate_cart_selection(product, key):
    # Concrete productId alone remains valid for server-issued Agent cart drafts.
    if key not in (None, "") and key != selection_key(product):
        raise HTTPException(422, "所选规格与货品不一致，请重新选择")


def selection_input(key):
    if key in (None, ""):
        return None
    if not isinstance(key, str) or not re.fullmatch(r"[a-z0-9_]{1,32}(?:-[a-z0-9_]{1,32}){0,5}", key):
        raise HTTPException(422, "商品规格编号无效")
    return key


def detail_payload(product, catalog, shape):
    meta = product.get("skuCatalog")
    variants = [p for p in catalog if (p.get("skuCatalog") or {}).get("spuId") == meta["spuId"]] if meta else [product]
    properties = meta["properties"] if meta else [{"propertyId": "spec", "propertyName": "规格",
        "propertyValues": [{"propertyValueId": "default", "propertyValue": product["spec"]}]}]
    rows = []
    for variant in variants:
        info, key = shape(variant), selection_key(variant)
        rows.append({"skuId": variant["id"], "productId": variant["id"], "productCode": variant["code"],
                     "productName": variant["name"], "spec": variant["spec"], "description": variant["remark"],
                     "propertyValueIds": key, "propertyValueIdHash": key, "propertyData": property_data(variant),
                     "price": variant["price"], "stock": variant["stock"], "availableStock": variant["stock"],
                     "skuCatalog": variant.get("skuCatalog"), "attributes": (variant.get("skuCatalog") or {}).get("attributes"),
                     "cover": info["cover"],
                     "technicalProfile": info["technicalProfile"], "demo": info["demo"]})
    info = shape(product)
    prices = [p["price"] for p in variants if p["price"] is not None]
    info.update(spuId=meta["spuId"] if meta else None, spuName=meta["spuName"] if meta else None,
                minPrice=min(prices) if prices else None, maxPrice=max(prices) if prices else None)
    return {"productInfo": info, "productPropertyList": properties, "skuList": rows}
