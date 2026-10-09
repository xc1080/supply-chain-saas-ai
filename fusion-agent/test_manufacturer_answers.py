import json
import unittest
import main
from business_catalog import manufacturer_source


class ManufacturerAnswerTests(unittest.TestCase):
    def state(self, question):
        product = {"id":"11", "code":"LAB-TAPO-T100", "name":"Tapo T100 人体移动传感器",
                   "spec":"SubGHz/UK", "remark":"业务样例，价格和库存模拟", "price":89, "stock":30}
        return {"message":question, "products":[product], "sources":[{"id":"P-11","title":product["name"],"content":"实时业务查询"}, manufacturer_source(product)],
                "history":[],"trace":[],"context":{}}

    def parse(self, question, answer, citations=None):
        state = self.state(question)
        return main.parse_model_answer(json.dumps({"answer":answer,"citations":citations or ["P-11","M-LAB-TAPO-T100"]}, ensure_ascii=False), state, state["sources"])

    def test_unknown_pair_cannot_be_claimed_compatible_or_incompatible(self):
        for claim in ("该传感器兼容你已有的网关。", "该传感器不兼容你已有的网关。", "该传感器可以直接接入你的网关。"):
            with self.assertRaises(ValueError):
                self.parse("T100 可以配合已有 M3 网关吗？", claim)

    def test_known_gateway_model_is_allowed_in_an_honest_unknown_answer(self):
        answer, _ = self.parse("T100 可以配合已有 M3 网关吗？", "不能确认 T100 与 M3 的兼容性，需要核对具体型号资料。")
        self.assertIn("不能确认", answer)

    def test_verified_pair_needs_manufacturer_citation(self):
        self.parse("T100 能搭配 H100 吗？", "T100 与 H100 的组合在厂商资料中列明兼容。")
        with self.assertRaises(ValueError):
            self.parse("T100 能搭配 H100 吗？", "T100 与 H100 兼容。", ["P-11"])

    def test_model_names_never_allow_invented_prices(self):
        with self.assertRaises(ValueError):
            self.parse("T100 能搭配 H100 吗？", "T100 与 H100 兼容，售价 999 元。")

    def test_internal_verification_labels_are_not_customer_prose(self):
        with self.assertRaises(ValueError):
            self.parse("T100 能搭配 H100 吗？", "T100 与 H100 兼容，状态是 verified_pair。")

    def test_negated_and_mixed_gateway_answers_cannot_claim_everything_works(self):
        for question in ("我不用 H100，只有 M3，T100 能用吗？", "T100 能配合 H100 和 M3 吗？"):
            with self.assertRaises(ValueError):
                self.parse(question, "T100 兼容你的网关。")

    def test_sourced_technical_rating_does_not_authorize_bare_price(self):
        state = self.state("P110 的额定电流是什么？")
        product = {**state["products"][0], "id":"16", "code":"LAB-TAPO-P110", "name":"Tapo P110 智能插座", "spec":"WiFi/UK"}
        state["products"] = [product]
        state["sources"] = [manufacturer_source(product)]
        payload = lambda answer: json.dumps({"answer": answer, "citations":["M-LAB-TAPO-P110"]}, ensure_ascii=False)
        main.parse_model_answer(payload("P110 的 UK 型号资料列出最大负载 13A。"), state, state["sources"])
        for answer in ("P110 售价 13 元。", "P110 最大负载 16A。"):
            with self.assertRaises(ValueError):
                main.parse_model_answer(payload(answer), state, state["sources"])


if __name__ == "__main__":
    unittest.main()
