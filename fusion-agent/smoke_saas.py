"""Live two-tenant isolation, payment timeout and bounded seckill load checks.
Only this script's new studio orders/activities are changed. Existing demo stock is untouched.
"""
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
from datetime import datetime,timedelta
from hashlib import sha256
import json,time,uuid,argparse
from pathlib import Path
import httpx
from bootstrap_tenants import sql
BASE="http://127.0.0.1:8035"
RUN=uuid.uuid4().hex[:12]
REQUESTS=40
CONCURRENCY=20

def admin(name):
    c=httpx.Client(base_url=BASE,timeout=30,limits=httpx.Limits(max_connections=60,max_keepalive_connections=60))
    body=c.post('/login',json={'username':name,'password':'admin123'}).json();assert body.get('code')==200,body.get('msg')
    c.headers['Authorization']='Bearer '+body['token'];return c

def business(c,method,path,body=None):
    response=c.request(method,path,json=body).json();assert response.get('code')==200,(path,response)
    return response['data']

def store():
    c=httpx.Client(base_url="http://127.0.0.1:7051",timeout=45)
    body=c.get('/api/account/autoLogin').json();assert body['code']==200
    addresses=c.post('/api/userAddress/loadDataList',json={}).json();assert addresses['code']==200,addresses
    if addresses['data']:
        c._smoke_address_id=addresses['data'][0]['addressId']
    else:
        saved=c.post('/api/userAddress/addAddress',json={'addressee':'负载练习访客','phone':'00000000000','address':'虚构地址：样例市负载测试空间','defaultType':1}).json()
        assert saved['code']==200,saved
        c._smoke_address_id=saved['data']['addressId']
    return c

def call(c,path,**body):
    if path in ('order/postOrder','seckill/createOrder','seckill/submitOrder'):
        body.setdefault('addressId',getattr(c,'_smoke_address_id','demo-address'))
    return c.post('/api/'+path,json=body).json()

