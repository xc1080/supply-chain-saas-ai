"""Shared visitor state. Redis is authoritative; SQLite is a one-time import source.

Cart changes and import/revocation are atomic across facade instances. Chat,
Agent checkpoints and legacy order records deliberately remain outside this store.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import math
import os
import re
import secrets
import sqlite3
import time
from functools import wraps
from pathlib import Path

import redis.asyncio as redis
from redis.exceptions import RedisError
from redis.backoff import NoBackoff
from redis.retry import Retry
from fastapi import HTTPException

SESSION_TTL = 30 * 86400


def redis_required(method):
    @wraps(method)
    async def wrapped(*args, **kwargs):
        try:
            return await method(*args, **kwargs)
        except RedisError as error:
            # Never expose connection URLs/passwords or fall back to local state.
            raise HTTPException(503, "会话服务暂时不可用，请稍后重试") from error
    return wrapped


IMPORT = """
if redis.call('EXISTS',KEYS[2]) == 1 then return 0 end
redis.call('SET',KEYS[2],'done','EX',ARGV[1])
if tonumber(ARGV[2]) <= 0 then return 0 end
redis.call('HSET',KEYS[1],'expires',ARGV[3],'context_product',ARGV[4],'keywords',ARGV[5])
redis.call('EXPIRE',KEYS[1],ARGV[2])
local rows=cjson.decode(ARGV[6])
for _,row in ipairs(rows) do redis.call('HSET',KEYS[3],row.product_id,cjson.encode(row)) end
if #rows > 0 then redis.call('EXPIRE',KEYS[3],ARGV[2]) end
return 1
"""

CREATE = """
if redis.call('EXISTS',KEYS[2]) == 1 then return 0 end
redis.call('HSET',KEYS[1],'expires',ARGV[1],'context_product','','keywords','[]')
redis.call('EXPIRE',KEYS[1],ARGV[2])
redis.call('SET',KEYS[2],'done','EX',ARGV[2])
return 1
"""

LOOKUP = """
return {redis.call('HGET',KEYS[1],'expires') or '',redis.call('EXISTS',KEYS[2])}
"""

REVOKE = """
redis.call('DEL',KEYS[1],KEYS[3],KEYS[4],KEYS[5],KEYS[6])
redis.call('SET',KEYS[2],'revoked','EX',ARGV[1])
return 1
"""

ALIVE = """
local ttl=redis.call('TTL',KEYS[1])
if ttl <= 0 then return {-401} end
"""

CART_ADD = ALIVE + """
local previous=redis.call('HGET',KEYS[3],ARGV[1])
local row={id=ARGV[4],product_id=ARGV[1],quantity=0,created=ARGV[5]}
if previous then row=cjson.decode(previous) end
local count=tonumber(row.quantity)+tonumber(ARGV[2])
if count < 1 or count > tonumber(ARGV[3]) then return {-409} end
if not previous and redis.call('HLEN',KEYS[3]) >= 200 then return {-422} end
row.quantity=count
redis.call('HSET',KEYS[3],ARGV[1],cjson.encode(row))
redis.call('EXPIRE',KEYS[3],ttl)
return {count}
"""

CART_DELETE = ALIVE + """
local entries=redis.call('HGETALL',KEYS[3])
for i=1,#entries,2 do
  local row=cjson.decode(entries[i+1])
  if row.id == ARGV[1] then redis.call('HDEL',KEYS[3],entries[i]);return {1} end
end
return {0}
"""

CART_CONSUME = ALIVE + """
if redis.call('HEXISTS',KEYS[4],ARGV[1]) == 1 then return {0} end
local quantities=cjson.decode(ARGV[2])
for pid,count in pairs(quantities) do
  local previous=redis.call('HGET',KEYS[3],pid)
  if previous then
    local row=cjson.decode(previous)
    row.quantity=tonumber(row.quantity)-tonumber(count)
    if row.quantity <= 0 then redis.call('HDEL',KEYS[3],pid)
    else redis.call('HSET',KEYS[3],pid,cjson.encode(row)) end
  end
end
redis.call('HSET',KEYS[4],ARGV[1],'1')
redis.call('EXPIRE',KEYS[4],ttl)
return {1}
"""

KEYWORDS = ALIVE + """
local words=cjson.decode(redis.call('HGET',KEYS[1],'keywords') or '[]')
local result={}
if ARGV[1] == 'save' and ARGV[2] ~= '' then table.insert(result,ARGV[2]) end
if ARGV[1] ~= 'clear' then
  for _,word in ipairs(words) do
    if (ARGV[1] == 'load' or word ~= ARGV[2]) and #result < 10 then table.insert(result,word) end
  end
