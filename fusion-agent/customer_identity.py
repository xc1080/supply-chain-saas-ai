"""Durable, tenant/shop-scoped customer identity; login cookies stay revocable.

Account rows live in the existing backed-up storefront SQLite database. An
owner seed is never returned to clients. Registration can adopt only the valid
guest principal supplied by the server, preserving the Java SHA-256 owner ID.
"""
from __future__ import annotations

import re
import secrets
import sqlite3
import time

import bcrypt
from fastapi import HTTPException


def initialize_identity(connection):
    connection.executescript("""
        CREATE TABLE IF NOT EXISTS customer_accounts(
            id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL, shop_id TEXT NOT NULL,
            login_name TEXT NOT NULL, display_name TEXT NOT NULL,
            password_hash BLOB NOT NULL, owner_seed TEXT NOT NULL,
            auth_version INTEGER NOT NULL DEFAULT 1, enabled INTEGER NOT NULL DEFAULT 1,
            created_at REAL NOT NULL,
            UNIQUE(tenant_id,shop_id,login_name), UNIQUE(tenant_id,shop_id,owner_seed)
        );
    """)


def login_name(value):
    result = str(value or "").strip().lower()
    if not re.fullmatch(r"[a-z0-9][a-z0-9_.-]{3,31}", result):
        raise HTTPException(422, "账号须为4至32位英文字母、数字、点、下划线或短横线")
    return result


def password_bytes(value):
    if not isinstance(value, str) or not 10 <= len(value) <= 64 or len(value.encode("utf-8")) > 72:
        raise HTTPException(422, "密码须为10至64字，UTF-8编码不超过72字节")
    return value.encode("utf-8")


class CustomerIdentityStore:
    def __init__(self, connection_factory, tenant, shop):
        self.connection_factory, self.tenant, self.shop = connection_factory, tenant, shop
        # Unknown accounts consume the same bcrypt work as a real password check.
        self._dummy_hash = bcrypt.hashpw(secrets.token_bytes(32), bcrypt.gensalt(rounds=12))

    def account(self, account_id, version=None):
        with self.connection_factory() as connection:
            row = connection.execute("SELECT * FROM customer_accounts WHERE id=? AND tenant_id=? AND shop_id=? AND enabled=1",
                                     (account_id, self.tenant, self.shop)).fetchone()
        if row is None or (version is not None and int(row["auth_version"]) != int(version)):
            raise HTTPException(401, "账户会话已失效，请重新登录")
        return dict(row)

    def register(self, name, password, nickname, guest_seed):
        name, encoded = login_name(name), password_bytes(password)
        nickname = str(nickname or name).strip()
        if not 1 <= len(nickname) <= 40 or re.search(r"[\x00-\x1f\x7f]", nickname):
            raise HTTPException(422, "昵称须为1至40字")
        if not re.fullmatch(r"[a-f0-9]{48}", guest_seed):
            raise HTTPException(401, "访客会话已失效")
        account_id, hashed = secrets.token_hex(16), bcrypt.hashpw(encoded, bcrypt.gensalt(rounds=12))
        try:
            with self.connection_factory() as connection:
                connection.execute("INSERT INTO customer_accounts(id,tenant_id,shop_id,login_name,display_name,password_hash,owner_seed,created_at) VALUES(?,?,?,?,?,?,?,?)",
                                   (account_id, self.tenant, self.shop, name, nickname, hashed, guest_seed, time.time()))
        except sqlite3.IntegrityError as error:
            raise HTTPException(409, "该账号或访客记录已绑定，请登录已有账户") from error
        return self.account(account_id)

    def login(self, name, password):
        name, encoded = login_name(name), password_bytes(password)
        with self.connection_factory() as connection:
            row = connection.execute("SELECT * FROM customer_accounts WHERE tenant_id=? AND shop_id=? AND login_name=?",
                                     (self.tenant, self.shop, name)).fetchone()
        matched = bcrypt.checkpw(encoded, bytes(row["password_hash"]) if row else self._dummy_hash)
        if row is None or not matched or not row["enabled"]:
            # Login failures must not trigger the storefront's visitor bootstrap.
            raise HTTPException(403, "账号或密码不正确")
        return dict(row)

    def user(self, account):
        return {"userId": account["id"], "nickName": account["display_name"], "userName": account["login_name"],
                "tenantId": self.tenant, "avatar": "/demo-media/fallback.svg", "demo": True,
                "identityType": "ACCOUNT", "persistentIdentity": True}
