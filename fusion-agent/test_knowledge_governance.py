"""Immediate source revocation and quote evidence revisions, without a model."""
import copy
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import business_catalog as catalog_module
from business_catalog import load_scenario, profile_for, manufacturer_source, compatibility_assessment, registry_version
from business_planning import prepare_bundle
from test_business_planning import catalog, request
import test_store_quotes as quote_fixtures
import test_store_api as store_fixtures


class KnowledgeRegistryTests(unittest.TestCase):
    def setUp(self):
        self.data=copy.deepcopy(load_scenario())
        self.temp=tempfile.TemporaryDirectory()
        self.path=Path(self.temp.name)/'catalog.json'
        self.write()
        self.patch=patch.object(catalog_module,'DATA_PATH',self.path);self.patch.start()
    def tearDown(self):self.patch.stop();self.temp.cleanup()
    def write(self):self.path.write_text(json.dumps(self.data,ensure_ascii=False),encoding='utf-8')
    def row(self,code):return next(row for row in self.data['products'] if row['code']==code)
    def test_evidence_change_invalidates_cache_even_with_same_file_timestamp(self):
        original=profile_for('LAB-TAPO-T100')['evidenceVersion'];timestamp=self.path.stat().st_mtime
        self.row('LAB-TAPO-T100')['facts'].append('新的厂商型号核对记录')
        self.write();os.utime(self.path,(timestamp,timestamp))
        self.assertNotEqual(profile_for('LAB-TAPO-T100')['evidenceVersion'],original)
    def test_trade_costs_never_change_knowledge_version_or_leak_into_profile(self):
        original=registry_version()
        self.row('LAB-TAPO-T100')['simulation']['costPrice']=99999
        self.write();self.assertEqual(registry_version(),original)
        self.assertNotIn('99999',json.dumps(profile_for('LAB-TAPO-T100')))
    def test_revoked_product_and_source_cannot_authorize_model_pair(self):
        product={'code':'LAB-TAPO-T100','name':'T100'}
        self.assertEqual(compatibility_assessment(product,'H100')['status'],'verified_pair')
        for source in self.row('LAB-TAPO-T100')['sources']:source['publicationStatus']='REVOKED'
        self.write()
        self.assertEqual(compatibility_assessment(product,'H100')['status'],'evidence_withdrawn')
        self.assertIsNone(manufacturer_source(product))
        self.assertEqual(profile_for('LAB-TAPO-T100')['facts'],[])
        plan,_=prepare_bundle(request((3,1),owned=['H100']),catalog())
        self.assertEqual(plan['status'],'NEEDS_INPUT')
        self.assertIn('evidenceVerification',{item['field'] for item in plan['missing']})
    def test_user_confirmation_does_not_verify_unknown_firmware_or_china_region(self):
        for field,value,missing in [('requestedFirmwareVersion','1.0.0','firmwareVerification'),
                                    ('requestedHardwareRevision','V9','hardwareVerification'),
                                    ('requestedRegion','CN','regionVerification')]:
            raw=request((3,1),owned=['H100']);raw[field]=value
            plan,_=prepare_bundle(raw,catalog())
            self.assertEqual(plan['status'],'NEEDS_INPUT')
            self.assertIn(missing,{item['field'] for item in plan['missing']})


class KnowledgeQuoteTests(unittest.TestCase):
    login=store_fixtures.StoreIntegrationTests.login
    api=store_fixtures.StoreIntegrationTests.api
    data=store_fixtures.StoreIntegrationTests.data
    cart=store_fixtures.StoreIntegrationTests.cart
    send_question=store_fixtures.StoreIntegrationTests.send_question
    await_message=store_fixtures.StoreIntegrationTests.await_message
    plan=quote_fixtures.QuoteStoreTests.plan
    confirm=quote_fixtures.QuoteStoreTests.confirm
    def setUp(self):
        quote_fixtures.QuoteStoreTests.setUp(self)
        self.registry=copy.deepcopy(load_scenario())
        directory=self.stack.enter_context(tempfile.TemporaryDirectory())
        self.path=Path(directory)/'catalog.json';self.write()
        self.stack.enter_context(patch.object(catalog_module,'DATA_PATH',self.path))
    def write(self):self.path.write_text(json.dumps(self.registry,ensure_ascii=False),encoding='utf-8')
    def test_revised_evidence_requires_second_confirmation_without_adding_cart(self):
        plan=self.plan()
        row=next(p for p in self.registry['products'] if p['code']=='LAB-TAPO-L530E')
        row['facts'].append('更新后的厂商安装核对记录');self.write()
        updated=self.confirm(plan)['data']['businessPlan']
        self.assertEqual(updated['confirmationStatus'],'EVIDENCE_REVIEW_REQUIRED')
        self.assertEqual(updated['revision'],2);self.assertFalse(self.cart(self.alice))
        self.assertNotEqual(updated['evidenceSnapshot']['registryVersion'],plan['evidenceSnapshot']['registryVersion'])
        self.assertEqual(self.confirm(updated)['data']['businessPlan']['confirmationStatus'],'ADDED')
    def test_revoked_evidence_blocks_saved_quote_even_with_unchanged_price_and_stock(self):
        plan=self.plan()
        row=next(p for p in self.registry['products'] if p['code']=='LAB-TAPO-T100')
        row['publicationStatus']='REVOKED';self.write()
        updated=self.confirm(plan)['data']['businessPlan']
        self.assertEqual(updated['status'],'NEEDS_INPUT')
        self.assertFalse(self.cart(self.alice))
        self.assertEqual(self.confirm(updated)['code'],409)


if __name__=='__main__':unittest.main()
