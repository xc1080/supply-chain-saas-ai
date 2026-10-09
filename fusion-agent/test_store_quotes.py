"""Owned quote revisions, live repricing and atomic Redis bundle cart checks."""
import copy
import json
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

import test_store_api as fixtures
from business_planning import apply_authoritative_quote
from commerce_api import store_order


class QuoteAuthority(fixtures.FakeCommerceAuthority):
    def handle(self, request):
        if request.url.path == '/commerce/context':
            return self.response({'tenantId':'demo','shopId':'default'})
        if request.url.path == '/commerce/planning/quote':
            body = json.loads(request.content)
            items = []
            for requested in body['items']:
                stock = self.stock(requested['productId'])
                quantity, price = requested['quantity'], stock['price']
                items.append({'productId':requested['productId'],'quantity':quantity,'unitPrice':price,
                              'availableStock':stock['availableStock'],'lineTotal':price*quantity*body['units']})
            return self.response({'items':items,'totalAmount':sum(item['lineTotal'] for item in items),
                                  'promisableUnits':min(item['availableStock']//item['quantity'] for item in items),
                                  'deliveryStatus':'UNKNOWN','deliveryDate':None,'deliveryCapacity':None})
        return super().handle(request)


class QuoteStoreTests(unittest.TestCase):
    login = fixtures.StoreIntegrationTests.login
    api = fixtures.StoreIntegrationTests.api
    data = fixtures.StoreIntegrationTests.data
    cart = fixtures.StoreIntegrationTests.cart
    add_cart = fixtures.StoreIntegrationTests.add_cart
    send_question = fixtures.StoreIntegrationTests.send_question
    await_message = fixtures.StoreIntegrationTests.await_message
    create_order = fixtures.StoreIntegrationTests.create_order
    post_order = fixtures.StoreIntegrationTests.post_order
    tearDown = fixtures.StoreIntegrationTests.tearDown

    def setUp(self):
        fixtures.StoreIntegrationTests.setUp(self)
        self.authority = QuoteAuthority(self.service.catalog)
        self.service.catalog[0].update(id='1',code='LAB-TAPO-L530E',name='Tapo L530E')
        self.service.catalog[1].update(id='2',code='LAB-TAPO-T100',name='Tapo T100')
        self.units = 2
        self.plan_status = 'READY_FOR_REVIEW'
        self.contexts = []
        self.emit_plan = True

        async def run_plan(state, fetch_catalog, tenant, owner, **kwargs):
            self.contexts.append(copy.deepcopy(state['context']))
            if not self.emit_plan: return {'answer':'普通商品问答','products':[],'sources':[],'mode':{'llm':'local'}}
            items = [{'productId':'1','quantity':1},{'productId':'2','quantity':1}]
            plan = {'type':'CONSUMER_BUNDLE','status':'REVIEW_REQUIRED','units':self.units,'items':items,
                    'missing':[],'budget':{'limit':1000,'total':None,'withinBudget':None},
                    'compatibility':{'status':'COMPATIBLE','checks':[]},'inventory':{'requestedUnits':self.units},
                    'alternatives':[],'requiresApproval':True,'executable':False,
                    'request':{'items':items,'units':self.units,'budget':1000,'ownedGatewayModels':['H100'],
                               'installationConfirmed':True,'regionConfirmed':True}}
            plan = apply_authoritative_quote(plan,await kwargs['quote_fetcher'](items,self.units),self.service.catalog)
            plan['status'] = self.plan_status
            if self.plan_status == 'NEEDS_INPUT': plan['missing'] = [{'field':'gateway','question':'请确认网关型号'}]
            return {'answer':'方案草稿','products':[],'sources':[],'mode':{'llm':'local'},'businessPlan':plan}
        self.service.run_customer_plan = run_plan

    def plan(self, client=None):
        client = client or self.alice
        message = self.send_question(client,'给我整套设备报价')
        return json.loads(self.await_message(client,message)['assistantMessage'])['businessPlan']

    def confirm(self, plan, client=None, **extra):
        return self.api(client or self.alice,'agent/confirmQuote',quoteToken=plan['quoteToken'],revision=plan['revision'],**extra)

    def test_quote_is_read_only_owner_scoped_and_batch_confirmation_is_idempotent(self):
        plan = self.plan()
        self.assertFalse(self.cart(self.alice))
        self.assertFalse(self.authority.orders)
        self.assertEqual(self.api(self.bob,'agent/quoteDetail',quoteToken=plan['quoteToken'])['code'],404)
        self.assertEqual(self.confirm(plan,self.bob)['code'],404)
        first = self.confirm(plan,items=[{'productId':'private','quantity':99}],units=99)
        self.assertEqual(first['code'],200,first)
        self.assertEqual(first['data']['businessPlan']['confirmationStatus'],'ADDED')
        self.assertEqual({row['productId']:row['buyCount'] for row in self.cart(self.alice)}, {'1':2,'2':2})
        self.assertEqual(self.confirm(plan)['data']['businessPlan']['confirmationStatus'],'ADDED')
        self.assertEqual(sum(row['buyCount'] for row in self.cart(self.alice)),4)
        history = self.data(self.alice,'agent/loadHistoryMessage')['list'][0]
        self.assertEqual(json.loads(history['assistantMessage'])['businessPlan']['confirmationStatus'],'ADDED')
        self.assertEqual(self.service.catalog[0]['stock'],5)
        self.assertFalse(self.authority.held)

    def test_price_change_returns_revision_and_requires_explicit_second_confirmation(self):
        plan = self.plan()
        self.service.catalog[0]['price'] = 139
        updated = self.confirm(plan)['data']['businessPlan']
        self.assertEqual(updated['confirmationStatus'],'REPRICE_REQUIRED')
        self.assertEqual(updated['revision'],2)
        self.assertEqual(updated['budget']['total'],416)
        self.assertFalse(self.cart(self.alice))
        self.assertEqual(self.confirm(plan)['data']['businessPlan']['revision'],2)
        self.assertFalse(self.cart(self.alice))
        self.assertEqual(self.confirm(updated)['data']['businessPlan']['confirmationStatus'],'ADDED')
        self.assertEqual(sum(row['buyCount'] for row in self.cart(self.alice)),4)

    def test_shortage_refresh_and_cart_capacity_failure_never_partially_add_bundle(self):
        plan = self.plan()
        self.service.catalog[0]['stock'] = 1
        revised = self.confirm(plan)['data']['businessPlan']
        self.assertEqual(revised['status'],'REVIEW_REQUIRED')
        self.assertEqual(revised['inventory']['shortages'][0]['quantity'],1)
        self.assertFalse(self.cart(self.alice))
        self.service.catalog[0]['stock'] = 5
        other = self.plan()
        self.assertEqual(self.add_cart(self.alice,'2',7)['code'],200)
        self.assertEqual(self.confirm(other)['code'],409)
        self.assertEqual({row['productId']:row['buyCount'] for row in self.cart(self.alice)},{'2':7})

    def test_missing_conditions_expiry_and_invalid_token_never_add_to_cart(self):
        self.plan_status = 'NEEDS_INPUT'
        plan = self.plan()
        self.assertEqual(self.confirm(plan)['code'],409)
        self.plan_status = 'READY_FOR_REVIEW'
        plan = self.plan()
        field = 'quote-draft:' + plan['quoteToken']
        key = self.state_keys(self.alice.cookies.get(fixtures.store_api.COOKIE))[3]
        expired = json.loads(self.redis.hget(key,field));expired['expiresAt'] = time.time()-1
        self.redis.hset(key,field,json.dumps(expired))
        self.assertEqual(self.confirm(plan)['code'],409)
        self.assertEqual(self.api(self.alice,'agent/quoteDetail',quoteToken='../../other')['code'],404)
        self.assertFalse(self.cart(self.alice))

    def test_same_quote_parallel_confirmations_commit_one_batch_across_facades(self):
        plan = self.plan()
        self.bob.cookies.set(fixtures.store_api.COOKIE,self.alice.cookies.get(fixtures.store_api.COOKIE))
        with ThreadPoolExecutor(max_workers=2) as workers:
            futures = [workers.submit(self.confirm,plan,client) for client in (self.alice,self.bob)]
            results = [future.result() for future in futures]
        self.assertTrue(all(result['code'] == 200 for result in results),results)
        self.assertEqual(sum(row['buyCount'] for row in self.cart(self.alice)),4)

    def test_last_server_bundle_request_survives_more_than_three_chat_rounds(self):
        first = self.plan()
        self.emit_plan = False
        for _ in range(4):
            message = self.send_question(self.alice,'商品的安装说明呢')
            self.await_message(self.alice,message)
        self.emit_plan = True
        self.plan()
        self.assertEqual(self.contexts[-1]['previousBundleRequest'],first['request'])

    def test_changed_compatibility_evidence_requires_review_before_cart_write(self):
        plan = self.plan()
        with patch('business_planning._pair_check',return_value={'status':'unknown_pair'}):
            revised = self.confirm(plan)['data']['businessPlan']
        self.assertEqual(revised['status'],'REVIEW_REQUIRED')
        self.assertEqual(revised['compatibility']['status'],'UNKNOWN')
        self.assertFalse(self.cart(self.alice))

    def test_partial_authority_fields_are_preserved_and_workflow_counts_do_not_hide_remainder(self):
        order_id = self.create_order(self.alice,lines=[{'productId':'1','buyCount':3}])
        order = self.authority.orders[order_id]
        order.update(orderStatus=1,fulfillmentStatus='PARTIALLY_SHIPPED',shippedAmount=129,returnedAmount=0,
                     refundedAmount=0,availableActions=['SHIP','AFTER_SALES'],afterSalesStatus='REQUESTED',
                     afterSalesCases=[{'afterSalesId':'AS-partial','kind':'UNSHIPPED_REFUND','items':[{'productId':'1','quantity':1}]}],
                     shipments=[{'shipmentId':'SHIP-1','items':[{'productId':'1','quantity':1}]}])
        order['items'][0].update(orderedQuantity=3,shippedQuantity=1,unshippedQuantity=2,shippableQuantity=1,
                                returnedQuantity=0,cancelledQuantity=0,refundedQuantity=0,afterSalesAvailableQuantity=2,
                                unshippedRefundAvailableQuantity=1,returnAvailableQuantity=1)
        adapted = store_order(order)
        self.assertEqual(adapted['orderItemList'][0]['shippableQuantity'],1)
        self.assertEqual(adapted['orderItemList'][0]['buyCount'],3)
        self.assertEqual(adapted['afterSalesCases'][0]['kind'],'UNSHIPPED_REFUND')
        self.assertEqual(adapted['shipments'][0]['shipmentId'],'SHIP-1')
        self.assertEqual(self.data(self.alice,'order/loadMyOrder',status=1)['totalCount'],1)
        self.assertEqual(self.data(self.alice,'order/getOrderCountInfo')['waitSend'],1)


if __name__ == '__main__': unittest.main()
