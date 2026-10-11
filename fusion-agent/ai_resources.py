"""Redis backed AI admission and daily provider token accounting.

AI resource failures fail closed for model calls only; checkout and payment do
not depend on this module. All Lua keys use one hash tag for atomic admission.
"""
from __future__ import annotations

import asyncio
import contextvars
import hashlib
import json
import os
import sqlite3
import time
import uuid
from contextlib import asynccontextmanager, closing, contextmanager
from functools import wraps
from datetime import datetime, timezone
from pathlib import Path

import httpx
import redis.asyncio as redis
from redis.exceptions import RedisError
from redis.backoff import NoBackoff
from redis.retry import Retry
from ai_versions import digest, provider_id, safe_label, version_manifest

CURRENT = contextvars.ContextVar("ai_resource_scope", default=None)
TRACKING = contextvars.ContextVar("ai_tracking_scope", default=None)


@contextmanager
def tracking_scope(tenant, *, run_id=None, versions=None):
    context = {"tenant": str(tenant), "runId": run_id or uuid.uuid4().hex,
               "versions": versions if versions is not None else version_manifest()}
    token = TRACKING.set(context)
    try:
        yield context
    finally:
        TRACKING.reset(token)


def tracked_operation(operation):
    def decorate(function):
        @wraps(function)
        async def wrapped(*args, **kwargs):
            if TRACKING.get() is None:
                with tracking_scope(os.getenv("FUSION_TENANT", "demo")):
                    tracking_event(operation, 'started', kind='request')
                    return await function(*args, **kwargs)
            return await function(*args, **kwargs)
        return wrapped
    return decorate


def tracking_event(operation, outcome, **fields):
    scope = TRACKING.get()
    tenant = scope["tenant"] if scope else os.getenv("FUSION_TENANT", "demo")
    metric(tenant, operation, outcome, **fields)


def safe_summary(summary):
    # Deliberate allowlist: never persist arbitrary tool payloads or error text.
    result = {}
    for key, value in (summary or {}).items():
        if key in {"items", "sources", "citations", "required_tools", "completed_tools", "attempt", "rows"}:
            if isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= 1000000: result[key] = value
        elif key == "cached" and isinstance(value, bool): result[key] = value
        elif key == "result_digest" and isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value): result[key] = value
        elif key == "error_type" and isinstance(value, str) and value in {"HTTPException", "ValueError", "KeyError", "TypeError", "IndexError", "OSError", "TimeoutError", "CancelledError", "ResourceUnavailable", "HTTPStatusError", "ConnectError", "ReadTimeout", "LostLease", "RuntimeError"}: result[key] = value
    return result


class ResourceUnavailable(RuntimeError):
    pass