def run():
    a,b=admin('admin'),admin('studio_admin');clients=[];created=[];activity_id='load_'+RUN
    before_a=business(a,'GET','/commerce/inventory')
    assert business(a,'GET','/commerce/context')['tenantId']=='demo'
    assert business(b,'GET','/commerce/context')['tenantId']=='studio'
    for c,wrong in ((a,'studio'),(b,'demo')):
        assert c.get('/commerce/orders',headers={'X-Tenant-ID':wrong}).status_code==403
    assert b.get('/monitor/cache').status_code==403
    assert b.get('/baseDate/product/list').json()['total']==8
    existing=business(a,'GET','/commerce/orders')['rows']
    if existing:assert b.get('/commerce/orders/'+existing[0]['orderId']).json()['code']==404
    print('PASS authenticated database routing, forged tenant header, cross-tenant order and platform endpoint rejection')
    studio_before=business(b,'GET','/commerce/inventory')
    product=next(p for p in studio_before if p['productCode']=='DEMO-LAMP-ZB');pid=product['productId'];capacity=min(12,REQUESTS,int(product['availableStock']))
    assert capacity>0
    now=datetime.now()
    business(b,'POST','/commerce/activities',{'activityId':activity_id,'productId':pid,'title':'Concurrency test '+RUN,'capacity':capacity,'perOwnerLimit':1,'price':99,'startsAt':(now-timedelta(seconds=5)).isoformat(timespec='seconds'),'endsAt':(now+timedelta(minutes=10)).isoformat(timespec='seconds')})
    assert not any(x['activityId']==activity_id for x in business(a,'GET','/commerce/activities'))
    try:
        for _ in range(REQUESTS):clients.append(store())
        def buy(index):
            start=time.perf_counter();result=call(clients[index],'seckill/createOrder',activityId=activity_id,requestKey=RUN+'_'+str(index),tenantId='demo');return index,result,(time.perf_counter()-start)*1000
        start=time.perf_counter()
        with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:results=list(pool.map(buy,range(len(clients))))
        elapsed=time.perf_counter()-start
        codes=Counter(x[1]['code'] for x in results)
        winners=[(i,res['data']) for i,res,_ in results if res['code']==200];created.extend((clients[i],o['orderId']) for i,o in winners)
        assert len(winners)==capacity,(codes,capacity)
        assert set(codes)<={200,409,429},codes
        assert len({o['orderId'] for _,o in winners})==capacity
        for _,order in winners:assert order['amount']==99 and order['tenantId']=='studio'
        inventory=next(p for p in business(b,'GET','/commerce/inventory') if p['productId']==pid)
        assert inventory['reservedStock']==capacity and inventory['activityStock']==0
        i,order=winners[0]
        same=call(clients[i],'seckill/createOrder',activityId=activity_id,requestKey=RUN+'_'+str(i))
        assert same['code']==200 and same['data']['orderId']==order['orderId']
        with ThreadPoolExecutor(max_workers=20) as pool:
            refused=list(pool.map(lambda n:call(clients[i],'seckill/createOrder',activityId=activity_id,requestKey=RUN+'_over_'+str(n)),range(20)))
        assert any(x['code']==429 for x in refused)
        assert a.get('/commerce/orders/'+order['orderId']).json()['code']==404
        durations=sorted(t for _,_,t in results)
        report={'requests':REQUESTS,'concurrency':CONCURRENCY,'activityCapacity':capacity,'codes':dict(codes),'elapsedSeconds':round(elapsed,3),'p50Ms':round(durations[len(durations)//2],1),'p95Ms':round(durations[int(len(durations)*.95)-1],1),'oversold':False}
        Path('../logs/seckill-load-result.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print('PASS live C checkout load',json.dumps(report))
        for client,oid in created:assert call(client,'order/cancelOrder',orderId=oid)['data']['orderStatus']==4
        assert next(x for x in business(b,'GET','/commerce/activities') if x['activityId']==activity_id)['remaining']==capacity
        # Expire this test activity to return its unclaimed allocation to ordinary sales.
        sql("UPDATE ksdatabase_studio.commerce_activity SET ends_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE activity_id='"+activity_id+"'")
        assert business(b,'GET','/commerce/inventory')==studio_before
        client=clients[0]
        saved_address = call(client,'userAddress/loadDataList')['data'][0]
        shipping = {key:saved_address[key] for key in ('addressee','phone','address')}
        timeout=business(b,'POST','/commerce/orders',{'ownerId':sha256(client.cookies.get('simlect_studio_session').encode()).hexdigest(),'requestKey':'expire_'+RUN,'shippingAddress':shipping,'items':[{'productId':pid,'quantity':2}]})
        oid=timeout['orderId'];created.append((client,oid))
        sql("UPDATE ksdatabase_studio.commerce_order SET expires_at=TIMESTAMPADD(SECOND,-2,CURRENT_TIMESTAMP) WHERE order_id='"+oid+"'")
        deadline=time.monotonic()+8
        while time.monotonic()<deadline:
            row=business(b,'GET','/commerce/orders/'+oid)
            if row['orderStatus']==4:break
            time.sleep(.25)
        assert row['orderStatus']==4 and row['closeReason']=='PAYMENT_TIMEOUT',row
        assert business(b,'GET','/commerce/inventory')==studio_before
        assert call(client,'order/sandboxPay',orderId=oid)['code']==409
        assert business(a,'GET','/commerce/inventory')==before_a
        print('PASS scheduled timeout releases holds, restores activity quota and never changes the other tenant stock')
        # A cookie from tenant A is not a valid visitor of tenant B.
        other=httpx.Client(base_url='http://127.0.0.1:7050',timeout=15);other.get('/api/account/autoLogin')
        assert httpx.get('http://127.0.0.1:7051/api/order/loadOrder',headers={'Cookie':'simlect_studio_session='+other.cookies.get('simlect_demo_session')}).json()['code']==901
        other.close();print('PASS visitor sessions are tenant scoped')
    finally:
        for client,oid in created:
            try:call(client,'order/cancelOrder',orderId=oid)
            except Exception:pass
        sql("UPDATE ksdatabase_studio.commerce_activity SET ends_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE activity_id='"+activity_id+"'")
        for c in clients:c.close()
        a.close();b.close()

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--requests',type=int,default=40)
    parser.add_argument('--concurrency',type=int,default=20)
    options=parser.parse_args()
    if not 12<=options.requests<=500 or not 1<=options.concurrency<=100:parser.error('requests 12..500, concurrency 1..100')
    REQUESTS,CONCURRENCY=options.requests,options.concurrency
    run()
