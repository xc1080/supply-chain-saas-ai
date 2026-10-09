"""Fencing, cancellation and startup recovery against shared local SQLite."""
import json
import os
import tempfile
import time
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import planner
import store_api
from execution_runtime import (Lease, LostLease, create_message, finish_message, renew_message,
                               expire_messages, cancel_message, redact, bind_message_run)


class ExecutionRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.patch = patch.object(store_api, 'DB_PATH', Path(self.temp.name)/'store.sqlite3'); self.patch.start()
        self.env = patch.dict(os.environ, {'FUSION_AGENT_DB':str(Path(self.temp.name)/'agent.sqlite3')}); self.env.start()
    def tearDown(self):
        self.env.stop(); self.patch.stop(); self.temp.cleanup()
    def create(self):
        with store_api.db() as c:
            return create_message(c,'demo','default','visitor','问题',datetime.now().strftime('%Y-%m-%d %H:%M:%S'))
    def test_second_worker_startup_does_not_interrupt_live_message(self):
        identity, lease = self.create()
        with store_api.db() as c:
            self.assertEqual(expire_messages(c,'demo','default'),0)
            self.assertEqual(c.execute('SELECT status FROM messages WHERE id=?',(identity,)).fetchone()[0],1)
            renew_message(c,identity,lease)
        with store_api.db() as c:
            finish_message(c,identity,lease,'demo','default','visitor','完成','chat',2)
            event=c.execute('SELECT payload FROM message_events').fetchone()[0]
            self.assertEqual(json.loads(event)['assistantMessage'],'完成')
    def test_cross_worker_cancel_fences_result_and_lease(self):
        identity, lease=self.create()
        with store_api.db() as c: self.assertEqual(cancel_message(c,'demo','default','visitor',identity),1)
        with store_api.db() as c:
            with self.assertRaises(LostLease): finish_message(c,identity,lease,'demo','default','visitor','过期回答','chat',2)
            with self.assertRaises(LostLease): renew_message(c,identity,lease)
            self.assertEqual(c.execute('SELECT answer FROM messages WHERE id=?',(identity,)).fetchone()[0],'已停止回答')
    def test_expired_executor_and_wrong_tenant_cannot_publish(self):
        identity, lease=self.create()
        with store_api.db() as c:
            with self.assertRaises(LostLease): finish_message(c,identity,lease,'studio','default','visitor','跨租户','chat',2)
            c.execute('UPDATE messages SET lease_until=? WHERE id=?',(time.time()-1,identity))
        with store_api.db() as c:
            self.assertEqual(expire_messages(c,'studio','default'),0)
            self.assertEqual(expire_messages(c,'demo','default'),1)
            with self.assertRaises(LostLease): finish_message(c,identity,lease,'demo','default','visitor','迟到','chat',2)
    def test_checkpoint_expected_version_and_new_executor_reject_old_writer(self):
        lease=Lease('worker-old',0)
        with planner.database() as c:
            c.execute("INSERT INTO agent_runs(run_id,tenant_id,owner_id,status,state,updated,executor_id,lease_until) VALUES('run','demo','owner','RUNNING','{}',?,?,?)",(time.time(),'worker-old',time.time()+30))
        planner.checkpoint('run','demo','owner',{'message':'核验'},'RUNNING',lease)
        with planner.database() as c:
            c.execute("UPDATE agent_runs SET executor_id='worker-new',version=version+1 WHERE run_id='run'")
        with self.assertRaises(LostLease): planner.checkpoint('run','demo','owner',{'message':'旧结果'},'COMPLETED',lease)
        row=planner.inspect_run('run','demo','owner')
        self.assertEqual(row['executor_id'],'worker-new');self.assertEqual(row['status'],'RUNNING')
    def test_dead_worker_message_keeps_run_id_for_explicit_resume(self):
        identity,lease=self.create()
        with store_api.db() as c:
            bind_message_run(c,identity,lease,'saved-run')
            c.execute('UPDATE messages SET lease_until=? WHERE id=?',(time.time()-1,identity))
        with store_api.db() as c:
            self.assertEqual(expire_messages(c,'demo','default'),1)
            row=c.execute('SELECT answer FROM messages WHERE id=?',(identity,)).fetchone()
            self.assertEqual(json.loads(row['answer'])['runId'],'saved-run')
            self.assertEqual(json.loads(row['answer'])['planStatus'],'INTERRUPTED')
    def test_checkpoint_recovery_only_claims_expired_executors(self):
        with planner.database() as c:
            for identity,deadline in [('alive',time.time()+30),('dead',time.time()-1)]:
                c.execute("INSERT INTO agent_runs(run_id,tenant_id,owner_id,status,state,updated,executor_id,lease_until) VALUES(?,'demo','owner','RUNNING','{}',?,?,?)",(identity,time.time(),'worker',deadline))
        planner.recover()
        self.assertEqual(planner.inspect_run('alive','demo','owner')['status'],'RUNNING')
        self.assertEqual(planner.inspect_run('dead','demo','owner')['status'],'INTERRUPTED')
    def test_redaction_removes_nested_credentials_and_contacts(self):
        safe=redact({'authorization':'Bearer SECRET','context':{'shippingAddress':{'phone':'13800138000'},'data':'请联系13800138000或a@example.com','nested':{'api_key':'SECRET'}}})
        encoded=json.dumps(safe)
        for private in ('SECRET','13800138000','a@example.com','shippingAddress'):
            self.assertNotIn(private,encoded)


if __name__=='__main__': unittest.main()
