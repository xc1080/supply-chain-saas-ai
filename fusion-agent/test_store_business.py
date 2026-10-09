"""Public catalog, private addresses and customer-only after-sales facade checks."""
import copy
import json
import unittest

import test_store_api as fixtures
from product_media import product_cover


class BusinessAuthority(fixtures.FakeCommerceAuthority):
    def __init__(self, catalog):
        super().__init__(catalog)
        self.after_sales = {}
        self.requests = []

    def handle(self, request):
        self.requests.append(request)
        path = request.url.path
        body = json.loads(request.content) if request.content else {}
        if path.endswith('/after-sales') and path.startswith('/commerce/orders/'):
            order = self.orders.get(path.split('/')[3])
            if not order or order['ownerId'] != body.get('ownerId'):
                return self.response(code=404, msg='订单不存在')
            if order['orderStatus'] not in (1, 2, 3):
                return self.response(code=409, msg='订单尚未付款')
            if not order.get('afterSalesId'):
                identity = 'AS-' + order['orderId']
                row = {'afterSalesId':identity, 'orderId':order['orderId'], 'status':'REQUESTED', 'statusName':'待审核',
                       'reason':body['reason'], 'refundAmount':order['totalAmount'], 'refundedAmount':0,
                       'createdAt':'2026-10-08 12:00:00', 'ownerId':body['ownerId'], 'requestKey':body['requestKey']}
                self.after_sales[identity] = row
                order.update(afterSalesId=identity, afterSalesStatus='REQUESTED')
            row = self.after_sales[order['afterSalesId']]
            if row['requestKey'] != body['requestKey'] or row['reason'] != body['reason']:
                return self.response(code=409, msg='已有售后申请')
            return self.response({key:value for key,value in row.items() if key not in ('ownerId','requestKey')})
        if path == '/commerce/after-sales':
            rows = [{key:value for key,value in row.items() if key not in ('ownerId','requestKey')}
                    for row in self.after_sales.values() if row['ownerId'] == request.url.params.get('ownerId')]
            return self.response({'rows':rows, 'total':len(rows)})
        if path.startswith('/commerce/after-sales/'):
            row = self.after_sales.get(path.rsplit('/',1)[-1])
            if not row or row['ownerId'] != request.url.params.get('ownerId'): return self.response(code=404, msg='售后记录不存在')
            return self.response({key:value for key,value in row.items() if key not in ('ownerId','requestKey')})
        return super().handle(request)


