"""Existing gateway context must not become a requested product constraint."""
import unittest

from business_catalog import load_scenario
from retrieval import query_constraints, rank_products


class GatewayContextTests(unittest.TestCase):
    def test_owned_gateway_protocol_does_not_filter_target_in_prefix_or_suffix(self):
        for gateway in ("H100", "Zigbee", "Wi-Fi"):
            for query in (f"我已有 {gateway} 网关，想买人体传感器",
                          f"想买人体传感器，我已有 {gateway} 网关",
                          f"我目前已经有 {gateway} 网关。推荐人体传感器",
                          f"推荐人体传感器，我家有 {gateway} 网关"):
                with self.subTest(query=query):
                    constraints = query_constraints(query)
                    self.assertEqual(constraints["topics"], ["传感器"])
                    self.assertEqual(constraints["protocols"], [])
                    self.assertFalse(constraints["no_gateway"])

    def test_requested_protocol_is_preserved_separately_from_owned_equipment(self):
        cases = (
            ("我已有 Wi-Fi 网关，想买 Zigbee 人体传感器", ["zigbee"], []),
            ("想买 Zigbee 人体传感器，我已有 Wi-Fi 网关", ["zigbee"], []),
            ("我已有 Zigbee 网关，推荐 Wi-Fi 人体传感器", ["wifi"], []),
            ("推荐 Thread 传感器，我已有 Zigbee 网关", ["thread"], []),
            ("我有 Wi-Fi 网关，推荐传感器，不要 Zigbee", [], ["zigbee"]),
            ("推荐不要 Wi-Fi 的传感器，我家有 Zigbee 网关", [], ["wifi"]),
        )
        for query, required, excluded in cases:
            with self.subTest(query=query):
                constraints = query_constraints(query)
                self.assertEqual(constraints["protocols"], required)
                self.assertEqual(constraints["excluded_protocols"], excluded)
                self.assertEqual(constraints["topics"], ["传感器"])

    def test_ownership_without_comma_and_gateway_question_keep_sensor_target(self):
        for query in ("我已有 Wi-Fi 网关想买人体传感器", "H100 网关能配哪些传感器",
                      "H100 支持哪些温湿度传感器"):
            with self.subTest(query=query):
                constraints = query_constraints(query)
                self.assertEqual(constraints["topics"], ["传感器"])
                self.assertEqual(constraints["protocols"], [])

    def test_absent_gateway_still_filters_for_direct_connection(self):
        constraints = query_constraints("我没有 Zigbee 网关，推荐 Wi-Fi 灯泡")
        self.assertTrue(constraints["no_gateway"])
        self.assertEqual(constraints["protocols"], ["wifi"])
        self.assertEqual(constraints["topics"], ["灯"])

    def test_catalog_sensor_request_never_returns_energy_monitoring_plug(self):
        products = [{"id": row["code"], "code": row["code"], "name": row["name"],
                     "category": row["category"], "spec": row["spec"], "remark": "",
                     "price": 79, "stock": 2} for row in load_scenario()["products"]]
        for query in ("我已有 Wi-Fi 网关，推荐人体传感器", "推荐人体传感器，我已有 Wi-Fi 网关",
                      "已有 H100 网关，推荐人体传感器"):
            with self.subTest(query=query):
                result = rank_products(query, products)
                self.assertIn("LAB-TAPO-T100", {row["code"] for row in result})
                self.assertTrue(all(row["category"] == "智能传感器" for row in result))
                self.assertNotIn("LAB-TAPO-P110", {row["code"] for row in result})


if __name__ == "__main__":
    unittest.main()
