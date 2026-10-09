"""Snapshot local databases and verify restoration into a newly created isolated database.
Reports contain hashes/counts, never SQL dump contents, credentials or customer records.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import time
import uuid
from bootstrap_tenants import ROOT, MYSQL, CONFIG, sql

DATABASES = {"demo": "ksdatabase", "studio": "ksdatabase_studio"}
DUMP = MYSQL.parent / "mysqldump.exe"

def mysql_command(query, database=None, *, input_path=None):
    arguments = [str(MYSQL), "--defaults-extra-file=" + str(CONFIG), "--default-character-set=utf8mb4", "-N", "-B"]
    if database:
        arguments.append(database)
    if input_path:
        with input_path.open("rb") as handle:
            result = subprocess.run(arguments, stdin=handle, capture_output=True, timeout=180)
    else:
        result = subprocess.run(arguments, input=query.encode(), capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError("Restore verification SQL failed; check database permissions and schema compatibility")
    return result.stdout.decode("utf-8")

def dump_database(database, destination, *, data_only=False):
    arguments = [str(DUMP), "--defaults-extra-file=" + str(CONFIG),
        "--single-transaction", "--skip-comments", "--skip-dump-date", "--skip-add-locks",
        "--skip-disable-keys", "--skip-extended-insert", "--complete-insert", "--hex-blob",
        "--order-by-primary", "--set-gtid-purged=OFF", "--no-tablespaces",
        "--default-character-set=utf8mb4"]
    if data_only:
        arguments.append("--no-create-info")
    arguments.append(database)  # No --databases: restore must not embed USE or CREATE DATABASE.
    with destination.open("wb") as handle:
        result = subprocess.run(arguments, stdout=handle, stderr=subprocess.PIPE, timeout=180)
    if result.returncode:
        raise RuntimeError("Snapshot creation failed; backup was not verified")

def row_fingerprint(path):
    digest = hashlib.sha256()
    counts = {}
    with path.open("rb") as handle:
        for line in handle:
            if line.startswith(b"INSERT INTO "):
                digest.update(line)
                parts = line.split(bytes([96]), 2)
                if len(parts) == 3:
                    name = parts[1].decode("utf-8")
                    counts[name] = counts.get(name, 0) + 1
    return {"sha256": digest.hexdigest(), "rows": sum(counts.values()), "tablesWithRows": len(counts)}

def safe_restore_target(name):
    if not re.fullmatch(r"verify_backup_[a-f0-9]{20}", name):
        raise ValueError("Restore destination must be an internally generated isolated database")
    if name in DATABASES.values():
        raise ValueError("Refusing live tenant destination")

def verify_mysql(tenant, directory):
    database = DATABASES[tenant]
    snapshot = directory / (tenant + ".sql")
    dump_database(database, snapshot)
    expected = row_fingerprint(snapshot)
    destination = "verify_backup_" + uuid.uuid4().hex[:20]
    safe_restore_target(destination)
    if sql(f"SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{destination}'"):
        raise RuntimeError("Restore destination already exists")
    created = False
    begin = time.monotonic()
    try:
        mysql_command(f"CREATE DATABASE {destination} CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
        created = True
        mysql_command("", destination, input_path=snapshot)
        restored_dump = directory / (tenant + "-restored-data.sql")
        dump_database(destination, restored_dump, data_only=True)
        observed = row_fingerprint(restored_dump)
        if expected != observed:
            raise RuntimeError("Restored row fingerprint differs from the captured snapshot")
        tables = mysql_command("SHOW TABLES", destination).splitlines()
        return {"tenant": tenant, "snapshot": str(snapshot), "fileSha256": hashlib.sha256(snapshot.read_bytes()).hexdigest(),
                "restoredRowFingerprint": observed, "tables": len(tables),
                "restoreSeconds": round(time.monotonic() - begin, 3), "verified": True,
                "isolatedDestination": destination, "liveDatabaseOverwritten": False}
    finally:
        if created:
            safe_restore_target(destination)
            mysql_command(f"DROP DATABASE {destination}")

def verify_sqlite(path, directory):
    destination = directory / path.name
    with sqlite3.connect(path) as source, sqlite3.connect(destination) as target:
        source.backup(target)
        if target.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise RuntimeError("SQLite snapshot integrity failed")
        tables = target.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
        rows = {}
        for (name,) in tables:
            if not re.fullmatch(r"[A-Za-z0-9_]+", name):
                raise RuntimeError("Unexpected SQLite table name")
            rows[name] = target.execute('SELECT COUNT(*) FROM "' + name + '"').fetchone()[0]
    return {"database": path.name, "snapshot": str(destination), "tables": len(tables),
            "rows": sum(rows.values()), "integrity": "ok", "verified": True}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tenant", choices=["demo", "studio", "all"], default="all")
    args = parser.parse_args()
    directory = ROOT / "runtime/backups" / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])
    directory.mkdir(parents=True)
    report = {"verified": False, "createdAt": datetime.now(timezone.utc).isoformat(), "mysql": [], "sqlite": [],
              "coverage": "Local logical snapshot restore; not offsite backup or point-in-time recovery"}
    try:
        for tenant in DATABASES if args.tenant == "all" else [args.tenant]:
            report["mysql"].append(verify_mysql(tenant, directory))
        for name in ("store-demo.sqlite3", "store-studio.sqlite3", "agent-runs.sqlite3", "ai-metrics.sqlite3"):
            path = ROOT / "runtime" / name
            if path.exists():
                report["sqlite"].append(verify_sqlite(path, directory))
        report["verified"] = True
    finally:
        (directory / "restore-report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({"verified": report["verified"], "report": str(directory / "restore-report.json"),
                      "mysqlTenants": len(report["mysql"]), "sqliteDatabases": len(report["sqlite"])},ensure_ascii=False))

if __name__ == "__main__":
    main()
