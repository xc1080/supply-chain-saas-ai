"""Same-host SQLite execution leases and redacted, persistent delivery events.

SQLite files must be on local disk shared by the processes. This is deliberately
not a distributed workflow engine or a multi-host/NFS storage contract.
"""
from __future__ import annotations

import hashlib
import contextvars
import json
import os
import re
import time
import uuid
from dataclasses import dataclass

PROCESS_ID = uuid.uuid4().hex
LEASE_SECONDS = 30
CURRENT_MESSAGE = contextvars.ContextVar('agent_current_message',default=None)


class LostLease(RuntimeError):
    """An expired or replaced executor must never publish its old result."""


@dataclass
class Lease:
    executor: str
    version: int


def executor_id():
    return PROCESS_ID + ":" + uuid.uuid4().hex


def redact(value):
    """Remove credentials/contact fields recursively from checkpoints/metrics."""
    if isinstance(value, dict):
        return {key: redact(item) for key, item in value.items() if not re.search(
            r"authorization|password|secret|api[_-]?key|access[_-]?token|shippingAddress|recipient|phone|email|address", key, re.I)}
    if isinstance(value, list):
        return [redact(item) for item in value]
    if isinstance(value, str):
        value = re.sub(r"Bearer\s+\S+", "[credential removed]", value, flags=re.I)
        value = re.sub(r"\b1[3-9]\d{9}\b", "[phone removed]", value)
        value = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", "[email removed]", value)
    return value


def initialize_messages(connection):
    connection.execute("BEGIN IMMEDIATE")
    fields = {row[1] for row in connection.execute("PRAGMA table_info(messages)")}
    for name, definition in {"executor_id": "TEXT NOT NULL DEFAULT ''", "lease_until": "REAL NOT NULL DEFAULT 0",
                             "version": "INTEGER NOT NULL DEFAULT 0", "tenant_id": "TEXT NOT NULL DEFAULT ''",
                             "shop_id": "TEXT NOT NULL DEFAULT 'default'", "agent_run_id":"TEXT NOT NULL DEFAULT ''"}.items():
        if name not in fields:
            connection.execute(f"ALTER TABLE messages ADD COLUMN {name} {definition}")
    connection.executescript("""
    CREATE INDEX IF NOT EXISTS ix_messages_scope ON messages(tenant_id,shop_id,session_id,id);
    CREATE TABLE IF NOT EXISTS message_events(sequence INTEGER PRIMARY KEY AUTOINCREMENT,
      tenant_id TEXT NOT NULL,shop_id TEXT NOT NULL,session_id TEXT NOT NULL,message_id INTEGER NOT NULL,
      payload TEXT NOT NULL,created REAL NOT NULL);
    CREATE INDEX IF NOT EXISTS ix_message_events_scope ON message_events(tenant_id,shop_id,session_id,sequence);
    """)


def expire_messages(connection, tenant, shop):
    now = time.time()
    # Never interrupt another live worker, including a worker with the same tenant.
    rows = connection.execute("""SELECT id,session_id,agent_run_id FROM messages
        WHERE status=1 AND (tenant_id=? OR tenant_id='') AND shop_id=?
        AND ((executor_id<>'' AND lease_until<?) OR
             (executor_id='' AND (julianday('now','localtime')-julianday(sent_at))*86400>100))""",
        (tenant, shop, now)).fetchall()
    changed = 0
    for row in rows:
        answer='回答已中断，请重试'
        if row['agent_run_id']:
            answer=json.dumps({'type':'CHAT_RESULT','answer':answer,'runId':row['agent_run_id'],'planStatus':'INTERRUPTED'},ensure_ascii=False)
        updated = connection.execute("UPDATE messages SET status=3,answer=?,version=version+1 WHERE id=? AND status=1", (answer,row['id'])).rowcount
        if not updated: continue
        changed += updated
        payload = {'messageType':'agent','messageId':row['id'],'assistantMessage':answer,'outPutType':2}
        connection.execute("INSERT INTO message_events(tenant_id,shop_id,session_id,message_id,payload,created) VALUES(?,?,?,?,?,?)",
                           (tenant,shop,row['session_id'],row['id'],json.dumps(payload,ensure_ascii=False),now))
    return changed


