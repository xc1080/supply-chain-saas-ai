"""Verify real account revocation using only uniquely owned staff fixtures.

No inventory/order changes. Fixture accounts are disabled and their bindings
revoked afterwards; login audit records remain. Credentials never enter reports.
"""
import asyncio
import json
import secrets
import time
from pathlib import Path

import bcrypt
import httpx
from bootstrap_tenants import sql


async def run():
    checks, fixtures = [], []
    def check(name, condition):
        checks.append({"name": name, "passed": bool(condition)})
        if not condition:
            raise RuntimeError(name)
    async with httpx.AsyncClient(base_url="http://127.0.0.1:8035", timeout=15) as client:
        async def login(name, password):
            return (await client.post("/login", json={"username": name, "password": password})).json()
        async def call(method, path, token, body=None):
            response = await client.request(method, path, headers={"Authorization": "Bearer " + token, "X-Shop-ID": "default"}, json=body)
            return response.json()
        try:
            for tenant, database, admin_id, admin_name in (("demo", "ksdatabase", 1, "admin"), ("studio", "ksdatabase_studio", 101, "studio_admin")):
                admin = await login(admin_name, "admin123")
                check(tenant + ":admin-login", bool(admin.get("token")))
                user_id = 700000000 + secrets.randbelow(100000000)
                name = "verify_" + secrets.token_hex(5)
                password, replacement = "FixtureLogin123", "FixtureReset456"
                hashed = bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()
                department = sql(f"SELECT COALESCE(dept_id,0) FROM {database}.sys_user WHERE user_id={admin_id}")
                check(tenant + ":unique-fixture", sql(f"SELECT COUNT(*) FROM ksdatabase.sys_user WHERE user_id={user_id} OR user_name='{name}'") == "0")
                fixtures.append((tenant, database, user_id, name))
                sources = ["ksdatabase"] if database == "ksdatabase" else ["ksdatabase", database]
                for source in sources:
                    sql(f"INSERT INTO {source}.sys_user(user_id,dept_id,user_name,nick_name,password,status,del_flag,create_by) VALUES({user_id},{department},'{name}','Verification staff','{hashed}','0','0','session-verification'); INSERT INTO {source}.sys_user_role VALUES({user_id},9002)")
                sql(f"INSERT INTO ksdatabase.commerce_tenant_binding VALUES({user_id},'{tenant}',1); INSERT INTO {database}.commerce_shop_member VALUES('default',{user_id},'SUPPLY_REVIEWER')")
                first = await login(name, password)
                check(tenant + ":staff-login", bool(first.get("token")))
                token = first["token"]
                check(tenant + ":staff-inventory", (await call("GET", "/commerce/inventory", token))["code"] == 200)
                check(tenant + ":disable", (await call("PUT", "/system/user/changeStatus", admin["token"], {"userId": user_id, "status": "1"}))["code"] == 200)
                check(tenant + ":disabled-old-token", (await call("GET", "/commerce/inventory", token))["code"] == 401)
                check(tenant + ":disabled-new-login", not (await login(name, password)).get("token"))
                check(tenant + ":enable", (await call("PUT", "/system/user/changeStatus", admin["token"], {"userId": user_id, "status": "0"}))["code"] == 200)
                check(tenant + ":old-token-not-resurrected", (await call("GET", "/commerce/context", token))["code"] == 401)
                second = await login(name, password)
                check(tenant + ":enabled-login", bool(second.get("token")))
                check(tenant + ":reset-password", (await call("PUT", "/system/user/resetPwd", admin["token"], {"userId": user_id, "password": replacement}))["code"] == 200)
                check(tenant + ":reset-revokes-old-token", (await call("GET", "/commerce/inventory", second["token"]))["code"] == 401)
                check(tenant + ":old-password-rejected", not (await login(name, password)).get("token"))
                fresh = await login(name, replacement)
                check(tenant + ":new-password-login", bool(fresh.get("token")))
                check(tenant + ":new-session-authorized", (await call("GET", "/commerce/inventory", fresh["token"]))["code"] == 200)
                # Remove only this fixture's legacy role: the cached privileged
                # snapshot must be rejected even though membership still exists.
                sql(f"DELETE FROM {database}.sys_user_role WHERE user_id={user_id} AND role_id=9002")
                check(tenant + ":role-change-revokes-token", (await call("GET", "/commerce/context", fresh["token"]))["code"] == 401)
        finally:
            for tenant, database, user_id, name in fixtures:
                for source in ({"ksdatabase", database}):
                    sql(f"UPDATE {source}.sys_user SET status='1',del_flag='2' WHERE user_id={user_id} AND user_name='{name}' AND create_by='session-verification'")
                sql(f"UPDATE ksdatabase.commerce_tenant_binding SET enabled=0 WHERE user_id={user_id} AND tenant_id='{tenant}'; DELETE FROM {database}.commerce_shop_member WHERE user_id={user_id} AND member_role='SUPPLY_REVIEWER'")
    report = {"passed": all(c["passed"] for c in checks), "checks": checks, "count": len(checks), "time": time.time(), "inventoryMutations": False}
    target = Path(__file__).resolve().parent.parent / "logs" / "account-revocation-result.json"
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"passed": report["passed"], "checks": len(checks), "inventoryMutations": False}))


if __name__ == "__main__":
    asyncio.run(run())
