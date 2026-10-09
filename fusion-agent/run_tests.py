"""Run offline tests with isolated SQLite/Redis state and no real provider keys."""
import os
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import redis


def run():
    identity=uuid.uuid4().hex
    ai_prefix='fusion:test:suite-ai:'+identity
    store_prefix='fusion:test:suite-store:'+identity
    with tempfile.TemporaryDirectory(prefix='fusion-suite-') as directory:
        env={'FUSION_AGENT_DB':str(Path(directory)/'runs.sqlite3'),
             'FUSION_STORE_DB':str(Path(directory)/'store.sqlite3'),
             'FUSION_AI_METRICS_DB':str(Path(directory)/'metrics.sqlite3'),
             'FUSION_AI_KEY_PREFIX':ai_prefix,'FUSION_STORE_KEY_PREFIX':store_prefix,
             'FUSION_CUSTOMER_ASSERTION_SECRET':'test-assertion-secret-only-32-characters',
             'FUSION_LLM_KEY':'','AI_BAILIAN_API_KEY':'','DEEPSEEK_API_KEY':'','FUSION_EMBEDDING_KEY':''}
        with patch.dict(os.environ,env):
            try:
                suite=unittest.defaultTestLoader.discover(str(Path(__file__).parent),pattern='test_*.py')
                result=unittest.TextTestRunner(verbosity=2).run(suite)
                return 0 if result.wasSuccessful() else 1
            finally:
                client=redis.Redis.from_url(os.getenv('FUSION_REDIS_URL','redis://127.0.0.1:6379/0'))
                try:
                    for prefix in (ai_prefix,store_prefix):
                        keys=list(client.scan_iter(match=prefix+':*'))
                        if keys:client.delete(*keys)
                finally:client.close()


if __name__=='__main__':raise SystemExit(run())
