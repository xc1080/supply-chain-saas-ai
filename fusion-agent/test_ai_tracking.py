"""Correlated, content-free telemetry with isolated SQLite and fake providers."""
import asyncio
import copy
import json
import os
import sqlite3
import tempfile
import time
import unittest
from contextlib import asynccontextmanager, closing
from pathlib import Path
from unittest.mock import AsyncMock, patch

import httpx
import main
import planner
import ai_resources
from ai_resources import CURRENT, TRACKING, metric, tracking_scope, tracking_event
from ai_versions import version_manifest

PRODUCT = {'id':'1','code':'DEMO-LAMP-WIFI','name':'智能灯','spec':'WiFi/12W','price':129,'stock':3,'remark':'WiFi直连'}


class FakeResources:
    def __init__(self, tenant): self.tenant, self.charged = tenant, 0
    async def reserve(self, amount): self.charged += amount; return ['global','tenant']
    async def call(self, script, keys, delta): self.charged += delta; return 1


@asynccontextmanager
async def fake_admission(tenant, *, enabled=True):
    token = CURRENT.set(FakeResources(tenant))
    try: yield CURRENT.get()
    finally: CURRENT.reset(token)


class OfflineService:
    @staticmethod
    def llm_key(): return ''
    @staticmethod
    async def retrieve(state): return {'products':state['catalog'],'sources':[],'mode':{'llm':'local'}}
    @staticmethod
    async def answer(state): return {'answer':'已核验','citations':[]}


class TrackingTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = Path(self.temp.name)/'metrics.sqlite3'
        self.env = patch.dict(os.environ, {'FUSION_AI_METRICS_DB':str(self.db),
            'FUSION_AGENT_DB':str(Path(self.temp.name)/'runs.sqlite3'), 'FUSION_LLM_KEY':'',
            'AI_BAILIAN_API_KEY':'','DEEPSEEK_API_KEY':'','FUSION_EMBEDDING_KEY':'','FUSION_DISABLED_TOOLS':''})
        self.env.start()
    def tearDown(self): self.env.stop(); self.temp.cleanup()
    def rows(self):
        with closing(sqlite3.connect(self.db)) as connection:
            connection.row_factory = sqlite3.Row
            return [dict(row) for row in connection.execute('SELECT * FROM ai_metrics ORDER BY id')]
    async def fetch(self,state): return {'catalog':[copy.deepcopy(PRODUCT)]}

    def test_legacy_table_migration_preserves_rows_and_explicit_old_inserts(self):
        with closing(sqlite3.connect(self.db)) as connection, connection:
            connection.execute('CREATE TABLE ai_metrics(id INTEGER PRIMARY KEY,created REAL NOT NULL,tenant TEXT NOT NULL,operation TEXT NOT NULL,outcome TEXT NOT NULL,elapsed REAL NOT NULL,tokens INTEGER NOT NULL,status INTEGER)')
            connection.execute("INSERT INTO ai_metrics(created,tenant,operation,outcome,elapsed,tokens,status) VALUES(?,'demo','old','ok',1,7,200)",(time.time(),))
        with tracking_scope('demo',run_id='migration'):
            metric('demo','test','ok')
        with closing(sqlite3.connect(self.db)) as connection, connection:
            connection.execute("INSERT INTO ai_metrics(created,tenant,operation,outcome,elapsed,tokens,status) VALUES(2,'demo','old','ok',2,8,200)")
        rows=self.rows()
        self.assertEqual([row['tokens'] for row in rows],[7,0,8])
        self.assertEqual(rows[0]['run_id'],''); self.assertIsNone(rows[0]['input_tokens'])
        self.assertEqual(rows[1]['run_id'],'migration'); self.assertEqual(rows[2]['versions'],'{}')

    async def test_async_request_contexts_and_nested_scope_reset(self):
        async def worker(tenant,identity):
            with tracking_scope(tenant,run_id=identity):
                await asyncio.sleep(.01)
                tracking_event('tool','completed',kind='tool')
                with tracking_scope(tenant,run_id=identity+'-nested'):
                    tracking_event('tool','completed',kind='tool')
                self.assertEqual(TRACKING.get()['runId'],identity)
        await asyncio.gather(worker('demo','first'),worker('studio','second'))
        self.assertIsNone(TRACKING.get())
        self.assertEqual({(row['tenant'],row['run_id']) for row in self.rows()},
                         {('demo','first'),('demo','first-nested'),('studio','second'),('studio','second-nested')})

    def test_versions_exclude_credentials_and_invalidate_only_public_contract_changes(self):
        manifests=[]
        for credential in ('secret-one','secret-two'):
            with patch.dict(os.environ,{'FUSION_LLM_KEY':credential,'FUSION_LLM_BASE_URL':f'https://user:{credential}@models.test/v1?key={credential}'}):
                manifests.append(version_manifest())
        self.assertEqual(manifests[0],manifests[1])
        self.assertNotIn('secret',json.dumps(manifests)); self.assertNotIn('models.test',json.dumps(manifests))
        import business_tools
        catalog=copy.deepcopy(business_tools.TOOL_CATALOG); catalog['read_order_settlement']['version']+=1
        with patch.object(business_tools,'TOOL_CATALOG',catalog): changed=version_manifest()
        self.assertNotEqual(changed['tools']['catalogVersion'],version_manifest()['tools']['catalogVersion'])
        self.assertEqual(changed['knowledge'],version_manifest()['knowledge'])

    def test_summary_allowlist_drops_content_secrets_and_personal_evidence(self):
        with tracking_scope('demo',run_id='privacy'):
            tracking_event('tool','completed',summary={'items':2,'question':'PRIVATE_QUERY','prompt':'PRIVATE_PROMPT',
                'phone':'13812345678','authorization':'Bearer PRIVATE_SECRET','evidence':{'address':'PRIVATE_ADDRESS'},
                'error_type':'PRIVATE_ERROR_BODY'})
        encoded=json.dumps(self.rows())
        for secret in ('PRIVATE_QUERY','PRIVATE_PROMPT','13812345678','PRIVATE_SECRET','PRIVATE_ADDRESS','PRIVATE_ERROR_BODY'):
            self.assertNotIn(secret,encoded)
        self.assertEqual(json.loads(self.rows()[0]['summary']),{'items':2})

    async def model_run(self, *, repaired=True):
        answers=0
        def respond(request):
            nonlocal answers
            body=json.loads(request.content)
            if body['max_tokens']==220:
                context=json.loads(body['messages'][1]['content'])
                content={'tool':context['available_tools'][0],'reason':'核验事实'}
            else:
                answers+=1
                content={'answer':'智能灯售价999元。','citations':['P-1']}
                if repaired and answers>1:
                    content={'answer':'智能灯参考售价为 {{price:P-1}}。','citations':['P-1']}
            return httpx.Response(200,json={'choices':[{'message':{'content':json.dumps(content,ensure_ascii=False)}}],
                                           'usage':{'prompt_tokens':7,'completion_tokens':5,'total_tokens':12}})
        client_type=httpx.AsyncClient
        with patch.dict(os.environ,{'FUSION_LLM_KEY':'PRIVATE_SECRET'}), patch.object(planner,'ai_scope',fake_admission), \
                patch.object(main.httpx,'AsyncClient',side_effect=lambda *args,**kwargs:client_type(transport=httpx.MockTransport(respond),**kwargs)):
            return await planner.run_agent(main,{'message':'智能灯多少钱？PRIVATE_QUERY 13812345678 private@example.test',
                'authorization':'Bearer PRIVATE_SECRET','context':{'channel':'customer'}},'demo','owner',self.fetch)

    async def test_provider_success_validation_repair_and_tools_share_run_and_call(self):
        result=await self.model_run()
        rows=self.rows(); self.assertEqual({row['run_id'] for row in rows},{result['runId']})
        self.assertEqual(result['mode']['llm'],'online'); self.assertIn('129 元',result['answer'])
        providers=[row for row in rows if row['kind']=='provider']
        self.assertTrue(all(row['input_tokens']==7 and row['output_tokens']==5 and row['tokens']==12 for row in providers))
        self.assertTrue(all(row['usage_source']=='provider' and row['model'] for row in providers))
        rejected=next(row for row in rows if row['operation']=='answer_validation' and row['outcome']=='rejected')
        repaired=next(row for row in rows if row['outcome']=='repair')
        self.assertEqual(rejected['call_id'],repaired['call_id'])
        self.assertIn(rejected['call_id'],{row['call_id'] for row in providers if row['operation']=='answer' and row['outcome']=='ok'})
        accepted=next(row for row in rows if row['operation']=='answer_validation' and row['outcome']=='accepted')
        self.assertNotEqual(rejected['call_id'],accepted['call_id'])
        self.assertEqual(accepted['prompt_version'],result['versions']['prompts']['answer'])
        self.assertTrue(any(row['kind']=='tool' and row['outcome']=='completed' and row['tool_version'] for row in rows))
        self.assertTrue(any(row['kind']=='planning' for row in rows))
        self.assertEqual(sum(row['tokens'] for row in providers),12*len(providers))
        for text in ('PRIVATE_QUERY','PRIVATE_SECRET','13812345678','private@example.test','智能灯售价999元'):
            self.assertNotIn(text,json.dumps(rows,ensure_ascii=False))
        self.assertIsNone(TRACKING.get()); self.assertIsNone(CURRENT.get())

    async def test_two_invalid_successful_calls_record_rejections_and_final_fallback(self):
        result=await self.model_run(repaired=False)
        self.assertEqual(result['mode']['llm'],'local')
        rows=self.rows()
        providers=[row for row in rows if row['kind']=='provider' and row['operation']=='answer']
        self.assertEqual([row['outcome'] for row in providers],['ok','ok'])
        rejected=[row for row in rows if row['operation']=='answer_validation' and row['outcome']=='rejected']
        self.assertEqual(len(rejected),2)
        fallback=next(row for row in rows if row['operation']=='answer' and row['outcome']=='fallback')
        self.assertEqual(fallback['call_id'],providers[-1]['call_id'])

    async def test_completed_cache_preserves_original_manifest_and_new_access_version(self):
        result=await planner.run_agent(OfflineService(),{'message':'推荐智能灯','context':{'channel':'customer'}},'demo','owner',self.fetch)
        with patch.dict(os.environ,{'FUSION_LLM_MODEL':'new-test-model'}):
            restored=await planner.run_agent(OfflineService(),{'message':'推荐智能灯','context':{'channel':'customer'}},'demo','owner',self.fetch,run_id=result['runId'])
        self.assertEqual(restored['versions'],result['versions'])
        cached=next(row for row in self.rows() if row['outcome']=='cached')
        self.assertEqual(json.loads(cached['versions'])['models']['llm']['model'],'new-test-model')

    async def test_guarded_tool_selection_and_failed_tool_have_distinct_events(self):
        async def illegal(*args): return {'tool':'pay_order','reason':'PRIVATE_QUERY','mode':'model'}
        result=await planner.run_agent(OfflineService(),{'message':'推荐智能灯'},'demo','owner',self.fetch,chooser=illegal)
        self.assertNotIn('pay_order',result['completed'])
        self.assertNotIn('PRIVATE_QUERY',json.dumps(result['trace']))
        self.assertTrue(any(row['outcome']=='guarded' and row['kind']=='validation' for row in self.rows()))
        async def unavailable(state): raise RuntimeError('Bearer PRIVATE_SECRET PRIVATE_ERROR_BODY')
        with self.assertRaises(planner.AgentExecutionError):
            await planner.run_agent(OfflineService(),{'message':'推荐智能灯'},'demo','owner',unavailable,chooser=illegal)
        self.assertTrue(any(row['outcome']=='failed' and row['kind']=='tool' for row in self.rows()))
        self.assertNotIn('PRIVATE_SECRET',json.dumps(self.rows()))

    async def test_missing_usage_and_ambiguous_timeout_are_labelled_estimates(self):
        def reply(request):
            if request.url.path=='/timeout': raise httpx.ReadTimeout('PRIVATE_PROVIDER_ERROR',request=request)
            return httpx.Response(200,json={'choices':[]})
        with tracking_scope('demo',run_id='usage'), patch.object(ai_resources,'CURRENT') as current:
            resources=FakeResources('demo'); current.get.return_value=resources
            async with httpx.AsyncClient(transport=httpx.MockTransport(reply)) as client:
                await ai_resources.provider_post(client,'https://provider.test/missing',headers={},json={'input':['PRIVATE_DOCUMENT'],'model':'test-model'},operation='embedding')
                with self.assertRaises(httpx.ReadTimeout):
                    await ai_resources.provider_post(client,'https://provider.test/timeout',headers={},json={'input':['PRIVATE_DOCUMENT'],'model':'test-model'},operation='embedding')
        rows=self.rows()
        self.assertEqual([row['usage_source'] for row in rows],['estimated','estimated'])
        self.assertEqual([row['outcome'] for row in rows],['ok','transport_error'])
        self.assertTrue(all(row['tokens']>0 and row['input_tokens'] is None and row['output_tokens'] is None for row in rows))
        self.assertNotIn('PRIVATE_DOCUMENT',json.dumps(rows)); self.assertNotIn('PRIVATE_PROVIDER_ERROR',json.dumps(rows))

    async def test_provider_429_is_correlated_and_not_charged_as_success(self):
        with tracking_scope('demo',run_id='rate-limit'), patch.object(ai_resources,'CURRENT') as current:
            resources=FakeResources('demo'); current.get.return_value=resources
            async with httpx.AsyncClient(transport=httpx.MockTransport(lambda request:httpx.Response(429,json={'error':'PRIVATE_PROVIDER_ERROR'}))) as client:
                with self.assertRaises(httpx.HTTPStatusError):
                    await ai_resources.provider_post(client,'https://provider.test/limited',headers={'Authorization':'Bearer PRIVATE_SECRET'},json={'input':['PRIVATE_DOCUMENT']},operation='embedding')
        row=self.rows()[0]
        self.assertEqual((row['run_id'],row['outcome'],row['status'],row['tokens'],row['usage_source']),('rate-limit','rate_limited',429,0,'none'))
        self.assertEqual(resources.charged,0)

    async def test_procurement_success_response_can_still_fail_business_validation(self):
        from business_tasks import propose_targets
        from test_business_tasks import business_plan, service
        from fastapi import HTTPException
        response=httpx.Response(200,request=httpx.Request('POST','https://provider.test'),json={
            'choices':[{'message':{'content':json.dumps({'items':[{'productId':8,'quantity':1}]})}}]})
        response.extensions['ai_tracking']={'call_id':'proposal-call','provider':'provider.test','model':'test-model'}
        with tracking_scope('demo',run_id='proposal'), patch('business_tasks.provider_post',AsyncMock(return_value=response)):
            with self.assertRaises(HTTPException) as caught:
                await propose_targets(service('PRIVATE_SECRET'),'补货 PRIVATE_QUERY',business_plan())
        self.assertEqual(caught.exception.status_code,502)
        row=self.rows()[0]
        self.assertEqual((row['run_id'],row['call_id'],row['operation'],row['outcome']),
                         ('proposal','proposal-call','procurement_validation','rejected'))
        self.assertNotIn('PRIVATE_QUERY',json.dumps(row)); self.assertNotIn('PRIVATE_SECRET',json.dumps(row))


if __name__=='__main__': unittest.main()