def create_message(connection, tenant, shop, session, question, sent_at):
    connection.execute("BEGIN IMMEDIATE")
    expire_messages(connection, tenant, shop)
    if connection.execute("SELECT 1 FROM messages WHERE session_id=? AND (tenant_id=? OR tenant_id='') AND shop_id=? AND status=1 LIMIT 1",
                          (session, tenant, shop)).fetchone():
        from fastapi import HTTPException
        raise HTTPException(409, "正在回答上一个问题，请等待或停止后再发送")
    lease = Lease(executor_id(), 0)
    identity = connection.execute("""INSERT INTO messages(session_id,question,sent_at,tenant_id,shop_id,executor_id,lease_until)
        VALUES(?,?,?,?,?,?,?)""", (session, question, sent_at, tenant, shop, lease.executor, time.time()+LEASE_SECONDS)).lastrowid
    return identity, lease


def renew_message(connection, identity, lease):
    changed = connection.execute("""UPDATE messages SET lease_until=?,version=version+1
       WHERE id=? AND executor_id=? AND version=? AND status=1 AND lease_until>?""",
       (time.time()+LEASE_SECONDS, identity, lease.executor, lease.version, time.time())).rowcount
    if not changed:
        raise LostLease("Message execution lease lost")
    lease.version += 1


def bind_message_run(connection, identity, lease, run_id):
    changed=connection.execute("UPDATE messages SET agent_run_id=? WHERE id=? AND executor_id=? AND version=? AND status=1 AND lease_until>?",
                               (run_id,identity,lease.executor,lease.version,time.time())).rowcount
    if not changed: raise LostLease('Message cannot bind a run after cancellation')


def bind_current_run(run_id):
    callback=CURRENT_MESSAGE.get()
    if callback: callback(run_id)


def finish_message(connection, identity, lease, tenant, shop, session, text, biz, status):
    changed = connection.execute("""UPDATE messages SET answer=?,status=?,biz_type=?,version=version+1,lease_until=0
       WHERE id=? AND tenant_id=? AND shop_id=? AND session_id=? AND executor_id=? AND version=? AND status=1 AND lease_until>?""",
       (text, status, biz, identity, tenant, shop, session, lease.executor, lease.version, time.time())).rowcount
    if not changed:
        raise LostLease("Message result rejected after cancellation/recovery")
    lease.version += 1
    payload = {"messageType": "agent", "messageId": identity, "assistantMessage": text,
               "bizType": biz, "outPutType": 1 if status == 2 else 2}
    connection.execute("INSERT INTO message_events(tenant_id,shop_id,session_id,message_id,payload,created) VALUES(?,?,?,?,?,?)",
                       (tenant, shop, session, identity, json.dumps(payload, ensure_ascii=False), time.time()))


def cancel_message(connection, tenant, shop, session, identity):
    changed = connection.execute("""UPDATE messages SET status=3,answer='已停止回答',version=version+1,lease_until=0
       WHERE id=? AND session_id=? AND (tenant_id=? OR tenant_id='') AND shop_id=? AND status=1""",
       (identity, session, tenant, shop)).rowcount
    if changed:
        payload = {"messageType": "agent", "messageId": identity, "assistantMessage": "已停止回答", "outPutType": 2}
        connection.execute("INSERT INTO message_events(tenant_id,shop_id,session_id,message_id,payload,created) VALUES(?,?,?,?,?,?)",
                           (tenant, shop, session, identity, json.dumps(payload, ensure_ascii=False), time.time()))
    return changed


def retain_messages(connection):
    cutoff = time.time()-max(1, int(os.getenv("FUSION_CHAT_RETENTION_DAYS", "30")))*86400
    connection.execute("DELETE FROM message_events WHERE created<?", (cutoff,))
    connection.execute("DELETE FROM messages WHERE status<>1 AND julianday(sent_at)<julianday(?,'unixepoch','localtime')", (cutoff,))
    connection.execute("DELETE FROM proposals WHERE expires<?", (time.time(),))
