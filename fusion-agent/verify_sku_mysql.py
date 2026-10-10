"""Verify MySQL RR current-read semantics in a newly created disposable schema.

This checks the database primitive, not a substitute for business API acceptance.
Only the internally generated isolated schema is created/dropped; tenant data is untouched.
"""
import json
import re
import subprocess
import time
import uuid
from pathlib import Path
from backup_restore_drill import mysql_command
from bootstrap_tenants import MYSQL, CONFIG, ROOT


def run():
    schema = "verify_sku_" + uuid.uuid4().hex[:20]
    if not re.fullmatch(r"verify_sku_[a-f0-9]{20}", schema):
        raise ValueError("Unsafe isolated verification schema")
    if mysql_command("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='" + schema + "'").strip():
        raise RuntimeError("Verification schema already exists")
    marker = "sku_probe_" + uuid.uuid4().hex
    report = {"kind": "MYSQL_RR_PRIMITIVE", "liveTenantWritten": False, "passed": False}
    reader = None
    try:
        mysql_command("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
        mysql_command("CREATE TABLE product(product_id BIGINT PRIMARY KEY,product_code VARCHAR(64)) ENGINE=InnoDB;"
                      "CREATE TABLE commerce_sku(product_id BIGINT PRIMARY KEY,attributes_json TEXT) ENGINE=InnoDB;", schema)
        query = ("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;START TRANSACTION;"
                 "SELECT 'initial_code',COUNT(*) FROM product WHERE product_code='OWNED';"
                 "SELECT 'initial_binding',COUNT(*) FROM commerce_sku WHERE product_id=1;"
                 "DO GET_LOCK('" + marker + "',0);DO SLEEP(3);"
                 "SELECT 'old_code_snapshot',COUNT(*) FROM product WHERE product_code='OWNED';"
                 "SELECT 'old_binding_snapshot',COUNT(*) FROM commerce_sku WHERE product_id=1;"
                 "SELECT 'current_code',product_id FROM product WHERE product_code='OWNED' FOR UPDATE;"
                 "SELECT 'current_binding',product_id FROM commerce_sku WHERE product_id=1 FOR UPDATE;"
                 "ROLLBACK;DO RELEASE_LOCK('" + marker + "');")
        reader = subprocess.Popen([str(MYSQL), "--defaults-extra-file=" + str(CONFIG), "-N", "-B", schema],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        reader.stdin.write(query.encode("utf-8"))
        reader.stdin.close()
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            if mysql_command("SELECT IS_USED_LOCK('" + marker + "')").strip() != "NULL":
                break
            time.sleep(.05)
        else:
            raise RuntimeError("Reader never established its RR snapshot")
        mysql_command("INSERT INTO product VALUES(1,'OWNED');"
                      "INSERT INTO commerce_sku VALUES(1,'{}');", schema)
        reader.wait(timeout=10)
        output, error = reader.stdout.read().decode("utf-8"), reader.stderr.read()
        if reader.returncode or error:
            raise RuntimeError("RR probe reader failed")
        observed = dict(line.split("\t", 1) for line in output.strip().splitlines())
        expected = {"initial_code": "0", "initial_binding": "0", "old_code_snapshot": "0",
                    "old_binding_snapshot": "0", "current_code": "1", "current_binding": "1"}
        if observed != expected:
            raise RuntimeError("MySQL RR current-read result differs from expected")
        report.update(passed=True, checks=observed, mysqlVersion=mysql_command("SELECT VERSION()").strip())
    finally:
        if reader is not None and reader.poll() is None:
            reader.kill()
            reader.wait(timeout=5)
        if not re.fullmatch(r"verify_sku_[a-f0-9]{20}", schema):
            raise ValueError("Refusing unsafe cleanup target")
        mysql_command("DROP DATABASE IF EXISTS " + schema)
        report["isolatedSchemaRemoved"] = True
        (ROOT / "logs/sku-mysql-rr-result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__": run()
