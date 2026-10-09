"""Ensure one usable public demo activity per configured tenant without resetting existing activity/order state."""
from datetime import datetime,timedelta
import uuid
from seed_demo import api
import os

def seed():
    token=api('/login',body={'username':os.getenv('FUSION_DEMO_USER','admin'),'password':os.getenv('FUSION_DEMO_PASSWORD','admin123')})['token']
    now=datetime.now()
    activities=api('/commerce/activities',token)['data']
    if any(datetime.fromisoformat(a['endsAt'].replace(' ','T'))>now and a['remaining']>0 for a in activities):
        print('Existing active activity retained');return
    inventory=api('/commerce/inventory',token)['data']
    product=next((p for p in inventory if p['productCode']=='DEMO-LAMP-WIFI' and p['availableStock']>=2),None)
    if product is None:print('No spare demo allocation; activity not created');return
    api('/commerce/activities',token,{'activityId':'welcome_'+uuid.uuid4().hex[:12],'productId':product['productId'],'title':'智能照明限时选购','price':169,'capacity':2,'perOwnerLimit':1,'startsAt':(now-timedelta(seconds=1)).isoformat(timespec='seconds'),'endsAt':(now+timedelta(hours=24)).isoformat(timespec='seconds')})
    print('Public demo activity created; two existing units allocated, no stock added')
if __name__=='__main__':seed()
