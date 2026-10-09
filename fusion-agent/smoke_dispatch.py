"""Ship one isolated Studio sandbox order; assert stock is deducted exactly once."""
from datetime import datetime, timedelta
from contextlib import closing
import uuid
from smoke_saas import admin, business, store, call

def run():
    with closing(admin('admin')) as a, closing(admin('studio_admin')) as b, closing(store()) as customer:
        root = business(a, 'GET', '/commerce/inventory')
        before = business(b, 'GET', '/commerce/inventory')
        product = next(p for p in before if p['productCode'] == 'DEMO-LAMP-ZB')
        pid = product['productId']
        activity = 'dispatch_' + uuid.uuid4().hex[:12]
        now = datetime.now()
        business(b, 'POST', '/commerce/activities', {
            'activityId': activity, 'productId': pid, 'title': 'Sandbox dispatch verification',
            'price': 99, 'capacity': 1, 'perOwnerLimit': 1,
            'startsAt': (now-timedelta(seconds=5)).isoformat(timespec='seconds'),
            'endsAt': (now+timedelta(minutes=5)).isoformat(timespec='seconds')})
        response = call(customer, 'seckill/createOrder', activityId=activity, requestKey=activity)
        assert response['code'] == 200, response
        order = response['data']['orderId']
        def stock():
            return next(p for p in business(b, 'GET', '/commerce/inventory') if p['productId']==pid)
        def act():
            return next(p for p in business(b, 'GET', '/commerce/activities') if p['activityId']==activity)
        assert stock()['bookStock'] == product['bookStock'] and stock()['reservedStock'] == product['reservedStock']+1
        paid = call(customer, 'order/sandboxPay', orderId=order)
        assert paid['code']==200 and paid['data']['orderStatus']==1, paid
        assert stock()['bookStock'] == product['bookStock'] and act()['remaining']==0
        sent = business(b, 'POST', '/commerce/orders/'+order+'/ship', {})
        repeated = business(b, 'POST', '/commerce/orders/'+order+'/ship', {})
        assert sent['receiptId']==repeated['receiptId'] and sent['orderStatus']==2
        assert stock()['bookStock']==product['bookStock']-1 and stock()['reservedStock']==product['reservedStock']
        received = call(customer, 'order/receiveOrder', orderId=order)
        assert received['code']==200 and received['data']['orderStatus']==3, received
        assert stock()['bookStock']==product['bookStock']-1 and act()['remaining']==0
        assert business(a, 'GET', '/commerce/inventory')==root
        print('PASS Studio seckill -> sandbox pay -> idempotent dispatch -> receive; one unit deducted, Demo tenant unchanged; order='+order)

if __name__=='__main__': run()
