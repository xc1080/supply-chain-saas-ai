import unittest
from eval_ai_business import evaluate


class BusinessEvaluationTests(unittest.IsolatedAsyncioTestCase):
    async def test_fixed_business_and_adversarial_cases(self):
        result=await evaluate()
        self.assertEqual(result['total'],14)
        self.assertEqual(result['passed'],result['total'],result)
        self.assertFalse(result['liveModel'])
        self.assertEqual(result['versions']['schemaVersion'],1)
        self.assertEqual(len(result['evaluationVersion']),64)
        self.assertEqual({row['category'] for row in result['cases']},{'business','retrieval','answer'})


if __name__=='__main__':unittest.main()
