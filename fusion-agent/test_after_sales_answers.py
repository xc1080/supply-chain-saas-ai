import json
import unittest

import main


class AfterSalesAnswerTests(unittest.TestCase):
    def state(self, after_sales, refunded=0):
        return {"message": "我的退款进度怎么样？", "products": [], "sources": [], "history": [], "trace": [],
                "context": {"channel": "customer", "orders": [{"id": "SC-TEST", "status": "PAID", "totalAmount": 79,
                           "items": [], "afterSalesStatus": after_sales, "refundedAmount": refunded}]}}

    def test_refund_and_return_slots_override_stale_fulfillment_status(self):
        for status, expected in (("REFUNDED", "退款完成"), ("AWAITING_RETURN", "验收退货"), ("RETURN_RECEIVED", "等待沙箱退款")):
            state = self.state(status, 79 if status == "REFUNDED" else 0)
            answer = main.deterministic_answer(state)
            self.assertIn(expected, answer)
            self.assertNotIn("待发货", answer)
            self.assertIn(expected, main.fact_slots(state)["{{order_status:O-SC-TEST}}"])
            self.assertIn(expected, main.answer_sources(state)[0]["content"])

    def test_model_refunded_amount_requires_verified_refund_not_order_total(self):
        state = self.state("REFUNDED", 79)
        answer, _ = main.parse_model_answer(json.dumps({"answer": "状态为 {{order_status:O-SC-TEST}}，沙箱退款金额 {{order_refunded:O-SC-TEST}}。", "citations": ["O-SC-TEST"]}), state, main.answer_sources(state))
        self.assertIn("79 元", answer)
        self.assertNotIn("待发货", answer)
        unrefunded = self.state("APPROVED")
        self.assertNotIn("{{order_refunded:O-SC-TEST}}", main.fact_slots(unrefunded))
        with self.assertRaises(ValueError):
            main.parse_model_answer(json.dumps({"answer": "已退 {{order_refunded:O-SC-TEST}}。", "citations": ["O-SC-TEST"]}), unrefunded, main.answer_sources(unrefunded))


if __name__ == "__main__":
    unittest.main()
