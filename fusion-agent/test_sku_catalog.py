import copy
import unittest
from fastapi import HTTPException

from sku_catalog import public_sku_catalog, property_data, selection_key, validate_cart_selection, selection_input, detail_payload
from store_api import product_shape
from retrieval import product_protocols, rank_products
from business_catalog import compatibility_assessment


def metadata(color="white", protocol="wifi", family="a" * 32):
    return {"spuId": family, "spuName": "学习台灯",
            "properties": [{"propertyId": "color", "propertyName": "颜色", "propertyValues": [
                {"propertyValueId": "white", "propertyValue": "白色"}, {"propertyValueId": "black", "propertyValue": "黑色"}]},
                {"propertyId": "protocol", "propertyName": "协议", "propertyValues": [
                    {"propertyValueId": "wifi", "propertyValue": "WiFi"}, {"propertyValueId": "zigbee", "propertyValue": "Zigbee"}]}],
            "attributes": {"color": color, "protocol": protocol}, "propertyValueIds": color + "-" + protocol}


def variant(pid="1", color="white", protocol="wifi", **extra):
    return {"id": pid, "code": "LAB-SKU-" + pid, "name": "学习台灯", "spec": "12W",
            "category": "智能照明", "remark": "虚构学习规格，兼容性未验证", "price": 99, "stock": 5,
            "skuCatalog": public_sku_catalog(metadata(color, protocol)), **extra}


class SkuCatalogTests(unittest.TestCase):
    def test_detail_groups_only_same_family_concrete_skus_with_own_stock_and_price(self):
        white = variant()
        black = variant("2", "black", "zigbee", price=129, stock=2)
        foreign = variant("3", skuCatalog=public_sku_catalog(metadata(family="b" * 32)))
        detail = detail_payload(white, [white, black, foreign], product_shape)
        self.assertEqual([row["skuId"] for row in detail["skuList"]], ["1", "2"])
        self.assertEqual([(row["price"], row["stock"]) for row in detail["skuList"]], [(99, 5), (129, 2)])
        self.assertEqual(detail["productInfo"]["minPrice"], 99)
        self.assertEqual(detail["productInfo"]["maxPrice"], 129)
        self.assertIsNone(detail["skuList"][1]["technicalProfile"])

    def test_legacy_single_sku_contract_remains_default(self):
        product = variant(skuCatalog=None)
        detail = detail_payload(product, [product], product_shape)
        self.assertEqual(detail["skuList"][0]["propertyValueIds"], "default")
        self.assertEqual(property_data(product), [{"propertyName": "规格", "propertyValue": "12W"}])

    def test_projection_drops_private_fields_at_every_level(self):
        raw = metadata()
        raw.update(supplierId=21, costPrice=10, warehouseId=1)
        raw["properties"][0]["supplierName"] = "private"
        raw["properties"][0]["propertyValues"][0]["costPrice"] = 10
        public = public_sku_catalog(raw)
        self.assertNotIn("supplierId", public)
        self.assertNotIn("supplierName", public["properties"][0])
        self.assertNotIn("costPrice", public["properties"][0]["propertyValues"][0])

    def test_partial_duplicate_wrong_value_or_extra_dimension_fails_closed(self):
        raws = [metadata() for _ in range(5)]
        raws[0]["attributes"].pop("color")
        raws[1]["attributes"]["color"] = "silver"
        raws[2]["attributes"]["extra"] = "white"
        raws[3]["properties"].append(copy.deepcopy(raws[3]["properties"][0]))
        raws[4]["properties"][0]["propertyValues"].append(copy.deepcopy(raws[4]["properties"][0]["propertyValues"][0]))
        for raw in raws:
            with self.subTest(raw=raw), self.assertRaises(HTTPException) as raised:
                public_sku_catalog(raw)
            self.assertEqual(raised.exception.status_code, 502)

    def test_ordered_combination_rejects_mismatch_and_separator_in_id(self):
        raw = metadata()
        raw["propertyValueIds"] = "wifi-white"
        with self.assertRaises(HTTPException): public_sku_catalog(raw)
        raw = metadata()
        raw["properties"][0]["propertyValues"][0]["propertyValueId"] = "white-black"
        with self.assertRaises(HTTPException): public_sku_catalog(raw)

    def test_cart_wrong_combo_is_rejected_even_when_other_variant_exists(self):
        product = variant()
        validate_cart_selection(product, "white-wifi")
        validate_cart_selection(product, None)
        for key in ("black-zigbee", "default", "wifi-white"):
            with self.assertRaises(HTTPException): validate_cart_selection(product, key)
        self.assertEqual(selection_key(product), "white-wifi")

    def test_input_rejects_collections_oversize_or_injected_selection(self):
        for key in (["white", "wifi"], "white/../../wifi", "x" * 33, "a-b-c-d-e-f-g", 42):
            with self.subTest(key=key), self.assertRaises(HTTPException): selection_input(key)
        self.assertEqual(selection_input("white-wifi"), "white-wifi")

    def test_empty_family_catalog_does_not_invent_combinations(self):
        white = variant()
        detail = detail_payload(white, [white], product_shape)
        self.assertEqual(len(detail["skuList"]), 1)
        self.assertNotIn("black-wifi", [row["propertyValueIds"] for row in detail["skuList"]])

    def test_agent_protocol_search_resolves_actual_variant_without_inheriting_evidence(self):
        white, black = variant(), variant("2", "black", "zigbee")
        selected = rank_products("推荐 Zigbee 台灯", [white, black])
        self.assertEqual([row["id"] for row in selected], ["2"])
        self.assertEqual(product_protocols(white), {"wifi"})
        self.assertEqual(compatibility_assessment(black, "有 H100 网关能用吗？")["status"], "unknown")


if __name__ == "__main__": unittest.main()
