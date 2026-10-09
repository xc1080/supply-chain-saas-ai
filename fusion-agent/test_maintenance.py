"""Retention works without a restart and never removes another active worker."""
import asyncio
import os
import sqlite3
import tempfile
import time
import unittest
from datetime import datetime, timedelta
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import patch

import planner
from execution_runtime import expire_messages, initialize_messages, retain_messages
from maintenance import PeriodicMaintenance


class MaintenanceTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "store.sqlite3"
        self.env = patch.dict(os.environ, {"FUSION_AGENT_DB": str(Path(self.temp.name) / "runs.sqlite3"),
            "FUSION_CHAT_RETENTION_DAYS": "30", "FUSION_AGENT_RETENTION_DAYS": "30"})
        self.env.start()
        with self.database() as connection:
            connection.executescript("""CREATE TABLE messages(id INTEGER PRIMARY KEY,session_id TEXT NOT NULL,
                question TEXT NOT NULL, answer TEXT NOT NULL DEFAULT '', status INTEGER NOT NULL,
                biz_type TEXT NOT NULL DEFAULT 'chat',sent_at TEXT NOT NULL);
                CREATE TABLE proposals(token TEXT PRIMARY KEY,session_id TEXT NOT NULL,order_id TEXT NOT NULL,
                status INTEGER NOT NULL DEFAULT 0,expires REAL NOT NULL);""")
            initialize_messages(connection)

    @contextmanager
    def database(self):
        connection = sqlite3.connect(self.path)
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def tearDown(self):
        self.env.stop()
        self.temp.cleanup()

    async def wait_for(self, condition):
        async with asyncio.timeout(3):
            while not condition():
                await asyncio.sleep(.005)

    def cleanup_store(self):
        with self.database() as connection:
            connection.row_factory = sqlite3.Row
            connection.execute("BEGIN IMMEDIATE")
            expire_messages(connection, "demo", "default")
            return retain_messages(connection)

    def seed_store(self):
        old = (datetime.now() - timedelta(days=40)).strftime("%Y-%m-%d %H:%M:%S")
        recent = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        with self.database() as connection:
            for identity, status, sent, tenant in ((1, 2, old, "demo"), (2, 1, old, "demo"),
                                                  (3, 2, recent, "demo"), (4, 1, old, "studio")):
                connection.execute("""INSERT INTO messages(id,session_id,question,status,sent_at,tenant_id,executor_id,lease_until)
                    VALUES(?,'customer','question',?,?,?,'alive',?)""", (identity, status, sent, tenant, time.time() + 30))
                connection.execute("""INSERT INTO message_events(tenant_id,shop_id,session_id,message_id,payload,created)
                    VALUES(?,'default','customer',?,'{}',?)""", (tenant, identity, time.time() - 40 * 86400))
            # Also remove a recently delivered event belonging to the old message.
            connection.execute("""INSERT INTO message_events(tenant_id,shop_id,session_id,message_id,payload,created)
                VALUES('demo','default','customer',1,'{}',?)""", (time.time(),))
            for token, deadline in (("expired", time.time() - 1), ("fresh", time.time() + 60)):
                connection.execute("INSERT INTO proposals(token,session_id,order_id,expires) VALUES(?,'customer','order',?)", (token, deadline))

    async def test_long_running_service_cleans_expired_records_and_preserves_live_workers(self):
        worker = PeriodicMaintenance(self.cleanup_store, name="chat", interval=.015)
        await worker.start()
        try:
            await self.wait_for(lambda: worker.last_success is not None)
            self.seed_store()  # Arrives after startup cleanup; only a later cycle removes it.
            def removed():
                with self.database() as connection:
                    return connection.execute("SELECT COUNT(*) FROM messages WHERE id=1").fetchone()[0] == 0
            await self.wait_for(removed)
            with self.database() as connection:
                self.assertEqual(connection.execute("SELECT id,status FROM messages ORDER BY id").fetchall(), [(2, 1), (3, 2), (4, 1)])
                self.assertEqual(connection.execute("SELECT message_id FROM message_events ORDER BY message_id").fetchall(), [(2,), (4,)])
                self.assertEqual(connection.execute("SELECT token FROM proposals").fetchall(), [("fresh",)])
            self.assertTrue(worker.snapshot()["running"])
        finally:
            await worker.close()
        self.assertFalse(worker.snapshot()["running"])

    async def test_two_process_callbacks_are_idempotent_under_sqlite_lock(self):
        self.seed_store()
        results = await asyncio.gather(asyncio.to_thread(self.cleanup_store), asyncio.to_thread(self.cleanup_store))
        self.assertEqual(sum(result["messages"] for result in results), 1)
        self.assertEqual(sum(result["proposals"] for result in results), 1)
        with self.database() as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM messages WHERE status=1").fetchone()[0], 2)

    async def test_failures_are_observable_and_next_cycle_retries(self):
        calls = []
        def callback():
            calls.append(time.time())
            if len(calls) == 1:
                raise RuntimeError("PRIVATE_PAYLOAD")
            return {"deleted": 1}
        worker = PeriodicMaintenance(callback, name="test", interval=.01)
        with self.assertLogs("maintenance", level="WARNING") as logged:
            await worker.start()
            await self.wait_for(lambda: worker.last_success is not None)
            await worker.close()
        self.assertEqual(worker.failures, 1)
        self.assertIsNotNone(worker.last_failure)
        self.assertEqual(worker.last_result, {"deleted": 1})
        self.assertNotIn("PRIVATE_PAYLOAD", "".join(logged.output))

    async def test_shutdown_wakes_sleep_and_start_is_idempotent(self):
        calls = []
        worker = PeriodicMaintenance(lambda: calls.append(1), name="test", interval=3600)
        await worker.start()
        original_task = worker._task
        await worker.start()
        self.assertIs(worker._task, original_task)
        await self.wait_for(lambda: len(calls) == 1)
        await asyncio.wait_for(worker.close(), .5)
        self.assertEqual(calls, [1])
        await worker.close()

    async def test_agent_run_retention_runs_periodically_without_fencing_active_executor(self):
        with planner.database() as connection:
            for identity, status, updated, lease in (("old", "COMPLETED", time.time() - 40 * 86400, 0),
                    ("fresh", "COMPLETED", time.time(), 0), ("alive", "RUNNING", time.time(), time.time() + 30),
                    ("dead", "RUNNING", time.time(), time.time() - 1)):
                connection.execute("""INSERT INTO agent_runs(run_id,tenant_id,owner_id,status,state,updated,executor_id,lease_until)
                    VALUES(?,'demo','owner',?,'{}',?,'worker',?)""", (identity, status, updated, lease))
        worker = PeriodicMaintenance(planner.recover, name="agent", interval=.01)
        await worker.start()
        try:
            await self.wait_for(lambda: worker.last_success is not None)
            with planner.database() as connection:
                self.assertEqual([(row["run_id"], row["status"]) for row in connection.execute("SELECT * FROM agent_runs ORDER BY run_id")],
                                 [("alive", "RUNNING"), ("dead", "INTERRUPTED"), ("fresh", "COMPLETED")])
            original = planner.inspect_run("alive", "demo", "owner")
            await self.wait_for(lambda: worker.last_result == {"interrupted": 0, "deleted": 0})
            self.assertEqual(planner.inspect_run("alive", "demo", "owner")["version"], original["version"])
        finally:
            await worker.close()


if __name__ == "__main__":
    unittest.main()