class StoreBusinessTests(unittest.TestCase):
    login = fixtures.StoreIntegrationTests.login
    api = fixtures.StoreIntegrationTests.api
    data = fixtures.StoreIntegrationTests.data
    create_order = fixtures.StoreIntegrationTests.create_order
    post_order = fixtures.StoreIntegrationTests.post_order
    detail = fixtures.StoreIntegrationTests.detail
    tearDown = fixtures.StoreIntegrationTests.tearDown

    def setUp(self):
        fixtures.StoreIntegrationTests.setUp(self)
        self.authority = BusinessAuthority(self.service.catalog)

    def address(self, client=None, **changes):
        return self.data(client or self.alice, 'userAddress/addAddress',
                         **{'addressee':'练习收货人','phone':'+86 13800000000','address':'上海市练习园区虚构地址一号','defaultType':1,**changes})['addressId']

    def test_any_listed_sku_is_public_with_manufacturer_profile_and_generic_fallback(self):
        base = copy.deepcopy(self.service.catalog[0])
        self.service.catalog.extend([{**base,'id':'new-hub','code':'LAB-TAPO-H100','name':'Tapo H100'},
                                     {**base,'id':'new-unknown','code':'ERP-NEW-SKU','name':'新上架货品'}])
        rows = self.data(self.alice,'product/loadProduct')['list']
        by_id = {row['productId']:row for row in rows}
        self.assertNotIn('private',by_id)
        self.assertEqual(by_id['new-hub']['technicalProfile']['model'],'H100')
        self.assertTrue(by_id['new-hub']['technicalProfile']['sources'][0]['url'].startswith('https://'))
        self.assertEqual(by_id['new-hub']['cover'],'/media/demo/products/gateway-zb.svg')
        self.assertEqual(by_id['new-unknown']['cover'],'/media/demo/fallback.svg')
        self.assertIsNone(by_id['new-unknown']['technicalProfile'])
        self.assertNotIn('simulation',by_id['new-hub']['technicalProfile'])
        self.assertEqual(product_cover('LAB-TAPO-H100', '/demo-media/products/fallback.svg'), '/media/demo/products/gateway-zb.svg')
        self.assertEqual(product_cover('LAB-TAPO-T100'), '/media/demo/products/sensor-motion.svg')
        self.assertEqual(product_cover('ERP-NEW-SKU', '/media/uploads/real-photo.png'), '/media/uploads/real-photo.png')

    def test_addresses_crud_are_private_owned_and_snapshot_cannot_be_spoofed(self):
        identity = self.address()
        self.assertEqual(self.api(self.bob,'userAddress/delAddress',addressId=identity)['code'],404)
        response = self.alice.get('/api/userAddress/loadDataList')
        self.assertEqual(response.headers['cache-control'],'private, no-store')
        selected = next(row for row in response.json()['data'] if row['addressId'] == identity)
        self.assertEqual(selected['defaultType'],1)
        order = self.data(self.alice,'order/postOrder',payMethod='demo',addressId=identity,clientRequestId='snapshot-order',
                          shippingAddress={'addressee':'forged','phone':'000000','address':'forged'},
                          orderList=[{'productId':'lamp','buyCount':1}])
        snapshot = self.detail(self.alice,order['orderId'])['shippingAddress']
        self.assertEqual(snapshot['addressee'],'练习收货人')
        self.assertEqual(set(snapshot),{'addressee','phone','address'})
        self.data(self.alice,'userAddress/updateAddress',addressId=identity,addressee='新的练习姓名',phone='13800000001',address='北京市另一个练习收货地址',defaultType=0)
        self.data(self.alice,'userAddress/delAddress',addressId=identity)
        self.assertEqual(self.detail(self.alice,order['orderId'])['shippingAddress'],snapshot)
        self.assertEqual(self.api(self.alice,'order/postOrder',payMethod='demo',addressId=identity,clientRequestId='missing-address',orderList=[{'productId':'lamp','buyCount':1}])['code'],422)

    def test_address_validation_rejects_short_oversized_and_control_text(self):
        for changes in ({'address':'短址'},{'address':'x'*301},{'phone':'abc12345'},{'addressee':'x'*65},{'address':'hello\x00world'}):
            result = self.api(self.alice,'userAddress/addAddress',**{'addressee':'练习用户','phone':'13800000000','address':'练习园区完整的收货地址',**changes})
            self.assertEqual(result['code'],422,result)

    def test_other_visitors_address_cannot_be_used_for_an_order(self):
        identity = self.address()
        result = self.api(self.bob,'order/postOrder',payMethod='demo',addressId=identity,clientRequestId='foreign-address',orderList=[{'productId':'lamp','buyCount':1}])
        self.assertEqual(result['code'],422)
        self.assertFalse(self.authority.orders)

    def test_customer_after_sales_are_owned_idempotent_and_cannot_review_or_refund(self):
        order_id = self.create_order(self.alice)
        self.data(self.alice,'order/sandboxPay',orderId=order_id)
        result = self.data(self.alice,'afterSales/apply',orderId=order_id,requestKey='return-one',reason='练习整单退货',ownerId='forged')
        identity = result['afterSalesId']
        self.assertEqual(self.data(self.alice,'afterSales/apply',orderId=order_id,requestKey='return-one',reason='练习整单退货')['afterSalesId'],identity)
        self.assertEqual(self.data(self.alice,'afterSales/list')['totalCount'],1)
        self.assertEqual(self.data(self.alice,'order/loadMyOrder',status=1)['totalCount'],0)
        self.assertEqual(self.data(self.alice,'order/getOrderCountInfo')['waitSend'],0)
        self.assertEqual(self.data(self.alice,'order/loadMyOrder')['totalCount'],1)
        self.assertEqual(self.data(self.bob,'afterSales/list')['list'],[])
        self.assertEqual(self.api(self.bob,'afterSales/detail',afterSalesId=identity)['code'],404)
        self.assertEqual(self.api(self.bob,'afterSales/apply',orderId=order_id,requestKey='forged',reason='越权')['code'],404)
        for path in ('afterSales/review','afterSales/accept-return','afterSales/sandbox-refund'):
            count = len(self.authority.requests)
            self.assertNotEqual(self.api(self.alice,path,afterSalesId=identity,decision='APPROVE')['code'],200)
            self.assertEqual(len(self.authority.requests),count)
        self.assertEqual(self.detail(self.alice,order_id)['afterSalesStatus'],'REQUESTED')

    def test_partial_after_sales_payload_is_forwarded_without_expanding_to_full_order(self):
        order_id = self.create_order(self.alice,lines=[{'productId':'lamp','buyCount':2}])
        self.data(self.alice,'order/sandboxPay',orderId=order_id)
        result = self.api(self.alice,'afterSales/apply',orderId=order_id,requestKey='partial',reason='只退一件',kind='UNSHIPPED_REFUND',items=[{'productId':'lamp','quantity':1}],ownerId='forged')
        self.assertEqual(result['code'],200,result)
        body = json.loads(self.authority.requests[-1].content)
        self.assertEqual(body['items'],[{'productId':'lamp','quantity':1}])
        self.assertEqual(body['kind'],'UNSHIPPED_REFUND')
        self.assertNotEqual(body['ownerId'],'forged')

    def test_partial_after_sales_rejects_invalid_or_foreign_lines_before_authority_write(self):
        order_id = self.create_order(self.alice,lines=[{'productId':'lamp','buyCount':2}])
        self.data(self.alice,'order/sandboxPay',orderId=order_id)
        invalid = [[],[{'productId':'lamp','quantity':0}],[{'productId':'lamp','quantity':1.5}],
                   [{'productId':'sensor','quantity':1}], [{'productId':'lamp','quantity':1}]*2]
        for index, items in enumerate(invalid):
            result = self.api(self.alice,'afterSales/apply',orderId=order_id,requestKey='invalid-'+str(index),reason='验证数量',kind='UNSHIPPED_REFUND',items=items)
            self.assertEqual(result['code'],422,result)
        self.assertEqual(self.api(self.alice,'afterSales/apply',orderId=order_id,requestKey='invalid-kind',reason='验证范围',kind='UNKNOWN',items=[{'productId':'lamp','quantity':1}])['code'],422)
        self.assertFalse(self.authority.after_sales)


if __name__ == '__main__': unittest.main()
