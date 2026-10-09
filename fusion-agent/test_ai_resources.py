"""Real Redis isolation, bounded waiting, token settlement and provider errors."""
import asyncio
import os
import secrets
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx
import redis
from ai_resources import AIResources, ResourceUnavailable, ai_scope, provider_post


class AIResourceTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.prefix='fusion:test:ai:'+secrets.token_hex(8)
        self.patch=patch.dict(os.environ, {'FUSION_AI_KEY_PREFIX':self.prefix,'FUSION_AI_GLOBAL_CONCURRENCY':'1',
            'FUSION_AI_TENANT_CONCURRENCY':'1','FUSION_AI_GLOBAL_WAITING':'1','FUSION_AI_TENANT_WAITING':'1',
            'FUSION_AI_WAIT_SECONDS':'0.15','FUSION_AI_GLOBAL_DAILY_TOKENS':'1000',
            'FUSION_AI_TENANT_DAILY_TOKENS':'500','FUSION_AI_METRICS_DB':str(Path(self.temp.name)/'metrics.sqlite3')})
        self.patch.start()
        self.redis=redis.Redis.from_url(os.getenv('FUSION_REDIS_URL','redis://127.0.0.1:6379/0'),decode_responses=True)
        self.redis.ping()
    def tearDown(self):
        keys=list(self.redis.scan_iter(match=self.prefix+':*'))
        if keys:self.redis.delete(*keys)
        self.redis.close();self.patch.stop();self.temp.cleanup()
    async def test_global_concurrency_waits_and_releases_without_session_bypass(self):
        first=AIResources('demo');second=AIResources('demo')
        await first.acquire()
        with self.assertRaises(ResourceUnavailable):await second.acquire()
        self.assertEqual(self.redis.zcard(first.keys[0]),1)
        await second.close();await first.close()
        third=AIResources('studio')
        await third.acquire();await third.close()
    async def test_queue_capacity_is_bounded(self):
        active=AIResources('demo');waiting=AIResources('demo');overflow=AIResources('studio')
        await active.acquire()
        task=asyncio.create_task(waiting.acquire());await asyncio.sleep(.03)
        with self.assertRaises(ResourceUnavailable):await overflow.acquire()
        await active.close();await task
        await waiting.close();await overflow.close()
    async def test_tenant_daily_budget_is_shared_and_other_tenant_independent(self):
        first=AIResources('demo');await first.acquire();await first.reserve(400);await first.close()
        other_owner=AIResources('demo');await other_owner.acquire()
        with self.assertRaises(ResourceUnavailable):await other_owner.reserve(101)
        await other_owner.close()
        studio=AIResources('studio');await studio.acquire();await studio.reserve(100);await studio.close()
    async def test_usage_settlement_and_429_do_not_charge_success_tokens(self):
        def handle(request):
            return httpx.Response(429 if request.url.path=='/limited' else 200,json={'usage':{'total_tokens':5},'choices':[]})
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            async with ai_scope('demo') as resources:
                await provider_post(client,'https://provider.test/ok',headers={},json={'messages':[{'content':'测试'}],'max_tokens':10},operation='test')
                self.assertEqual(int(self.redis.get(resources.budget_keys()[1])),5)
                with self.assertRaises(httpx.HTTPStatusError):
                    await provider_post(client,'https://provider.test/limited',headers={},json={'input':['test']},operation='test')
                self.assertEqual(int(self.redis.get(resources.budget_keys()[1])),5)
    async def test_redis_unavailable_fails_closed_before_provider(self):
        with patch.dict(os.environ,{'FUSION_REDIS_URL':'redis://127.0.0.1:1/0'}):
            resources=AIResources('demo')
            with self.assertRaises(ResourceUnavailable):await resources.acquire()
            await resources.close()


if __name__=='__main__':unittest.main()