end
local encoded='[]'
if #result > 0 then encoded=cjson.encode(result) end
if ARGV[1] ~= 'load' then redis.call('HSET',KEYS[1],'keywords',encoded) end
return {encoded}
"""

CONTEXT = ALIVE + """
if ARGV[1] == 'set' then redis.call('HSET',KEYS[1],'context_product',ARGV[2]) end
return {redis.call('HGET',KEYS[1],'context_product') or ''}
"""

CART_LIST = ALIVE + "return redis.call('HVALS',KEYS[3])"

ADDRESSES = ALIVE + """
if redis.call('EXISTS',KEYS[6]) == 0 then
  if ARGV[1] ~= '' then redis.call('HSET',KEYS[5],'demo-address',ARGV[1]);redis.call('EXPIRE',KEYS[5],ttl) end
  redis.call('SET',KEYS[6],'done','EX',ttl)
end
local operation=ARGV[2]
local id=ARGV[3]
if operation == 'save' or operation == 'update' then
  if operation == 'update' and redis.call('HEXISTS',KEYS[5],id) == 0 then return {-404} end
  if operation == 'save' and redis.call('HLEN',KEYS[5]) >= 20 then return {-420} end
  local row=cjson.decode(ARGV[4])
  if redis.call('HLEN',KEYS[5]) == 0 then row.defaultType=1 end
  if row.defaultType == 1 then
    local rows=redis.call('HGETALL',KEYS[5])
    for i=1,#rows,2 do local old=cjson.decode(rows[i+1]);old.defaultType=0;redis.call('HSET',KEYS[5],rows[i],cjson.encode(old)) end
  end
  redis.call('HSET',KEYS[5],id,cjson.encode(row));redis.call('EXPIRE',KEYS[5],ttl)
elseif operation == 'delete' or operation == 'default' then
  if redis.call('HEXISTS',KEYS[5],id) == 0 then return {-404} end
  if operation == 'delete' then redis.call('HDEL',KEYS[5],id)
  else
    local rows=redis.call('HGETALL',KEYS[5])
    for i=1,#rows,2 do local row=cjson.decode(rows[i+1]);row.defaultType=rows[i] == id and 1 or 0;redis.call('HSET',KEYS[5],rows[i],cjson.encode(row)) end
  end
end
local values=redis.call('HVALS',KEYS[5])
local hasDefault=false
for _,value in ipairs(values) do if cjson.decode(value).defaultType == 1 then hasDefault=true end end
if not hasDefault and #values > 0 then
  local row=cjson.decode(values[1]);row.defaultType=1;values[1]=cjson.encode(row);redis.call('HSET',KEYS[5],row.addressId,values[1])
