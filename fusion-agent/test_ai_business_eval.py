import unittest
from eval_ai_business import evaluate


class BusinessEvaluationTests(unittest.IsolatedAsyncioTestCase):
    async def test_fixed_business_and_adversarial_cases(self):
        result=await evaluate()
        self.assertEqual(result['total'],7)
        self.assertEqual(result['passed'],result['total'],result)
        self.assertFalse(result['liveModel'])


if __name__=='__main__':unittest.main()
