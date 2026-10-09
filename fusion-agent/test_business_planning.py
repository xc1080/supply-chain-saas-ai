import copy
import unittest

from business_catalog import load_scenario
from business_planning import (prepare_bundle, request_from_message, apply_authoritative_quote,
    alternative_candidates, replenishment_draft, effective_plan_message, merge_clarification, clarification_intent, capability)


def catalog():
    return [{"id": str(index + 1), "code": row["code"], "name": row["name"],
             "price": row["simulation"]["salePrice"], "stock": row["simulation"]["openingStock"],
             "spec": row["spec"], "remark": "", "category": row["category"]}
            for index, row in enumerate(load_scenario()["products"])]


def request(*items, budget=2000, units=1, owned=None):
    return {"items": [{"productId": str(pid), "quantity": quantity} for pid, quantity in items],
            "budget": budget, "units": units, "ownedGatewayModels": owned or [],
            "installationConfirmed": True, "regionConfirmed": True}


def quote(plan, products):
    by_id = {p["id"]: p for p in products}
    items = [{"productId": item["productId"], "quantity": item["quantity"],
              "unitPrice": by_id[item["productId"]]["price"],
              "availableStock": by_id[item["productId"]]["stock"]} for item in plan["items"]]
    possible = min(int(item["availableStock"] // item["quantity"]) for item in items)
    return {"items": items, "totalAmount": sum(item["unitPrice"] * item["quantity"] * plan["units"] for item in items),
            "promisableUnits": possible, "deliveryCapacity": None, "deliveryStatus": "UNKNOWN", "deliveryDate": None}


class BusinessPlanningTests(unittest.TestCase):
    def setUp(self):
        self.catalog = catalog()

    def build(self, raw):
        plan, products = prepare_bundle(raw, self.catalog)
        return apply_authoritative_quote(plan, quote(plan, self.catalog), self.catalog)

    def test_whole_bundle_budget_includes_quantities_and_units(self):
        plan = self.build(request((9, 2), (3, 1), budget=400, units=2, owned=["H100"]))
        expected = (self.catalog[8]["price"] * 2 + self.catalog[2]["price"]) * 2
        self.assertEqual(plan["budget"]["total"], expected)
        self.assertFalse(plan["budget"]["withinBudget"])
        self.assertEqual(plan["status"], "REVIEW_REQUIRED")
        self.assertEqual(plan["items"][0]["lineTotal"], self.catalog[8]["price"] * 4)
        self.assertEqual(plan["request"]["items"][0]["quantity"], 2)

    def test_gateway_is_a_bottleneck_even_when_lamps_are_plentiful(self):
        self.catalog[0]["stock"] = 10
        self.catalog[8]["stock"] = 100
        plan = self.build(request((9, 1), (1, 1), (3, 1), units=12, budget=10000))
        self.assertEqual(plan["inventory"]["promisableUnits"], 10)
        self.assertEqual(plan["inventory"]["shortages"][0]["productId"], "1")
        self.assertFalse(plan["delivery"]["promise"])
        self.assertEqual(plan["delivery"]["status"], "UNKNOWN")

    def test_missing_gateway_and_budget_are_questions_not_made_up_defaults(self):
        raw = request((3, 1), budget=None)
        raw.update(installationConfirmed=False, regionConfirmed=False)
        plan, _ = prepare_bundle(raw, self.catalog)
        self.assertEqual(plan["status"], "NEEDS_INPUT")
        self.assertEqual(plan["compatibility"]["status"], "NEEDS_INPUT")
        self.assertEqual({item["field"] for item in plan["missing"]}, {"budget", "gateway", "installationConfirmed", "regionConfirmed"})

    def test_other_gateway_and_unverified_pairs_are_never_called_compatible(self):
        for pid, gateway in ((3, "M3"), (4, "H100"), (3, "H1000")):
            with self.subTest(pid=pid, gateway=gateway):
                plan = self.build(request((pid, 1), owned=[gateway]))
                self.assertNotEqual(plan["compatibility"]["status"], "COMPATIBLE")
                self.assertNotEqual(plan["status"], "READY_FOR_REVIEW")

    def test_verified_exact_pair_can_be_reviewed_but_does_not_promise_date(self):
        plan = self.build(request((3, 1), (9, 2), owned=["H100"]))
        self.assertEqual(plan["compatibility"]["status"], "COMPATIBLE")
        self.assertEqual(plan["status"], "READY_FOR_REVIEW")
        self.assertFalse(plan["executable"])
        self.assertTrue(plan["requiresApproval"])
        self.assertFalse(plan["delivery"]["promise"])

    def test_mixed_known_and_unknown_gateway_does_not_hide_unverified_pair(self):
        plan = self.build(request((3, 1), owned=["H100", "M3"]))
        self.assertEqual(plan["compatibility"]["status"], "UNKNOWN")
        self.assertEqual(plan["compatibility"]["checks"][0]["unverifiedGatewayModels"], ["M3"])

    def test_different_sensor_functions_are_not_substitutes(self):
        self.catalog[2]["stock"] = 0
        plan = self.build(request((3, 1), owned=["H100"]))
        self.assertEqual(alternative_candidates(plan, self.catalog), [])
        self.assertEqual(capability(self.catalog[2]), "motion")
        self.assertEqual(capability(self.catalog[3]), "contact")

    def test_same_function_alternative_keeps_differences_and_requires_approval(self):
        self.catalog[8]["stock"] = 0
        plan = self.build(request((9, 1)))
        alternatives = alternative_candidates(plan, self.catalog)
        self.assertEqual(len(alternatives), 1)
        self.assertEqual(alternatives[0]["productId"], "10")
        self.assertTrue(alternatives[0]["requiresApproval"])
        self.assertIn("不自动替换", alternatives[0]["reason"])
        self.assertTrue(alternatives[0]["differences"])
        self.assertEqual(plan["items"][0]["productId"], "9")

    def test_tampered_quote_total_quantity_stock_and_overpromise_rejected(self):
        plan, _ = prepare_bundle(request((3, 1), owned=["H100"]), self.catalog)
        for mutate in (
            lambda value: value.update(totalAmount=0),
            lambda value: value["items"][0].update(quantity=2),
            lambda value: value["items"][0].update(availableStock=-1),
            lambda value: value.update(promisableUnits=1000),
            lambda value: value.update(items=[]),
            lambda value: value.update(deliveryCapacity=1, promisableUnits=2),
        ):
            value = quote(plan, self.catalog)
            mutate(value)
            with self.assertRaises(ValueError):
                apply_authoritative_quote(copy.deepcopy(plan), value, self.catalog)

    def test_unauthorized_missing_product_and_duplicate_items_rejected(self):
        for raw in (request((999, 1)), request((3, 1), (3, 1)), request((3, True)), request((3, 1), units=0)):
            with self.assertRaises(ValueError):
                prepare_bundle(raw, self.catalog)

    def test_text_parser_preserves_quantities_and_does_not_buy_owned_gateway(self):
        raw = request_from_message("卧室配齐2个L530E、1个T100，总预算600元，我已有H100网关", self.catalog)
        self.assertEqual(raw["items"], [{"productId": "3", "quantity": 1}, {"productId": "9", "quantity": 2}])
        self.assertEqual(raw["ownedGatewayModels"], ["H100"])
        self.assertEqual(raw["budget"], 600)
        self.assertNotIn("1", [item["productId"] for item in raw["items"]])

    def test_unspecified_whole_room_does_not_invent_a_bill_of_materials(self):
        raw = request_from_message("卧室配齐智能家居，预算1000", self.catalog)
        self.assertEqual(raw["items"], [])
        self.assertEqual(raw["requirements"], [])
        self.assertIn("requirements", [item["field"] for item in raw["missing"]])

    def test_unspecified_sensor_function_cannot_be_silently_dropped_from_bundle(self):
        raw = request_from_message("卧室配齐2个灯、1个传感器，预算1000，英国地区适用，已确认安装", self.catalog)
        self.assertIn("sensorPurpose", [item["field"] for item in raw["missing"]])
        plan, _ = prepare_bundle(raw, self.catalog)
        self.assertEqual(plan["status"], "NEEDS_INPUT")

    def test_followup_retains_original_quantities_but_latest_budget_wins(self):
        effective = effective_plan_message("预算改成800，已核对安装条件，英国地区适用", [
            {"role": "user", "content": "卧室配齐2个L530E、1个T100，预算500，我已有H100网关"}])
        raw = request_from_message(effective, self.catalog)
        # Explicit updates with 改成 are recognized as a fresh budget condition.
        self.assertEqual(raw["items"][-1]["quantity"], 2)
        self.assertEqual(raw["budget"], 800)
        self.assertTrue(raw["installationConfirmed"])
        self.assertTrue(raw["regionConfirmed"])

    def test_changed_gateway_does_not_reuse_previous_verified_pair(self):
        effective = effective_plan_message("网关换成M3，不用H100，安装已确认，英国地区适用", [
            {"role": "user", "content": "卧室配齐1个T100，预算500，我已有H100网关"}])
        raw = request_from_message(effective, self.catalog)
        self.assertEqual(raw["ownedGatewayModels"], ["M3"])
        plan, _ = prepare_bundle(raw, self.catalog)
        self.assertEqual(plan["compatibility"]["status"], "UNKNOWN")
        raw = request_from_message("卧室配齐1个T100，我已有H100网关；H100不用了，网关换成M3，预算500", self.catalog)
        self.assertEqual(raw["ownedGatewayModels"], ["M3"])

    def test_more_than_three_clarifications_preserve_server_draft_without_history(self):
        plan, _ = prepare_bundle(request_from_message("卧室配齐智能家居", self.catalog), self.catalog)
        for message in ("预算1000元", "2个L530E，1个T100", "我已有H100网关", "英国地区适用", "已确认安装条件"):
            raw = merge_clarification(plan["request"], message, self.catalog)
            plan, _ = prepare_bundle(raw, self.catalog)
        plan = apply_authoritative_quote(plan, quote(plan, self.catalog), self.catalog)
        self.assertEqual(plan["status"], "READY_FOR_REVIEW")
        self.assertEqual(plan["request"]["ownedGatewayModels"], ["H100"])
        self.assertEqual(plan["request"]["items"], [{"productId": "3", "quantity": 1}, {"productId": "9", "quantity": 2}])

    def test_explicit_replacement_requotes_new_device_without_keeping_old_one(self):
        previous = request((9, 2))
        merged = merge_clarification(previous, "换成2个L510E", self.catalog)
        self.assertEqual(merged["items"], [{"productId": "10", "quantity": 2}])

    def test_installation_confirmation_can_be_revoked_by_new_information(self):
        previous = request((9, 1))
        merged = merge_clarification(previous, "安装未核对", self.catalog)
        self.assertFalse(merged["installationConfirmed"])

    def test_bare_budget_reply_uses_pending_field_but_installation_question_is_not_an_update(self):
        previous = request((9, 1), budget=None)
        merged = merge_clarification(previous, "800元", self.catalog)
        self.assertEqual(merged["budget"], 800)
        self.assertFalse(clarification_intent("这个灯安装难不难？"))

    def test_gateway_pair_evidence_does_not_satisfy_an_unverified_platform_requirement(self):
        raw = request((3, 1), owned=["H100"])
        raw["requestedPlatforms"] = ["HomeKit"]
        plan = self.build(raw)
        self.assertEqual(plan["compatibility"]["status"], "COMPATIBLE")
        self.assertEqual(plan["compatibility"]["scope"], "gateway_model_pairs")
        self.assertEqual(plan["status"], "NEEDS_INPUT")
        self.assertIn("platformVerification", [item["field"] for item in plan["missing"]])

    def test_customer_cannot_obtain_merchant_replenishment_and_private_fields_never_survive(self):
        raw = {"items": [{"productId": 1, "availableStock": 2, "incomingStock": None,
                          "incomingKnown": False, "suggestedQuantity": 10,
                          "costPrice": 99, "supplierPhone": "SECRET-PHONE", "supplierName": "PRIVATE"}],
               "secret": "SECRET"}
        with self.assertRaises(PermissionError):
            replenishment_draft(raw, channel="customer")
        draft = replenishment_draft(raw, channel="workspace")
        self.assertNotIn("SECRET", str(draft))
        self.assertNotIn("costPrice", str(draft))
        self.assertFalse(draft["items"][0]["incomingKnown"])
        self.assertIsNone(draft["items"][0]["incomingStock"])
        self.assertFalse(draft["executable"])


if __name__ == "__main__":
    unittest.main()
