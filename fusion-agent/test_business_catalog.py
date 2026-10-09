import json
import unittest
from business_catalog import load_scenario, profile_for, manufacturer_source, compatibility_assessment
from retrieval import product_protocols, hard_match, query_constraints, product_text


class BusinessCatalogTests(unittest.TestCase):
    def setUp(self):
        load_scenario.cache_clear()

    def product(self, code):
        row = next(p for p in load_scenario()["products"] if p["code"] == code)
        return {"id": "reference", "code": code, "name": row["name"], "spec": row["spec"],
                "category": row["category"], "remark": "", "stock": 2, "price": 79}

    def test_scenario_has_sourced_models_and_simulation_is_separate(self):
        data = load_scenario()
        self.assertEqual(len(data["products"]), 12)
        self.assertTrue(all(p["sources"] and p["region"] for p in data["products"]))
        for p in data["products"]:
            public = json.dumps(profile_for(p["code"]), ensure_ascii=False)
            self.assertNotIn("costPrice", public)
            self.assertNotIn("openingStock", public)
            self.assertNotIn("supplierCode", public)
            source = manufacturer_source(self.product(p["code"]))
            self.assertNotIn("costPrice", source["content"])
            self.assertNotIn("supplierCode", product_text(self.product(p["code"])))

    def test_subghz_sensor_is_not_mislabeled_zigbee(self):
        self.assertEqual(product_protocols(self.product("LAB-TAPO-T100")), {"sub-ghz"})

    def test_only_verified_gateway_pair_is_accepted(self):
        p = self.product("LAB-TAPO-T100")
        self.assertEqual(compatibility_assessment(p, "已有 H100 网关") ["status"], "verified_pair")
        self.assertEqual(compatibility_assessment(p, "已有 M3 网关") ["status"], "unknown_pair")
        self.assertEqual(compatibility_assessment(p, "已有 H1000 网关") ["status"], "needs_gateway_model")
        self.assertEqual(compatibility_assessment(self.product("LAB-TAPO-T110"), "已有 H100") ["status"], "unknown_pair")

    def test_missing_gateway_and_direct_wifi_are_distinct(self):
        constraints = query_constraints("推荐不用网关的商品")
        self.assertFalse(hard_match(self.product("LAB-TAPO-T100"), constraints))
        self.assertTrue(hard_match(self.product("LAB-TAPO-L530E"), constraints))
        self.assertEqual(compatibility_assessment(self.product("LAB-TAPO-L530E"), "无需网关") ["status"], "gateway_not_required")

    def test_negated_and_mixed_gateway_mentions_do_not_overpromise(self):
        product = self.product("LAB-TAPO-T100")
        for question in ("我不用 H100，我现在有 M3，T100 能用吗？", "我没有 H100 网关，只有 M3", "H100 不用，换 M3 行吗？"):
            check = compatibility_assessment(product, question)
            self.assertEqual(check["status"], "unknown_pair")
            self.assertEqual(check["requestedGatewayModels"], ["M3"])
        mixed = compatibility_assessment(product, "我有 H100 和 M3，T100 能和这两个网关配对吗？")
        self.assertEqual(mixed["status"], "unknown_pair")
        self.assertEqual(mixed["matchedGatewayModels"], ["H100"])
        self.assertEqual(mixed["unverifiedGatewayModels"], ["M3"])
        self.assertEqual(compatibility_assessment(product, "我有 H100 和 H200，T100 都能用吗？")["status"], "verified_pair")

    def test_unknown_merchant_sku_has_no_manufacturer_claim(self):
        self.assertIsNone(profile_for("MERCHANT-CUSTOM-SKU"))
        self.assertEqual(compatibility_assessment({"code":"MERCHANT-CUSTOM-SKU"}, "H100") ["status"], "unknown")


if __name__ == "__main__":
    unittest.main()
