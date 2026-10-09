"""Two independent ASGI lifecycles sharing Redis sessions and local SQLite."""
import time
import unittest

from fastapi import FastAPI
from fastapi.testclient import TestClient
import store_api
import test_store_api as fixtures


class StoreRuntimeTests(unittest.TestCase):
    setUp=fixtures.StoreIntegrationTests.setUp
    cleanup_redis=fixtures.StoreIntegrationTests.cleanup_redis
    login=fixtures.StoreIntegrationTests.login
    api=fixtures.StoreIntegrationTests.api
    data=fixtures.StoreIntegrationTests.data
    send_question=fixtures.StoreIntegrationTests.send_question
    await_message=fixtures.StoreIntegrationTests.await_message
    post_order=fixtures.StoreIntegrationTests.post_order
    create_order=fixtures.StoreIntegrationTests.create_order

    def shared_cookie(self):
        return {'cookie':store_api.COOKIE+'='+self.alice.cookies.get(store_api.COOKIE)}

    def test_starting_another_worker_during_answer_keeps_live_execution(self):
        self.service.answer_delay=.6
        identity=self.send_question(self.alice,'这款灯怎么选？')
        app=FastAPI();app.include_router(store_api.build_store_router(self.service))
        with TestClient(app,base_url='http://127.0.0.1:7050') as newcomer:
            rows=newcomer.post('/api/agent/loadHistoryMessage',json={},headers=self.shared_cookie()).json()['data']['list']
            self.assertEqual(next(row for row in rows if row['messageId']==identity)['status'],1)
        self.await_message(self.alice,identity)

    def test_result_reaches_socket_connected_to_other_worker(self):
        headers={**self.shared_cookie(),'origin':'http://127.0.0.1:6001'}
        with self.bob.websocket_connect('/ws/',headers=headers) as socket:
            identity=self.send_question(self.alice,'推荐一款智能灯')
            payload=socket.receive_json()
            self.assertEqual(payload['messageId'],identity)
            self.assertEqual(payload['outPutType'],1)
        self.await_message(self.alice,identity)

    def test_other_worker_cancels_and_late_answer_does_not_replace_status(self):
        self.service.answer_delay=1.3
        identity=self.send_question(self.alice,'推荐一款智能灯')
        response=self.bob.post('/api/agent/cancelMessage',json={'messageId':identity},headers=self.shared_cookie())
        self.assertEqual(response.json()['code'],200)
        message=self.await_message(self.alice,identity,expected_status=3)
        self.assertEqual(message['assistantMessage'],'已停止回答')

    def test_mutating_order_operations_reject_get_navigation(self):
        identity=self.create_order(self.alice)
        for path in ('order/cancelOrder','order/sandboxPay','order/receiveOrder','order/postOrder','order/querySandboxPayment'):
            response=self.alice.get('/api/'+path,params={'orderId':identity,'payMethod':'demo'})
            self.assertEqual(response.json()['code'],405,path)
        self.assertEqual(self.authority.orders[identity]['orderStatus'],0)
        self.assertFalse(self.authority.payments)
        time.sleep(1.4)
        message=self.await_message(self.alice,identity,expected_status=3)
        self.assertEqual(message['assistantMessage'],'已停止回答')


if __name__=='__main__':unittest.main()