JOIN = """
local now=tonumber(ARGV[2])
for _,key in ipairs(KEYS) do redis.call('ZREMRANGEBYSCORE',key,'-inf',now) end
if redis.call('ZCARD',KEYS[3]) >= tonumber(ARGV[3]) or redis.call('ZCARD',KEYS[4]) >= tonumber(ARGV[4]) then return 0 end
redis.call('ZADD',KEYS[3],ARGV[5],ARGV[1]);redis.call('ZADD',KEYS[4],ARGV[5],ARGV[1])
for _,key in ipairs(KEYS) do redis.call('EXPIRE',key,180) end
return 1
"""
ACQUIRE = """
local now=tonumber(ARGV[2])
for _,key in ipairs(KEYS) do redis.call('ZREMRANGEBYSCORE',key,'-inf',now) end
if not redis.call('ZSCORE',KEYS[3],ARGV[1]) then return -1 end
if redis.call('ZRANK',KEYS[4],ARGV[1])~=0 then return 0 end
if redis.call('ZCARD',KEYS[1]) >= tonumber(ARGV[3]) or redis.call('ZCARD',KEYS[2]) >= tonumber(ARGV[4]) then return 0 end
redis.call('ZREM',KEYS[3],ARGV[1]);redis.call('ZREM',KEYS[4],ARGV[1])
redis.call('ZADD',KEYS[1],ARGV[5],ARGV[1]);redis.call('ZADD',KEYS[2],ARGV[5],ARGV[1])
return 1
"""
RENEW = """
local now=tonumber(ARGV[2])
local a=redis.call('ZSCORE',KEYS[1],ARGV[1]);local b=redis.call('ZSCORE',KEYS[2],ARGV[1])
if not a or not b or tonumber(a)<=now or tonumber(b)<=now then return 0 end
redis.call('ZADD',KEYS[1],ARGV[3],ARGV[1]);redis.call('ZADD',KEYS[2],ARGV[3],ARGV[1])
redis.call('EXPIRE',KEYS[1],180);redis.call('EXPIRE',KEYS[2],180)
return 1
"""
RELEASE = "for _,key in ipairs(KEYS) do redis.call('ZREM',key,ARGV[1]) end return 1"
RESERVE = """
local amount=tonumber(ARGV[1])
if tonumber(redis.call('GET',KEYS[1]) or '0')+amount>tonumber(ARGV[2]) or tonumber(redis.call('GET',KEYS[2]) or '0')+amount>tonumber(ARGV[3]) then return 0 end
redis.call('INCRBY',KEYS[1],amount);redis.call('INCRBY',KEYS[2],amount)
redis.call('EXPIRE',KEYS[1],172800);redis.call('EXPIRE',KEYS[2],172800)
return 1
"""
SETTLE = """
local delta=tonumber(ARGV[1]);redis.call('INCRBY',KEYS[1],delta);redis.call('INCRBY',KEYS[2],delta)
return 1
"""


def metric(tenant, operation, outcome, *, elapsed=0, tokens=0, status=None, kind="runtime", call_id="",
           provider="", model="", prompt_version="", tool_name="", input_tokens=None, output_tokens=None,
           usage_source="", summary=None):
    # No question, document text, address, bearer token or model response stored.
    path = Path(os.getenv("FUSION_AI_METRICS_DB", str(Path(__file__).parent.parent / "runtime/ai-metrics.sqlite3")))
    path.parent.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(path, timeout=3)) as connection:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("CREATE TABLE IF NOT EXISTS ai_metrics(id INTEGER PRIMARY KEY,created REAL NOT NULL,tenant TEXT NOT NULL,operation TEXT NOT NULL,outcome TEXT NOT NULL,elapsed REAL NOT NULL,tokens INTEGER NOT NULL,status INTEGER)")
        extra = {name: "TEXT NOT NULL DEFAULT ''" for name in ("run_id", "event_id", "kind", "call_id", "provider", "model", "prompt_version", "tool_name", "tool_version", "knowledge_version", "usage_source")}
        extra.update(input_tokens="INTEGER", output_tokens="INTEGER", summary="TEXT NOT NULL DEFAULT '{}'", versions="TEXT NOT NULL DEFAULT '{}'")
        existing = {row[1] for row in connection.execute("PRAGMA table_info(ai_metrics)")}
        for name, definition in extra.items():
            if name not in existing: connection.execute(f"ALTER TABLE ai_metrics ADD COLUMN {name} {definition}")
        connection.execute("CREATE INDEX IF NOT EXISTS ix_ai_metrics_run ON ai_metrics(run_id,created,id)")
        scope = TRACKING.get() or {}
        versions = scope.get("versions") or {}
        values = {"created": time.time(), "tenant": safe_label(tenant), "operation": safe_label(operation), "outcome": safe_label(outcome),
                  "elapsed": elapsed, "tokens": tokens, "status": status, "run_id": safe_label(scope.get("runId")),
                  "event_id": uuid.uuid4().hex, "kind": safe_label(kind), "call_id": safe_label(call_id),
                  "provider": safe_label(provider), "model": safe_label(model), "prompt_version": safe_label(prompt_version),
                  "tool_name": safe_label(tool_name), "tool_version": (versions.get("tools") or {}).get("catalogVersion", ""),
                  "knowledge_version": digest(versions["knowledge"]) if versions.get("knowledge") else "",
                  "input_tokens": input_tokens, "output_tokens": output_tokens, "usage_source": safe_label(usage_source),
                  "summary": json.dumps(safe_summary(summary), sort_keys=True), "versions": json.dumps(versions, sort_keys=True)}
        columns = ",".join(values)
        connection.execute(f"INSERT INTO ai_metrics({columns}) VALUES({','.join('?' for _ in values)})", tuple(values.values()))
        connection.execute("DELETE FROM ai_metrics WHERE created<?", (time.time()-max(1, int(os.getenv("FUSION_AI_METRICS_RETENTION_DAYS", "30")))*86400,))
        connection.commit()


