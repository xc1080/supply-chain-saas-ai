"""Authority transport lifecycle and concurrency checks, independent of LLM transports."""
import asyncio
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch

import httpx
from fastapi import HTTPException
from fastapi import FastAPI
from fastapi.testclient import TestClient

import commerce_api
import store_api
import test_store_api as fixtures


class CommercePoolTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        patcher = patch.dict('os.environ', {'FUSION_CUSTOMER_ASSERTION_SECRET':'isolated-proof-secret-32-characters'})
        patcher.start()
        self.addCleanup(patcher.stop)

    async def token(self):
        return "Bearer isolated-test"

    async def test_router_startup_and_shutdown_own_the_authority_client(self):
        clients = []
        actual_client = httpx.AsyncClient

        def factory(**kwargs):
            client = actual_client(transport=httpx.MockTransport(lambda r: httpx.Response(200, json={})), **kwargs)
            clients.append(client)
            return client

        with tempfile.TemporaryDirectory(prefix="commerce-pool-lifecycle-") as temporary:
            with patch.object(store_api, "DB_PATH", Path(temporary) / "store.sqlite3"):
                with patch.object(commerce_api.httpx, "AsyncClient", side_effect=factory):
                    app = FastAPI()
                    app.include_router(store_api.build_store_router(fixtures.OfflineService()))
                    with TestClient(app):
                        self.assertEqual(len(clients), 1)
                        self.assertFalse(clients[0].is_closed)
                    self.assertTrue(clients[0].is_closed)

    async def test_requests_reuse_one_bounded_client_and_lifecycle_closes_it(self):
        service = SimpleNamespace(JAVA_URL="http://127.0.0.1:8035", TIMEOUT=httpx.Timeout(2))
        created, options, seen = [], [], []
        actual_client = httpx.AsyncClient

        def transport(request):
            seen.append(request)
            return httpx.Response(200, json={"code": 200, "data": {"state": "PENDING"}})

        def factory(**kwargs):
            options.append(kwargs)
            client = actual_client(transport=httpx.MockTransport(transport), **kwargs)
            created.append(client)
            return client

        api = commerce_api.CommerceAPI(service, self.token)
        with patch.object(commerce_api.httpx, "AsyncClient", side_effect=factory):
            await api.start()
            await api.start()
            results = await asyncio.gather(*(api.request("POST", "/commerce/activities/rush/checkout",
                                                          body={"ownerId": "a" * 64, "requestKey": "same-key"}) for _ in range(20)))
            self.assertEqual(len(results), 20)
            self.assertEqual(len(created), 1)
            self.assertEqual(options[0]["limits"].max_connections, 64)
            self.assertEqual(options[0]["limits"].max_keepalive_connections, 32)
            self.assertFalse(options[0]["trust_env"])
            self.assertNotIn("verify", options[0])
            self.assertTrue(all(request.headers["authorization"] == "Bearer isolated-test" for request in seen))
            self.assertTrue(all(b'"requestKey":"same-key"' in request.content for request in seen))
            await api.close()
            self.assertTrue(created[0].is_closed)
            await api.close()
            await api.start()
            self.assertEqual(len(created), 2)
            await api.close()

    async def test_remote_authority_retains_proxy_environment_and_tls_defaults(self):
        service = SimpleNamespace(JAVA_URL="https://authority.example.test", TIMEOUT=httpx.Timeout(2))
        actual_client = httpx.AsyncClient
        options = []

        def factory(**kwargs):
            options.append(kwargs)
            return actual_client(transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"code": 200, "data": {}})), **kwargs)

        api = commerce_api.CommerceAPI(service, self.token)
        with patch.object(commerce_api.httpx, "AsyncClient", side_effect=factory):
            try:
                await api.request("GET", "/commerce/context")
                self.assertTrue(options[0]["trust_env"])
                self.assertNotIn("verify", options[0])
            finally: await api.close()

    async def test_uncertain_response_does_not_generate_a_new_checkout_key(self):
        service = SimpleNamespace(JAVA_URL="http://localhost:8035", TIMEOUT=httpx.Timeout(2))
        calls = []
        actual_client = httpx.AsyncClient

        def transport(request):
            calls.append(request.content)
            if len(calls) == 1: raise httpx.ReadTimeout("Acknowledgement lost after possible commit", request=request)
            return httpx.Response(200, json={"code": 200, "data": {"jobId": "b" * 32, "state": "PENDING"}})

        api = commerce_api.CommerceAPI(service, self.token)
        with patch.object(commerce_api.httpx, "AsyncClient", side_effect=lambda **kwargs: actual_client(transport=httpx.MockTransport(transport), **kwargs)):
            try:
                body = {"ownerId": "a" * 64, "requestKey": "original-key"}
                with self.assertRaises(HTTPException) as failure:
                    await api.request("POST", "/commerce/activities/rush/checkout", body=body)
                self.assertEqual(failure.exception.status_code, 502)
                receipt = await api.request("POST", "/commerce/activities/rush/checkout", body=body)
                self.assertEqual(receipt["state"], "PENDING")
                self.assertEqual(calls[0], calls[1])
            finally: await api.close()

    async def test_explicit_authentication_rejection_refreshes_once_with_new_customer_nonce(self):
        service = SimpleNamespace(JAVA_URL='http://localhost:8035', TIMEOUT=httpx.Timeout(2))
        for status, body in [(401, {'code':401}), (200, {'code':401})]:
            seen = []
            def transport(request):
                seen.append(request)
                if len(seen)==1: return httpx.Response(status, json=body)
                return httpx.Response(200, json={'code':200,'data':{'orderId':'accepted'}})
            refresh = AsyncMock(return_value='Bearer refreshed')
            api = commerce_api.CommerceAPI(service, self.token, token_refresher=refresh)
            api._client = httpx.AsyncClient(transport=httpx.MockTransport(transport))
            with patch.dict('os.environ', {'FUSION_CUSTOMER_ASSERTION_SECRET':'isolated-proof-secret-32-characters'}):
                try:
                    result = await api.request('POST','/commerce/orders', body={'ownerId':'a'*64,'requestKey':'fixed'})
                    self.assertEqual(result['orderId'],'accepted')
                    refresh.assert_awaited_once_with('Bearer isolated-test')
                    self.assertEqual(len(seen),2)
                    self.assertEqual(seen[1].headers['authorization'],'Bearer refreshed')
                    self.assertNotEqual(seen[0].headers['x-customer-nonce'],seen[1].headers['x-customer-nonce'])
                    self.assertNotEqual(seen[0].headers['x-customer-signature'],seen[1].headers['x-customer-signature'])
                    self.assertEqual(seen[0].content,seen[1].content)
                finally: await api.close()

    async def test_business_rejection_and_uncertain_commit_never_refresh_or_resend(self):
        service = SimpleNamespace(JAVA_URL='http://localhost:8035', TIMEOUT=httpx.Timeout(2))
        for failure, expected in [(403,403), ('timeout',502)]:
            seen = []
            def transport(request):
                seen.append(request)
                if failure == 'timeout': raise httpx.ReadTimeout('Possible commit; missing acknowledgement',request=request)
                return httpx.Response(200,json={'code':403,'msg':'Scope denied'})
            refresh = AsyncMock(return_value='Bearer refreshed')
            api = commerce_api.CommerceAPI(service,self.token,token_refresher=refresh)
            api._client = httpx.AsyncClient(transport=httpx.MockTransport(transport))
            with patch.dict('os.environ', {'FUSION_CUSTOMER_ASSERTION_SECRET':'isolated-proof-secret-32-characters'}):
                try:
                    with self.assertRaises(HTTPException) as raised:
                        await api.request('POST','/commerce/orders',body={'ownerId':'a'*64,'requestKey':'fixed'})
                    self.assertEqual(raised.exception.status_code,expected)
                    refresh.assert_not_awaited()
                    self.assertEqual(len(seen),1)
                finally: await api.close()

    async def test_second_authentication_rejection_stops_without_refresh_loop(self):
        service = SimpleNamespace(JAVA_URL='http://localhost:8035', TIMEOUT=httpx.Timeout(2))
        seen=[]
        def transport(request):
            seen.append(request)
            return httpx.Response(401,json={'code':401})
        refresh=AsyncMock(return_value='Bearer refreshed')
        api=commerce_api.CommerceAPI(service,self.token,token_refresher=refresh)
        api._client=httpx.AsyncClient(transport=httpx.MockTransport(transport))
        with patch.dict('os.environ', {'FUSION_CUSTOMER_ASSERTION_SECRET':'isolated-proof-secret-32-characters'}):
            try:
                with self.assertRaises(HTTPException) as raised:
                    await api.request('GET','/commerce/catalog')
                self.assertEqual(raised.exception.status_code,503)
                refresh.assert_awaited_once()
                self.assertEqual(len(seen),2)
            finally: await api.close()


if __name__ == "__main__":
    unittest.main()