end
return values
"""


class RedisSharedStore:
    def __init__(self, legacy_path: Path, *, tenant=None, shop=None, prefix=None, url=None):
        self.legacy_path = Path(legacy_path)
        self.tenant = tenant or os.getenv("FUSION_TENANT", "demo")
        self.shop = shop or os.getenv("FUSION_SHOP_ID", "default")
        self.prefix = prefix or os.getenv("FUSION_STORE_KEY_PREFIX", "fusion:store:v1")
        if not all(re.fullmatch(r"[A-Za-z0-9_:-]{1,100}", value) for value in (self.tenant, self.shop, self.prefix)):
            raise ValueError("Invalid server-side visitor state namespace")
        self.url = url or os.getenv("FUSION_REDIS_URL", "redis://127.0.0.1:6379/0")
        self._client = None

    async def start(self):
        if self._client is None:
            self._client = redis.Redis.from_url(self.url, decode_responses=True, protocol=2,
                                               max_connections=128, socket_connect_timeout=2,
                                               socket_timeout=2, retry=Retry(NoBackoff(), 0))

    async def get_client(self):
        await self.start()
        return self._client

    async def close(self):
        client, self._client = self._client, None
        if client is not None:
            await client.aclose()

    def keys(self, session):
        # Same Redis Cluster hash slot for each session's atomic scripts.
        base = f"{self.prefix}:{{{self.tenant}:{self.shop}:{session}}}"
        return [base + suffix for suffix in (":session", ":migrated", ":cart", ":cart-checkouts", ":addresses", ":addresses-initialized")]

    def _legacy_snapshot(self, session):
        # Pre-shop sessions belonged to the default shop only. A cookie must not
        # become a valid identity in another shop through legacy import.
        if self.shop != "default" or not self.legacy_path.exists():
            return None
        connection = sqlite3.connect(self.legacy_path.resolve().as_uri() + "?mode=ro", uri=True, timeout=2)
        connection.row_factory = sqlite3.Row
        try:
            row = connection.execute("SELECT * FROM sessions WHERE id=? AND expires>?", (session, time.time())).fetchone()
            if not row:
                return None
            carts = connection.execute("SELECT id,product_id,quantity,rowid AS created FROM carts WHERE session_id=? ORDER BY rowid DESC LIMIT 200", (session,)).fetchall()
            return dict(row), [dict(cart) for cart in carts]
        except sqlite3.OperationalError as error:
            if "no such table" in str(error):
                return None
            raise HTTPException(503, "历史会话迁移暂时不可用，请稍后重试") from error
        finally:
            connection.close()

    @redis_required
    async def valid_session(self, value):
        if not value or not re.fullmatch(r"[a-f0-9]{48}", value):
            return None
        client = await self.get_client()
        keys = self.keys(value)
        expires, migrated = await client.eval(LOOKUP, 2, keys[0], keys[1])
        if expires:
            return value if float(expires) > time.time() else None
        if migrated:
            return None
        # Slow import happens once per old cookie, never on the normal read path.
        snapshot = await asyncio.to_thread(self._legacy_snapshot, value)
        if snapshot is None:
            return None
        session, carts = snapshot
        expires = float(session["expires"])
        ttl = max(0, math.ceil(expires - time.time()))
        await client.eval(IMPORT, 3, keys[0], keys[1], keys[2], SESSION_TTL, ttl, expires,
                          session.get("context_product") or "", session.get("keywords") or "[]",
                          json.dumps(carts, ensure_ascii=False))
        # Another instance may have logged out while SQLite was being read.
        current = await client.hget(keys[0], "expires")
        return value if current is not None and float(current) > time.time() else None

    @redis_required
    async def create_session(self):
        client = await self.get_client()
        for _ in range(3):
            session = secrets.token_hex(24)
            keys = self.keys(session)
            if await client.eval(CREATE, 2, keys[0], keys[1], time.time() + SESSION_TTL, SESSION_TTL):
                return session
        raise HTTPException(503, "暂时无法创建访客会话，请稍后重试")

    @redis_required
    async def logout(self, session):
        return await (await self.get_client()).eval(REVOKE, 6, *self.keys(session), SESSION_TTL)

    @redis_required
    async def _eval(self, script, session, *args):
        result = await (await self.get_client()).eval(script, 6, *self.keys(session), *args)
        if result and result[0] == -401:
            raise HTTPException(401, "请重新进入演示商城")
        if result and result[0] == -409:
            raise HTTPException(409, "购买数量超出当前可售库存，请调整数量")
        if result and result[0] == -422:
            raise HTTPException(422, "购物车商品过多，请先清理")
        if result and result[0] == -404:
            raise HTTPException(404, "收货地址不存在或无权访问")
        if result and result[0] == -420:
            raise HTTPException(422, "最多保存20个收货地址，请先删除不再使用的地址")
        return result

    async def add_cart(self, session, product_id, delta, available_stock):
        return await self._eval(CART_ADD, session, product_id, delta, available_stock,
                                secrets.token_hex(8), time.time_ns())

    async def delete_cart(self, session, cart_id):
        return await self._eval(CART_DELETE, session, cart_id)

    async def carts(self, session):
        values = await self._eval(CART_LIST, session)
        return sorted((json.loads(value) for value in values), key=lambda row: int(row.get("created", 0)), reverse=True)

    async def consume_cart(self, session, request_key, quantities):
        # Replayed successful order requests must not remove newly added items.
        operation = hashlib.sha256(request_key.encode()).hexdigest()
        return await self._eval(CART_CONSUME, session, operation, json.dumps(quantities))

    async def keywords(self, session, operation="load", word=""):
        return json.loads((await self._eval(KEYWORDS, session, operation, word))[0])

    async def context(self, session, value=None, *, update=False):
        result = await self._eval(CONTEXT, session, "set" if update else "get", value or "")
        return result[0] or None

    async def addresses(self, session, operation="load", address_id="", address=None):
        # A one-time sample for local demo visitors; deleting it must never recreate it.
        seed_demo = os.getenv("FUSION_SEED_DEMO_ADDRESS", "1" if self.tenant == "demo" else "0") == "1"
        sample = {"addressId": "demo-address", "addressee": "演示收货人", "phone": "00000000000",
                  "address": "智能家居演示空间（虚构地址，不寄送实物）", "defaultType": 1, "sample": True}
        values = await self._eval(ADDRESSES, session, json.dumps(sample, ensure_ascii=False) if seed_demo else "",
                                  operation, address_id, json.dumps(address or {}, ensure_ascii=False))
        return sorted((json.loads(value) for value in values), key=lambda row: (row.get("defaultType") != 1, row["addressId"]))

    async def shipping_address(self, session, address_id):
        rows = await self.addresses(session)
        row = next((row for row in rows if row["addressId"] == address_id), None)
        if row is None:
            raise HTTPException(422, "请选择当前账户的收货地址")
        # Copy only the server-owned snapshot, never arbitrary client-supplied metadata.
        return {key: row[key] for key in ("addressee", "phone", "address")}