class AIResources:
    def __init__(self, tenant):
        # The caller obtains tenant identity from Java/server config, never chat text.
        self.tenant = str(tenant)
        self.scope = hashlib.sha256(self.tenant.encode()).hexdigest()[:24]
        self.prefix = os.getenv("FUSION_AI_KEY_PREFIX", "fusion:ai:runtime-v1")+":{ai-admission}:"
        self.ticket = uuid.uuid4().hex
        self.keys = [self.prefix+"active", self.prefix+"active:"+self.scope,
                     self.prefix+"waiting", self.prefix+"waiting:"+self.scope]
        self.client = redis.Redis.from_url(os.getenv("FUSION_REDIS_URL", "redis://127.0.0.1:6379/0"), decode_responses=True,
                    socket_connect_timeout=1, socket_timeout=1, retry=Retry(NoBackoff(), 0))
        self.active = False

    async def call(self, script, keys, *args):
        try:
            return await self.client.eval(script, len(keys), *keys, *args)
        except RedisError:
            raise ResourceUnavailable("AI resource service unavailable") from None

    async def acquire(self):
        wait = max(0.1, min(10, float(os.getenv("FUSION_AI_WAIT_SECONDS", "3"))))
        now = time.time()
        joined = await self.call(JOIN, self.keys, self.ticket, now,
                                int(os.getenv("FUSION_AI_GLOBAL_WAITING", "64")), int(os.getenv("FUSION_AI_TENANT_WAITING", "16")), now+wait+1)
        if not joined:
            metric(self.tenant, "admission", "queue_full")
            raise ResourceUnavailable("AI waiting queue full")
        until = time.monotonic()+wait
        while time.monotonic()<until:
            result = await self.call(ACQUIRE, self.keys, self.ticket, time.time(),
                                    int(os.getenv("FUSION_AI_GLOBAL_CONCURRENCY", "24")), int(os.getenv("FUSION_AI_TENANT_CONCURRENCY", "6")), time.time()+30)
            if result == 1:
                self.active = True
                return
            if result == -1:
                break
            await asyncio.sleep(0.05)
        metric(self.tenant, "admission", "wait_timeout")
        raise ResourceUnavailable("AI admission wait timed out")

    async def renew(self):
        if not await self.call(RENEW, self.keys[:2], self.ticket, time.time(), time.time()+30):
            raise ResourceUnavailable("AI concurrency lease lost")

    def budget_keys(self):
        day = datetime.now(timezone.utc).strftime("%Y%m%d")
        return [self.prefix+"tokens:"+day, self.prefix+"tokens:"+day+":"+self.scope]

    async def reserve(self, amount):
        await self.renew()
        keys = self.budget_keys()
        if not await self.call(RESERVE, keys, amount, int(os.getenv("FUSION_AI_GLOBAL_DAILY_TOKENS", "10000000")), int(os.getenv("FUSION_AI_TENANT_DAILY_TOKENS", "2000000"))):
            metric(self.tenant, "provider", "budget_exhausted")
            raise ResourceUnavailable("AI daily token budget exhausted")
        return keys

    async def close(self):
        try:
            await self.call(RELEASE, self.keys, self.ticket)
        except ResourceUnavailable:
            pass  # Expiring leases recover capacity; never switch to local quotas.
        finally:
            await self.client.aclose()


@asynccontextmanager
async def ai_scope(tenant, *, enabled=True):
    if not enabled or CURRENT.get() is not None:
        yield CURRENT.get()
        return
    resources = AIResources(tenant)
    heartbeat = None
    token = None
    parent = asyncio.current_task()
    async def keepalive():
        while True:
            await asyncio.sleep(10)
            try:
                await resources.renew()
            except ResourceUnavailable:
                parent.cancel()
                return
    try:
        await resources.acquire()
        token = CURRENT.set(resources)
        heartbeat = asyncio.create_task(keepalive())
        yield resources
    finally:
        if heartbeat:
            heartbeat.cancel()
            await asyncio.gather(heartbeat, return_exceptions=True)
        if token is not None:
            CURRENT.reset(token)
        await resources.close()


async def provider_post(client, endpoint, *, headers, json, operation):
    resources = CURRENT.get()
    if resources is None:
        # Direct evaluation/unit calls are admitted too; no unmetered provider path.
        async with ai_scope(os.getenv("FUSION_TENANT", "demo")):
            return await provider_post(client, endpoint, headers=headers, json=json, operation=operation)
    # UTF-8 bytes + output limit conservatively bounds normal tokenizer input.
    import json as json_module
    estimate = len(json_module.dumps(json.get("messages", json.get("input", [])), ensure_ascii=False).encode())+int(json.get("max_tokens", 0))
    estimate = max(1, estimate)
    keys = await resources.reserve(estimate)
    started = time.monotonic()
    charged = estimate  # Ambiguous timeout consumes reservation conservatively.
    status = None
    outcome = "transport_error"
    call_id = uuid.uuid4().hex
    metadata = {"call_id": call_id, "provider": provider_id(endpoint), "model": safe_label(json.get("model")),
                "prompt_version": digest([message.get("content") for message in json.get("messages", []) if message.get("role") == "system"]) if json.get("messages") else ""}
    input_tokens = output_tokens = None
    usage_source = "estimated"
    try:
        response = await client.post(endpoint, headers=headers, json=json)
        response.extensions["ai_tracking"] = metadata
        status = response.status_code
        if status >= 400:
            charged = 0
            usage_source = "none"
            outcome = "rate_limited" if status == 429 else "provider_error"
            response.raise_for_status()
        try:
            body = response.json()
        except ValueError:
            outcome = "invalid_response"
            raise
        if not isinstance(body, dict):
            outcome = "invalid_response"
            raise ValueError("Invalid provider response")
        usage = body.get("usage", {})
        if not isinstance(usage, dict):
            outcome = "invalid_response"
            raise ValueError("Invalid provider usage response")
        def count(value): return value if isinstance(value, int) and not isinstance(value, bool) and value >= 0 else None
        input_tokens = count(usage.get("prompt_tokens", usage.get("input_tokens")))
        output_tokens = count(usage.get("completion_tokens", usage.get("output_tokens")))
        total = count(usage.get("total_tokens"))
        if total is None and input_tokens is not None and output_tokens is not None: total = input_tokens + output_tokens
        charged = total if total is not None else estimate
        usage_source = "provider" if total is not None else "estimated"
        outcome = "ok"
        return response
    finally:
        try:
            await resources.call(SETTLE, keys, charged-estimate)
        finally:
            metric(resources.tenant, operation, outcome, elapsed=time.monotonic()-started, tokens=charged, status=status,
                   kind="provider", input_tokens=input_tokens, output_tokens=output_tokens, usage_source=usage_source, **metadata)


async def dependency_health():
    resources = AIResources("health")
    try:
        await resources.client.ping()
        status = "ok"
    except RedisError:
        status = "unavailable"
    finally:
        await resources.client.aclose()
    return {"redis_ai_admission": status, "execution_storage": "sqlite_same_host", "model_calls_fail_closed": True}
