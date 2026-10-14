"""Repeatable MySQL 8 migration and HTTP acceptance in run-owned schemas/processes.

Never reuses a database name or accepts an application port belonging to the demo.
Secrets and generated configuration stay outside the public report directory.
The upgrade starts an archived V11 Boot application before deploying the current
artifact. Legacy business data is synthetic; both binary/source hashes are saved.
"""
from __future__ import annotations

import argparse
import configparser
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import tarfile
import time
from urllib.parse import urlparse
import uuid
import zipfile

import bcrypt
import httpx
import redis

ROOT = Path(__file__).resolve().parent.parent
JAVA_ROOT = ROOT / "ks-inventory-system"
MAINTENANCE = "ruoyi-framework/src/main/java/com/ruoyi/framework/datasource/CommerceMaintenance.java"
RESOURCE_ROOT = "ruoyi-system/src/main/resources"
PROTECTED_PORTS = {8035, 7050, 7051, 5173, 6001, 6002}
HISTORICAL_REF = "f8095f63760a"
HISTORICAL_FIXTURE_KIND = "ARCHIVED_V11_SOURCE_REBUILD_CHECKOUT_BYTES_WITH_SYNTHETIC_DATA"


def extract_migrations(source):
    """Extract the Java list without interpreting nested lambdas or quoted parens."""
    anchor = source.index("new CommerceSchemaMigrator(source).migrate(Arrays.asList(")
    start = source.index("Arrays.asList(", anchor) + len("Arrays.asList(")
    depth, quoted, escaped = 1, False, False
    for index in range(start, len(source)):
        char = source[index]
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char == '(':
            depth += 1
        elif char == ')':
            depth -= 1
            if depth == 0:
                body = source[start:index]
                rows = [(int(v), name) for v, name in re.findall(r'Migration\.resources\(\s*(\d+)\s*,\s*"([^"]+)"', body)]
                if not rows or [v for v, _ in rows] != list(range(1, len(rows) + 1)):
                    raise ValueError("Migration versions must be unique and contiguous from V1")
                return body, rows
    raise ValueError("Unterminated CommerceMaintenance migration list")


def schema_name(run_id, scenario, tenant):
    value = f"verify_engineering_{run_id}_{scenario}_{tenant}"
    if not re.fullmatch(r"verify_engineering_[a-f0-9]{12}_(fresh|upgrade)_(demo|studio)", value):
        raise ValueError("Unsafe verification schema")
    return value


def baseline_sql(text):
    # The original ERP installer creates KsDataBase. Select a new owned schema
    # using the client argument instead; every DROP TABLE is confined to it.
    stripped, count = re.subn(r"(?im)^\s*create\s+DATABASE\s+`KsDataBase`\s*;\s*", "", text)
    if count != 1 or re.search(r"(?im)^\s*(?:USE\s|(?:CREATE|DROP)\s+DATABASE\b)", stripped):
        raise ValueError("Unexpected ERP installer database directive")
    if re.search(r"(?i)(?:`?KsDataBase`?)\s*\.", stripped):
        raise ValueError("Qualified live ERP schema reference in installer")
    return stripped


def clean_environment():
    prefixes = ("FUSION_", "COMMERCE_", "AI_BAILIAN_", "DEEPSEEK_", "OPENAI_", "SPRING_", "LANGSMITH_", "LANGCHAIN_", "UVICORN_", "MYSQL_")
    options = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "MAVEN_OPTS", "MAVEN_ARGS",
               "PYTHONPATH", "PYTHONSTARTUP", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"}
    result = {k: v for k, v in os.environ.items() if not k.upper().startswith(prefixes) and k.upper() not in options}
    result["PYTHONDONTWRITEBYTECODE"] = "1"
    return result


def source_evidence():
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    status = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).splitlines()
    paths = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT).decode("utf-8").split("\0")
    files = {}
    source_extensions = {".java", ".py", ".sql", ".xml", ".yml", ".yaml", ".properties", ".sh", ".ps1", ".json", ".js", ".mjs", ".ts", ".jsx", ".tsx", ".vue", ".html", ".css", ".scss", ".svg", ".txt", ".toml", ".in"}
    for relative in sorted(set(paths)):
        path = ROOT / relative
        if relative and path.is_file() and (path.suffix in source_extensions or path.name in {"Jenkinsfile", "Dockerfile"}):
            if not any(part in {"runtime", "logs", "target", "node_modules", ".venv", "dist", ".git"} for part in path.relative_to(ROOT).parts):
                files[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    digest = hashlib.sha256(json.dumps(files, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return {"gitHead": head, "gitDirty": bool(status), "gitStatus": status,
            "sourceSha256": digest, "sourceFilesCount": len(files), "sourceFiles": files}


class Verification:
    def __init__(self, args):
        self.args = args
        self.run_id = uuid.uuid4().hex[:12]
        self.output = Path(args.output).resolve()
        if self.output.exists() and any(self.output.iterdir()):
            raise ValueError("Report directory must be new or empty")
        self.output.mkdir(parents=True, exist_ok=True)
        self.private = Path(tempfile.mkdtemp(prefix="engineering-" + self.run_id + "-"))
        self.processes, self.sensitive, self.owned_schemas = [], [], set()
        self.report = {"runId": self.run_id, "startedAt": datetime.now(timezone.utc).isoformat(),
                       "passed": False, "checks": [], "phases": {}, "schemas": [],
                       "existingServicesStopped": False, "existingDatabasesWritten": False,
                       "historicalFixture": HISTORICAL_FIXTURE_KIND, "privateDirectory": str(self.private)}
        self.report.update(source_evidence())
        self.admin_password = "Ve" + secrets.token_hex(9)
        self.service_password = secrets.token_hex(10)
        self.db_password = secrets.token_hex(20)
        self.db_user = "ve_" + self.run_id
        self.assertion = secrets.token_urlsafe(48)
        self.webhook = secrets.token_urlsafe(48)
        self.token_secret = secrets.token_urlsafe(48)
        self.sensitive += [self.admin_password, self.service_password, self.db_password,
                           self.assertion, self.webhook, self.token_secret]
        self.db_account_created = False
        self.mysql = args.mysql or shutil.which("mysql")
        if not self.mysql:
            raise ValueError("mysql client is required; specify --mysql")
        self.db_env = clean_environment()
        self.db_host, self.db_port = args.mysql_host, args.mysql_port
        self.db_admin_user = args.mysql_user
        if args.mysql_defaults:
            config = configparser.ConfigParser(interpolation=None)
            config.read(args.mysql_defaults, encoding="utf-8")
            if "client" not in config:
                raise ValueError("MySQL defaults file must contain [client]")
            section = config["client"]
            self.db_host = section.get("host", self.db_host)
            self.db_port = section.getint("port", self.db_port)
            self.db_admin_user = section.get("user", self.db_admin_user)
            admin_password = section.get("password", "")
        else:
            admin_password = os.getenv("VERIFY_MYSQL_PASSWORD", "")
        self.db_env["MYSQL_PWD"] = admin_password
        self.db_login_file = self.private / 'absent-mysql-login-file'
        self.db_env['MYSQL_TEST_LOGIN_FILE'] = str(self.db_login_file)
        if admin_password:
            self.sensitive.append(admin_password)
        if self.db_host not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("MySQL verification must use loopback")

    def redact(self, value):
        for secret in self.sensitive:
            value = value.replace(secret, "[REDACTED]")
        return re.sub(r"(?i)(Bearer\s+)[A-Za-z0-9._-]+", r"\1[REDACTED]", value)

    def save(self):
        self.report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        (self.output / "engineering-result.json").write_text(json.dumps(self.report, ensure_ascii=False, indent=2), encoding="utf-8")

    def check(self, name, condition, evidence=None):
        row = {"name": name, "passed": bool(condition)}
        if evidence is not None:
            row["evidence"] = evidence
        self.report["checks"].append(row)
        self.save()
        if not condition:
            raise RuntimeError(name)

    def command(self, args, name, *, cwd=None, env=None, input=None, expected=0, timeout=180):
        result = subprocess.run(list(map(str, args)), cwd=cwd, env=env, input=input, text=True,
                                encoding="utf-8", errors="replace", capture_output=True, timeout=timeout)
        log = self.redact(result.stdout + result.stderr)
        (self.output / (name + ".log")).write_text(log, encoding="utf-8")
        self.check(name + ".exit", result.returncode == expected, {"exitCode": result.returncode})
        return log

    def sql(self, query, schema=None):
        if schema is not None and schema not in self.owned_schemas:
            raise ValueError("SQL target was not created by this run")
        if self.db_login_file.exists():
            raise ValueError('Unexpected MySQL login file in private verification directory')
        command = [self.mysql, "--no-defaults", "--protocol=TCP", "--default-character-set=utf8mb4",
                   "-h", self.db_host, "-P", str(self.db_port), "-u", self.db_admin_user, "-N", "-B"]
        if schema:
            command.append(schema)
        result = subprocess.run(command, env=self.db_env, input=query, text=True, encoding="utf-8", errors="replace", capture_output=True, timeout=90)
        if result.returncode:
            raise RuntimeError("Isolated SQL failed: " + self.redact(result.stderr)[:700])
        return result.stdout.strip()

    def prepare_build(self):
        # A running Windows JVM can lock target/*.jar. Never build in its checkout.
        self.build = self.private / "java-source"
        shutil.copytree(JAVA_ROOT, self.build, ignore=shutil.ignore_patterns("target", "node_modules", "ks-vue3", ".git", "*.log", "runtime", "logs", ".venv", ".env", ".env.*"))
        self.jar = self.build / "ruoyi-admin/target/ruoyi-admin.jar"
        if self.args.jar:
            shutil.copy2(Path(self.args.jar).resolve(), self.private / "verified-admin.jar")
            self.jar = self.private / "verified-admin.jar"
        else:
            maven = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
            self.command([maven, "-B", "-ntp", "package", "-DskipTests"], "isolated-java-package", cwd=self.build, timeout=900)
        with zipfile.ZipFile(self.jar) as archive:
            if "BOOT-INF/classes/com/ruoyi/RuoYiApplication.class" not in archive.namelist():
                raise ValueError("A Spring Boot executable jar is required, not a thin jar")
            # A supplied binary must contain the exact current migration bytes.
            # Boot's own startup then proves its registered list and checksums.
            bundled_resources = {}
            for member in archive.namelist():
                if member.startswith("BOOT-INF/lib/") and member.endswith(".jar"):
                    with zipfile.ZipFile(io.BytesIO(archive.read(member))) as library:
                        for name in library.namelist():
                            if name.startswith("db/commerce-") and name.endswith(".sql"):
                                bundled_resources.setdefault(name, []).append(library.read(name))
            for resource in (self.build / RESOURCE_ROOT / "db").glob("commerce-*.sql"):
                matches = bundled_resources.get("db/" + resource.name, [])
                if matches != [resource.read_bytes()]:
                    raise ValueError("Supplied jar migration resource differs from source: " + resource.name)
            archive.extractall(self.private / "boot")
        self.classpath = os.pathsep.join([str(self.private / "probe-classes"), str(self.private / "boot/BOOT-INF/classes")]
                                       + [str(p) for p in sorted((self.private / "boot/BOOT-INF/lib").glob("*.jar"))])
        body, self.migrations = extract_migrations((self.build / MAINTENANCE).read_text(encoding="utf-8"))
        self.latest = self.migrations[-1][0]
        self.report.update(latestMigration=self.latest, migrations=[{"version": v, "name": n} for v, n in self.migrations],
                           jarPath=str(self.jar), jarSha256=hashlib.sha256(self.jar.read_bytes()).hexdigest(),
                           artifactSourceBinding="ONLY_MIGRATION_RESOURCE_BYTES_PROVEN" if self.args.jar else "BUILT_FROM_PRIVATE_SOURCE_COPY",
                           sourceDigestScope="Java/Python/frontend/scripts and config source; excludes docs, runtime, logs, targets, dependency/output directories")
        self.historical_resources(body)
        template = (ROOT / "scripts/CommerceMigrationProbe.java.in").read_text(encoding="utf-8")
        probe = self.private / "CommerceMigrationProbe.java"
        probe.write_text(template.replace("__MIGRATIONS__", body), encoding="utf-8")
        (self.private / "probe-classes").mkdir()
        self.command(["javac", "-encoding", "UTF-8", "-cp", self.classpath, "-d", self.private / "probe-classes", probe], "compile-migration-probe")
        self.prepare_historical_build()

    def historical_resources(self, body):
        """Verify archived SQL content, recording the exact raw checksum bytes."""
        evidence = []
        entries = list(re.finditer(r'Migration\.resources\(\s*(\d+)\s*,', body))
        for index, entry in enumerate(entries):
            if int(entry.group(1)) > 11:
                break
            end = entries[index + 1].start() if index + 1 < len(entries) else len(body)
            resources = list(dict.fromkeys(re.findall(r'"(db/[^\"]+\.sql)"', body[entry.start():end])))
            for resource in resources:
                current = (JAVA_ROOT / RESOURCE_ROOT / resource).read_bytes()
                result = subprocess.run(["git", "show", f"{self.args.historical_ref}:ks-inventory-system/{RESOURCE_ROOT}/{resource}"],
                                        cwd=ROOT, capture_output=True)
                row = {"version": int(entry.group(1)), "resource": resource, "historicalRef": self.args.historical_ref,
                       "available": result.returncode == 0, "currentSha256": hashlib.sha256(current).hexdigest()}
                if result.returncode == 0:
                    archived = result.stdout
                    row.update(archivedSha256=hashlib.sha256(archived).hexdigest(),
                               sameSqlIgnoringCheckoutNewlines=archived.replace(b"\r\n", b"\n") == current.replace(b"\r\n", b"\n"))
                    folder = self.private / "historical-v11" / resource
                    folder.parent.mkdir(parents=True, exist_ok=True)
                    folder.write_bytes(archived)
                    self.check("historicalV11ResourceUnchanged." + resource, row["sameSqlIgnoringCheckoutNewlines"])
                evidence.append(row)
        self.report["historicalResourceEvidence"] = evidence

    def prepare_historical_build(self):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_./-]{0,79}", self.args.historical_ref):
            raise ValueError("Invalid historical Git ref")
        old_commit = subprocess.check_output(["git", "rev-parse", "--verify", self.args.historical_ref + "^{commit}"], cwd=ROOT, text=True).strip()
        self.old_build = self.private / "historical-java-source"
        self.old_build.mkdir()
        exported = subprocess.run(["git", "archive", "--format=tar", old_commit + ":ks-inventory-system"], cwd=ROOT, capture_output=True)
        if exported.returncode:
            raise RuntimeError("Historical source archive unavailable; fetch the configured Git ref")
        with tarfile.open(fileobj=io.BytesIO(exported.stdout)) as archive:
            for member in archive.getmembers():
                if member.name.split("/")[0] in {"ks-vue3", "target", "runtime", "logs"}:
                    continue
                target = (self.old_build / member.name).resolve()
                if not target.is_relative_to(self.old_build.resolve()) or member.issym() or member.islnk():
                    raise ValueError("Unsafe historical archive entry")
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                elif member.isfile():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.extractfile(member).read())
        old_body, old_versions = extract_migrations((self.old_build / MAINTENANCE).read_text(encoding="utf-8"))
        self.old_latest = old_versions[-1][0]
        self.check("historicalBinaryIsV11", self.old_latest == 11)
        aligned = []
        for resource in sorted(set(re.findall(r'"(db/[^\"]+\.sql)"', old_body))):
            archived = (self.old_build / RESOURCE_ROOT / resource).read_bytes()
            current = (self.build / RESOURCE_ROOT / resource).read_bytes()
            self.check("historicalBuild.sameSqlContent." + resource, archived.replace(b"\r\n", b"\n") == current.replace(b"\r\n", b"\n"))
            if archived != current:
                # Git archive exports LF, while this checkout may use CRLF or
                # mixed newlines. Preserve the CURRENT raw migration bytes only
                # inside the archived source copy, after equal-content proof.
                # No current SQL resource or history checksum is rewritten.
                (self.old_build / RESOURCE_ROOT / resource).write_bytes(current)
                aligned.append(resource)
        self.old_jar = self.old_build / "ruoyi-admin/target/ruoyi-admin.jar"
        maven = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
        self.command([maven, "-B", "-ntp", "package", "-DskipTests"], "historical-v11-java-package", cwd=self.old_build, timeout=900)
        self.report["historicalApplication"] = {"oldCommit": old_commit, "oldRef": self.args.historical_ref,
                                                "oldJarSha256": hashlib.sha256(self.old_jar.read_bytes()).hexdigest(),
                                                "oldJarPath": str(self.old_jar), "oldVersions": [{"version": v, "name": n} for v, n in old_versions],
                                                "resourceNewlineAlignment": aligned,
                                                "resourceExportPolicy": "ARCHIVED_CONTENT_CURRENT_CHECKOUT_NEWLINES",
                                                "sourceRebuiltFromGit": True, "originalHistoricalReleaseArtifact": False,
                                                "currentResourcesModified": False, "syntheticBusinessData": True}

    def create_schema(self, scenario, tenant):
        name = schema_name(self.run_id, scenario, tenant)
        self.check(name + ".didNotExist", not self.sql(f"SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{name}'"))
        self.sql(f"CREATE DATABASE `{name}` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
        self.owned_schemas.add(name)
        self.report["schemas"].append(name)
        self.sql(f"CREATE TABLE verification_owner(run_id CHAR(12) PRIMARY KEY);INSERT INTO verification_owner VALUES('{self.run_id}');", name)
        self.sql(baseline_sql((JAVA_ROOT / "sql/ks_20240820.sql").read_text(encoding="utf-8")), name)
        self.sql((JAVA_ROOT / "sql/quartz.sql").read_text(encoding="utf-8"), name)
        self.sql(f"GRANT ALL PRIVILEGES ON `{name}`.* TO '{self.db_user}'@'127.0.0.1';"
                 f"GRANT ALL PRIVILEGES ON `{name}`.* TO '{self.db_user}'@'localhost';")
        return name

    def seed_accounts(self, demo, studio):
        human_hash = bcrypt.hashpw(self.admin_password.encode(), bcrypt.gensalt(rounds=10)).decode()
        service_hash = bcrypt.hashpw(self.service_password.encode(), bcrypt.gensalt(rounds=10)).decode()
        self.sensitive.extend([human_hash, service_hash])
        for schema in (demo, studio):
            self.sql(f"UPDATE sys_user SET password='{human_hash}' WHERE user_id=1;"
                     "UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.captchaEnabled';"
                     "INSERT INTO warehouse(warehouse_id,warehouse_name,warehouse_valid) VALUES(1,'Verification only warehouse','0');"
                     "INSERT INTO product_type(product_type_id,parent_id,ancestors,product_type_name,status) VALUES(2,1,'0,1','Verification only','0');"
                     "INSERT IGNORE INTO sys_role_menu SELECT 1,menu_id FROM sys_menu;"
                     "INSERT INTO sys_role(role_id,role_name,role_key,role_sort,data_scope,status,del_flag) VALUES(9001,'Customer proxy','commerce_customer',99,'1','0','0'),(9002,'Reviewer','shop_staff',99,'1','0','0');", schema)
            for user_id, name, role, digest in ((101, "studio_admin", 1, human_hash), (201, "commerce_demo", 9001, service_hash),
                                                (202, "commerce_studio", 9001, service_hash), (301, "demo_supply_reviewer", 9002, human_hash)):
                self.sql(f"INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,user_type,password,status,del_flag,create_time) VALUES({user_id},100,'{name}','Verification fixture','00','{digest}','0','0',CURRENT_TIMESTAMP);"
                         f"INSERT INTO sys_user_role VALUES({user_id},{role});", schema)
            self.sql((JAVA_ROOT / RESOURCE_ROOT / "db/commerce-merchant.sql").read_text(encoding="utf-8"), schema)
            owner = 1 if schema == demo else 101
            proxy = 201 if schema == demo else 202
            self.sql("INSERT INTO commerce_shop(shop_id,shop_name) VALUES('default','Verification shop');"
                     f"INSERT INTO commerce_shop_member VALUES('default',{owner},'OWNER'),('default',{proxy},'CUSTOMER_SERVICE'),('default',301,'SUPPLY_REVIEWER');", schema)
        self.sql("CREATE TABLE commerce_tenant_binding(user_id BIGINT PRIMARY KEY,tenant_id VARCHAR(32) NOT NULL,enabled SMALLINT NOT NULL DEFAULT 1);"
                 "CREATE TABLE commerce_tenant_registry(tenant_id VARCHAR(32) PRIMARY KEY,status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP);"
                 "INSERT INTO commerce_tenant_registry(tenant_id) VALUES('demo'),('studio');"
                 "INSERT INTO commerce_tenant_binding VALUES(1,'demo',1),(101,'studio',1),(201,'demo',1),(202,'studio',1),(301,'demo',1);", demo)

    def jdbc(self, schema):
        return f"jdbc:mysql://{self.db_host}:{self.db_port}/{schema}?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=GMT%2B8&allowMultiQueries=true"

    def probe(self, schema, stop, name, retry=False, inject=False, expected=0):
        environment = clean_environment()
        environment.update(VERIFY_JDBC_URL=self.jdbc(schema), VERIFY_DB_USER=self.db_user, VERIFY_DB_PASSWORD=self.db_password)
        return self.command(["java", "-Dfile.encoding=UTF-8", "-cp", self.classpath, "com.ruoyi.system.service.CommerceMigrationProbe",
                             str(stop), str(retry).lower(), "inject-after-ddl" if inject else "normal", "studio" if schema.endswith("studio") else "demo"],
                            name, env=environment, expected=expected)

    def history(self, schema):
        return self.sql("SELECT version,name,checksum,state,started_at,finished_at,COALESCE(error_type,'') FROM commerce_schema_history ORDER BY version", schema)

    def synthetic_v11(self, schema):
        # The real archived application created this V11 schema; never remove
        # future tables to manufacture an older shape.
        self.check("v11.amountSchemaAbsent." + schema, not self.sql("SHOW TABLES LIKE 'commerce_order_amount'", schema))
        self.sql("INSERT INTO product(product_id,product_code,product_name,product_type,product_specifications,measure_unit,cost_price,univalence,inventory_qty,status,default_warehouse) VALUES(900,'VERIFY-LEGACY','Synthetic V11 product',2,'Legacy','piece',18,99,0,'0','1');"
                 "INSERT INTO head_order_form(systematic_order_form,original_order_form,order_form_type,order_form_status,order_date,warehousing_ids,user_ids,supplier_ids,order_form_amount) VALUES('VERIFY_LEGACY_PO','SYNTHETIC_V11','1','2',CURRENT_DATE,1,1,0,99);"
                 "INSERT INTO detail_order_form(systematic_order_form,product_id,warehousing_id,supplier_id,plan_quantity,univalence,discount,money,cost) VALUES('VERIFY_LEGACY_PO',900,1,0,1,99,100,99,18);"
                 "INSERT INTO commerce_order(order_id,owner_id,request_key,request_hash,status,total_amount,create_time,cancelled_time,shop_id) VALUES('SC_VERIFY_LEGACY',REPEAT('a',64),'legacy-v11',REPEAT('b',64),4,99,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'default');"
                 "INSERT INTO commerce_order_item(order_id,product_id,product_code,product_name,spec,quantity,unit_price,amount) VALUES('SC_VERIFY_LEGACY',900,'VERIFY-LEGACY','Synthetic V11 product','Legacy',1,99,99);"
                 "INSERT INTO commerce_fulfillment_line(order_id,product_id,shipped,returned,released,refunded) VALUES('SC_VERIFY_LEGACY',900,0,0,1,0);", schema)
        return self.legacy_snapshot(schema)

    def legacy_snapshot(self, schema):
        return {"order": self.sql("SELECT order_id,owner_id,request_key,status,total_amount FROM commerce_order WHERE order_id='SC_VERIFY_LEGACY'", schema),
                "purchase": self.sql("SELECT systematic_order_form,product_id,plan_quantity,univalence,money,cost FROM detail_order_form WHERE systematic_order_form='VERIFY_LEGACY_PO'", schema),
                "product": self.sql("SELECT product_id,product_code,inventory_qty,cost_price FROM product WHERE product_id=900", schema)}

    def available_port(self, port):
        if port in PROTECTED_PORTS:
            raise ValueError("Refusing a demo application port")
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", port))

    def start(self, command, name, *, cwd=None, env=None):
        log_path = self.private / (name + ".raw.log")
        handle = log_path.open("wb")
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        process = subprocess.Popen(list(map(str, command)), cwd=cwd, env=env, stdout=handle, stderr=subprocess.STDOUT, creationflags=flags)
        self.processes.append((process, handle, log_path, name))
        return process

    def stop(self, process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)

    def redis_ready(self):
        url = urlparse(self.args.redis_url)
        if url.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("Redis verification must use loopback")
        if self.args.redis_executable:
            self.available_port(url.port or 6379)
            folder = self.private / "redis"
            folder.mkdir()
            self.start([self.args.redis_executable, "--bind", "127.0.0.1", "--port", str(url.port or 6379),
                        "--protected-mode", "yes", "--dir", folder, "--save", "", "--appendonly", "no"], "owned-redis")
        client = redis.Redis.from_url(self.args.redis_url, socket_timeout=2)
        for _ in range(100):
            try:
                if client.ping():
                    # Existing shared Redis DBs are refused, never flushed.
                    self.check("isolatedRedisInitiallyEmpty", client.dbsize() == 0)
                    return
            except redis.ConnectionError:
                time.sleep(.1)
        raise RuntimeError("Isolated Redis never became ready")

    def application_environment(self, scenario, tenant="demo"):
        if tenant not in {"demo", "studio"}:
            raise ValueError("Unexpected verification tenant")
        environment = clean_environment()
        environment.update(FUSION_JAVA_URL=f"http://127.0.0.1:{self.args.java_port}",
                           FUSION_REDIS_URL=self.args.redis_url, FUSION_COMMERCE_USER="commerce_" + tenant,
                           FUSION_COMMERCE_PASSWORD=self.service_password, FUSION_CUSTOMER_ASSERTION_SECRET=self.assertion,
                           FUSION_STORE_DB=str(self.private / (scenario + "-store.sqlite3")),
                           FUSION_AI_METRICS_DB=str(self.private / (scenario + "-metrics.sqlite3")),
                           FUSION_AGENT_DB=str(self.private / (scenario + "-runs.sqlite3")), FUSION_SEED_DEMO_ADDRESS="0",
                           FUSION_LLM_KEY="", FUSION_EMBEDDING_KEY="", AI_BAILIAN_API_KEY="", DEEPSEEK_API_KEY="",
                           OPENAI_API_KEY="",
                           LANGCHAIN_TRACING_V2="false", LANGSMITH_TRACING="false",
                           FUSION_STORE_KEY_PREFIX="verification:" + self.run_id + ":" + scenario,
                           FUSION_AI_KEY_PREFIX="verification:ai:" + self.run_id + ":" + scenario,
                           FUSION_SESSION_COOKIE="simlect_studio_session" if tenant == "studio" else "verification_" + self.run_id,
                           FUSION_TENANT=tenant, FUSION_SHOP_ID="default")
        return environment

    def launch_java(self, scenario, demo, studio, payment_timeout=900, jar=None, expected_version=None):
        self.available_port(self.args.java_port)
        url = urlparse(self.args.redis_url)
        settings = {"server.port": str(self.args.java_port), "server.address": "127.0.0.1",
                    "spring.datasource.druid.master.url": self.jdbc(demo),
                    "spring.datasource.druid.master.username": self.db_user, "spring.datasource.druid.master.password": self.db_password,
                    "spring.datasource.druid.initialSize": "0", "spring.datasource.druid.minIdle": "1", "spring.datasource.druid.maxActive": "16",
                    "spring.datasource.druid.statViewServlet.enabled": "false", "spring.datasource.druid.filter.stat.log-slow-sql": "false",
                    "spring.redis.host": url.hostname, "spring.redis.port": str(url.port or 6379), "spring.redis.database": url.path.lstrip("/") or "0",
                    "commerce.tenants.ids": "demo,studio", "commerce.tenants.demo.database": demo, "commerce.tenants.studio.database": studio,
                    "commerce.migrations.enabled": "true", "commerce.migrations.retry-failed": "false",
                    "commerce.payment-timeout-seconds": str(payment_timeout),
                    "commerce.customer-assertion-secrets.demo": self.assertion, "commerce.customer-assertion-secrets.studio": self.assertion,
                    "commerce.payment-webhook-secret": self.webhook, "token.secret": self.token_secret,
                    "ruoyi.profile": (self.private / "uploads").as_posix(), "logging.level.com.ruoyi": "INFO",
                    "logging.file.path": (self.private / (scenario + "-application-logs")).as_posix(),
                    "spring.quartz.auto-startup": "false"}
        historical_logging = scenario == "archived-v11" and jar is not None and jar == getattr(self, "old_jar", None)
        if historical_logging:
            # Only the archived V11 jar needs an external portability override.
            logging_config = self.build / "ruoyi-admin/src/main/resources/logback-spring.xml"
            logging_resource = logging_config.as_uri()
            settings["logging.config"] = logging_resource
            logging_bytes = logging_config.read_bytes()
        else:
            # Prove the actual current artifact contains the config Boot loads.
            logging_resource = "BOOT-INF/classes/logback-spring.xml"
            with zipfile.ZipFile(jar or self.jar) as archive:
                logging_bytes = archive.read(logging_resource)
        # Use environment names proven by the existing local launcher for these
        # secrets; unknown property spellings cannot silently authorize a smoke.
        environment = clean_environment()
        environment.update(COMMERCE_PAYMENT_WEBHOOK_SECRET=self.webhook,
                           COMMERCE_CUSTOMER_ASSERTION_SECRETS_DEMO=self.assertion,
                           COMMERCE_CUSTOMER_ASSERTION_SECRETS_STUDIO=self.assertion)
        config = self.private / (scenario + ".properties")
        config.write_text("\n".join(f"{k}={v}" for k, v in settings.items()), encoding="utf-8")
        self.report.setdefault("javaStartupLogging", {})[scenario] = {
            "configurationSource": "CURRENT_SOURCE_EXTERNAL_TEST_COMPATIBILITY_CONFIG" if historical_logging else "PACKAGED_CURRENT_LOGGING_CONFIG",
            "configurationResource": logging_resource,
            "configurationSha256": hashlib.sha256(logging_bytes).hexdigest(),
            "logDirectory": settings["logging.file.path"],
            "originalJarLoggingConfigurationUsed": not historical_logging,
            "provesUnconfiguredHistoricalStartup": False}
        (self.private / "uploads").mkdir(exist_ok=True)
        process = self.start(["java", "-Dfile.encoding=UTF-8", "-jar", jar or self.jar,
                              "--spring.profiles.active=druid,local", "--spring.config.additional-location=file:" + config.as_posix()],
                             scenario + "-java", cwd=self.private, env=environment)
        deadline = time.monotonic() + 150
        with httpx.Client(trust_env=False, timeout=2) as client:
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Isolated Java exited before migrations/readiness")
                try:
                    response = client.get(f"http://127.0.0.1:{self.args.java_port}/captchaImage")
                    if response.status_code == 200 and response.json().get("code") == 200:
                        histories = [self.sql("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + s + "' AND TABLE_NAME='commerce_schema_history'") for s in (demo, studio)]
                        if histories == ["1", "1"] and all(self.sql("SELECT COUNT(*) FROM commerce_schema_history WHERE state='APPLIED'", s) == str(expected_version or self.latest) for s in (demo, studio)):
                            return process
                except (httpx.HTTPError, ValueError):
                    pass
                time.sleep(.3)
        raise RuntimeError("Isolated Java readiness timeout")

    def start_agent(self, scenario, tenant="demo"):
        self.available_port(self.args.python_port)
        environment = self.application_environment(scenario, tenant)
        process = self.start([sys.executable, "-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", str(self.args.python_port)],
                             scenario + "-agent", cwd=ROOT / "fusion-agent", env=environment)
        deadline = time.monotonic() + 60
        with httpx.Client(trust_env=False, timeout=2) as client:
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Isolated Python exited before readiness")
                try:
                    if client.get(f"http://127.0.0.1:{self.args.python_port}/openapi.json").status_code == 200:
                        health = client.get(f"http://127.0.0.1:{self.args.python_port}/health").json()
                        self.check(scenario + ".realModelProvidersDisabled", health.get("llm_configured") is False
                                   and health.get("embedding_configured") is False and health.get("java_url") == environment["FUSION_JAVA_URL"])
                        break
                except httpx.HTTPError:
                    pass
                time.sleep(.2)
            else:
                raise RuntimeError("Isolated Python readiness timeout")
        return process

    def smoke(self, scenario):
        process = self.start_agent(scenario)
        environment = self.application_environment(scenario)
        result = self.output / (scenario + "-closing-business.json")
        environment.update(FUSION_SMOKE_STORE=f"http://127.0.0.1:{self.args.python_port}",
                           FUSION_CLOSING_AGENT_URL=f"http://127.0.0.1:{self.args.python_port}",
                           FUSION_SMOKE_USER="admin", FUSION_SMOKE_PASSWORD=self.admin_password)
        wrapper = ("import pathlib,smoke_closing_business as smoke;"
                   "smoke.RESULT_PATH=pathlib.Path(" + repr(str(result)) + ");"
                   "scenario=smoke.ClosingBusiness();scenario.run()")
        try:
            self.command([sys.executable, "-c", wrapper], scenario + "-http-smoke", cwd=ROOT / "fusion-agent", env=environment, timeout=240)
            data = json.loads(result.read_text(encoding="utf-8"))
            self.check(scenario + ".realHttpClosingPassed", data.get("passed") is True)
            self.report["phases"][scenario]["orderId"] = data["orderId"]
            contracts = self.output / (scenario + "-closing-contracts.json")
            self.command([sys.executable, ROOT / "scripts/closing_contract_probe.py", "--output", contracts],
                         scenario + "-closing-contracts", cwd=ROOT, env=environment, timeout=120)
            contract_result = json.loads(contracts.read_text(encoding="utf-8"))
            self.check(scenario + ".uploadAndHistoryContractsPassed", contract_result.get("passed") is True)
        finally:
            self.stop(process)

    def setup_business(self, scenario):
        """Configure only the two run-owned shops through their owner accounts."""
        result = self.output / (scenario + "-business-setup.json")
        environment = self.application_environment(scenario)
        environment["FUSION_SMOKE_PASSWORD"] = self.admin_password
        wrapper = '''import json
from pathlib import Path
from contextlib import ExitStack
from smoke_supply_execution import Acceptance
acceptance = Acceptance()
proof = {}
with ExitStack() as stack:
    for username, tenant, actor in (("admin", "demo", 1), ("studio_admin", "studio", 101)):
        merchant = acceptance.merchant(stack, username)
        identity = acceptance.api(merchant, tenant + ".ownerContext", "GET", "/commerce/context")
        acceptance.check(tenant + ".expectedOwnedTenant", identity["tenantId"] == tenant and identity["userId"] == actor)
        saved = acceptance.api(merchant, tenant + ".configureDispatch", "POST", "/commerce/planning/policy", {"dailyItemCapacity": 100, "dispatchDays": 1})
        acceptance.check(tenant + ".policySaved", saved["status"] == "SAVED")
        policy = acceptance.api(merchant, tenant + ".verifyDispatch", "GET", "/commerce/planning/replenishment")["dispatchPolicy"]
        acceptance.check(tenant + ".dispatchPolicyPersisted", policy["dailyItemCapacity"] == 100 and policy["dispatchDays"] == 1 and policy["actorId"] == actor)
        proof[tenant] = policy
Path(RESULT).write_text(json.dumps({"passed": True, "source": "OWNER_HTTP_BUSINESS_CONFIGURATION", "writeEndpoint": "POST /commerce/planning/policy", "readEndpoint": "GET /commerce/planning/replenishment", "checks": acceptance.report["checks"], "dispatchPolicies": proof}, ensure_ascii=False, indent=2), encoding="utf-8")
'''.replace("RESULT", repr(str(result)))
        self.command([sys.executable, "-c", wrapper], scenario + "-business-setup", cwd=ROOT / "fusion-agent", env=environment)
        data = json.loads(result.read_text(encoding="utf-8"))
        self.check(scenario + ".ownedShopDispatchConfiguredByOwnerApi", data.get("passed") is True)
        self.report["phases"][scenario]["businessSetup"] = data

    def business_recovery(self, scenario, demo, studio):
        private_state = self.private / (scenario + "-recovery-session.json")
        report_path = self.output / (scenario + "-http-recovery.json")
        runtime = scenario + "-recovery"
        environment = self.application_environment(runtime)
        environment.update(FUSION_SMOKE_STORE=f"http://127.0.0.1:{self.args.python_port}",
                           FUSION_SMOKE_USER="admin", FUSION_SMOKE_PASSWORD=self.admin_password)
        java = self.launch_java(scenario + "-before-recovery", demo, studio, payment_timeout=60)
        agent = self.start_agent(runtime)
        command = [sys.executable, ROOT / "scripts/http_recovery_probe.py"]
        options = ["--private-state", private_state, "--report", report_path]
        try:
            self.command(command + ["prepare"] + options, scenario + "-recovery-prepare", env=environment, timeout=90)
            state = json.loads(private_state.read_text(encoding="utf-8"))
            operation = state["operation"]["operationId"]
            self.check(scenario + ".unknownPaymentPresentBeforeRestart",
                       self.sql(f"SELECT local_status,provider_status FROM commerce_payment_operation WHERE operation_id='{operation}'", demo) == "UNKNOWN\tSUCCEEDED")
        finally:
            self.stop(agent)
            self.stop(java)
        java = self.launch_java(scenario + "-restart", demo, studio, payment_timeout=60)
        agent = self.start_agent(runtime)
        try:
            self.command(command + ["verify"] + options, scenario + "-recovery-verify", env=environment, timeout=120)
            evidence = json.loads(report_path.read_text(encoding="utf-8"))
            self.check(scenario + ".httpSessionPaymentAndExpiryRecover", evidence.get("passed") is True)
            charged, unpaid = state["orders"]["charged"], state["orders"]["unpaid"]
            payment_facts = self.sql(f"SELECT COUNT(*),COALESCE(MAX(provider_revision),0) FROM commerce_payment_operation WHERE order_id='{charged}'", demo)
            self.check(scenario + ".paymentOperationAndProviderChargeOnce", payment_facts == "1\t1")
            self.check(scenario + ".channelChargeOnce", self.sql(f"SELECT COUNT(*) FROM commerce_channel_entry WHERE order_id='{charged}' AND movement='CHARGE'", demo) == "1")
            self.check(scenario + ".unpaidHasNoProviderOperation", self.sql(f"SELECT COUNT(*) FROM commerce_payment_operation WHERE order_id='{unpaid}'", demo) == "0")
            stock_events = self.sql(f"SELECT order_id,event_type,COUNT(*),SUM(delta_reserved) FROM commerce_stock_ledger WHERE order_id IN ('{charged}','{unpaid}') GROUP BY order_id,event_type ORDER BY order_id,event_type", demo)
            expected = sorted([f"{charged}\tRESERVE\t1\t1", f"{unpaid}\tRESERVE\t1\t1", f"{unpaid}\tRELEASE\t1\t-1"])
            self.check(scenario + ".holdsAndTimeoutReleaseExactlyOnce", stock_events.splitlines() == expected)
            self.report["phases"][scenario]["recoveryEvidence"] = {"orders": state["orders"], "operationId": operation,
                                                                    "providerFacts": payment_facts, "stockLedger": stock_events}
        finally:
            self.stop(agent)
        return java

    def mixed_load(self):
        demo, studio = self.report["phases"]["fresh"]["databases"]
        expected = [schema_name(self.run_id, "fresh", tenant) for tenant in ("demo", "studio")]
        if [demo, studio] != expected or not {demo, studio}.issubset(self.owned_schemas):
            raise ValueError("Mixed load may only use this run's fresh schemas")
        runtime = "fresh-mixed-studio"
        environment = self.application_environment(runtime, "studio")
        environment.update(FUSION_SMOKE_STORE=f"http://127.0.0.1:{self.args.python_port}",
                           FUSION_SMOKE_PASSWORD=self.admin_password, MIXED_LOAD_ADMIN_PASSWORD=self.admin_password)
        java = self.launch_java("fresh-mixed", demo, studio)
        agent = None
        result = self.output / "isolated-mixed-load.json"
        fixture = self.output / "isolated-mixed-fixture.json"
        try:
            setup = '''import json
from pathlib import Path
from contextlib import ExitStack
from smoke_closing_business import ClosingBusiness
scenario = ClosingBusiness()
with ExitStack() as stack:
    owner = scenario.merchant(stack, "studio_admin")
    identity = scenario.api(owner, "mixedFixture.owner", "GET", "/commerce/context")
    scenario.check("mixedFixture.privateStudioOwner", identity["tenantId"] == "studio" and identity["userId"] == 101)
    scenario.api(owner, "mixedFixture.policy", "POST", "/commerce/planning/policy", {"dailyItemCapacity": 1000, "dispatchDays": 1})
    policy = scenario.api(owner, "mixedFixture.verifyPolicy", "GET", "/commerce/planning/replenishment")["dispatchPolicy"]
    scenario.check("mixedFixture.syntheticCapacity", policy["dailyItemCapacity"] == 1000 and policy["dispatchDays"] == 1)
    product, body = scenario.create_product(owner, "MIXED", 99, 2, 1)
    scenario.purchase(owner, "MIXED", product, 20, body, 1, identity["userId"])
    Path(RESULT).write_text(json.dumps({"source": "RUN_OWNED_SYNTHETIC_STUDIO_FIXTURE_VIA_OWNER_HTTP", "productId": product, "productCode": body["productCode"], "baselineQuantity": 20, "dispatchPolicy": policy, "checks": scenario.report["checks"]}, ensure_ascii=False, indent=2), encoding="utf-8")
'''.replace("RESULT", repr(str(fixture)))
            self.command([sys.executable, "-c", setup], "isolated-mixed-fixture", cwd=ROOT / "fusion-agent", env=environment)
            data = json.loads(fixture.read_text(encoding="utf-8"))
            agent = self.start_agent(runtime, "studio")
            client_config = self.private / "mixed-mysql-client.ini"
            client_config.write_text("[client]\nprotocol=TCP\nhost=" + self.db_host + "\nport=" + str(self.db_port)
                                     + "\nuser=" + self.db_user + "\npassword=" + self.db_password + "\n", encoding="utf-8")
            options = ["smoke_mixed_load.py", "--users", "1000", "--browse-rps", "60", "--seconds", "120", "--orders", "200",
                       "--java-url", f"http://127.0.0.1:{self.args.java_port}", "--store-url", f"http://127.0.0.1:{self.args.python_port}",
                       "--studio-database", studio, "--demo-database", demo, "--shop-id", "default", "--product-code", data["productCode"], "--out", str(result)]
            wrapper = ("import asyncio,pathlib,sys,smoke_mixed_load as load;load.MYSQL=pathlib.Path(" + repr(str(Path(self.mysql).resolve()))
                       + ");load.CONFIG=pathlib.Path(" + repr(str(client_config)) + ");sys.argv=" + repr(options)
                       + ";raise SystemExit(asyncio.run(load.MixedRun(load.arguments()).run()))")
            self.command([sys.executable, "-c", wrapper], "isolated-mixed-load", cwd=ROOT / "fusion-agent", env=environment, timeout=600)
            load = json.loads(result.read_text(encoding="utf-8"))
            self.check("isolatedMixedLoadPassed", load.get("passed") is True and load.get("distinctSessionsPrepared") == 1000
                       and load.get("successfulBrowseRequests") == 7200 and load.get("successfulCheckoutOrders") == 200)
            self.report["mixedLoad"] = {key: load.get(key) for key in ("runId", "passed", "users", "targetBrowseRPS", "arrivalWindowSeconds",
                                      "successfulBrowseRequests", "actualBrowseRPS", "successfulCheckoutOrders", "checkoutCompletionP95Ms",
                                      "inventoryRestored", "warehouseBalancesRestored", "otherTenantUnchanged", "residualPurchasedUnits", "dispatchCapacityPreflight")}
            self.report["mixedLoad"].update(source=data["source"], fixtureReport=str(fixture), report=str(result), databases=[demo, studio])
            self.check("mixedLoadRestartDidNotReplayMigrations", {s: self.history(s) for s in (demo, studio)} == self.report["phases"]["fresh"]["history"])
        finally:
            if agent is not None:
                self.stop(agent)
            self.stop(java)

    def run(self):
        try:
            self.available_port(self.args.java_port)
            self.available_port(self.args.python_port)
            version = self.sql("SELECT VERSION()")
            self.check("realMySQL8", version.startswith("8."), {"version": version})
            self.prepare_build()
            self.redis_ready()
            self.sql(f"CREATE USER '{self.db_user}'@'127.0.0.1' IDENTIFIED BY '{self.db_password}';"
                     f"CREATE USER '{self.db_user}'@'localhost' IDENTIFIED BY '{self.db_password}';")
            self.db_account_created = True
            for scenario in ("fresh", "upgrade"):
                demo, studio = [self.create_schema(scenario, tenant) for tenant in ("demo", "studio")]
                self.seed_accounts(demo, studio)
                self.report["phases"][scenario] = {"databases": [demo, studio]}
                legacy = None
                if scenario == "upgrade":
                    historical = self.launch_java("archived-v11", demo, studio, jar=self.old_jar, expected_version=self.old_latest)
                    self.report["phases"][scenario]["oldApplicationHistory"] = {s: self.history(s) for s in (demo, studio)}
                    self.stop(historical)
                    legacy = self.synthetic_v11(demo)
                    self.report["phases"][scenario]["syntheticLegacyBefore"] = legacy
                    failed = self.probe(demo, 12, "fault-after-ddl", inject=True, expected=1)
                    self.check("partialDdlFailureRetained", "INJECTED_AFTER_DDL_FAILURE" in failed
                               and self.sql("SELECT state FROM commerce_schema_history WHERE version=12", demo) == "FAILED")
                    blocked = self.probe(demo, self.latest, "fault-default-retry-blocked", expected=1)
                    self.check("failedMigrationRequiresExplicitRetry", "Incomplete migration requires operator retry: V12" in blocked)
                    self.probe(demo, self.latest, "explicit-operator-retry", retry=True)
                    self.check("legacyRowsPreservedByUpgrade", self.legacy_snapshot(demo) == legacy)
                java = self.launch_java(scenario, demo, studio)
                initial_history = {s: self.history(s) for s in (demo, studio)}
                self.report["phases"][scenario]["history"] = initial_history
                self.check(scenario + ".allVersionsApplied", all(len(rows.splitlines()) == self.latest for rows in initial_history.values()))
                if not self.args.migrations_only:
                    self.setup_business(scenario)
                    self.smoke(scenario)
                self.stop(java)
                java = (self.launch_java(scenario + "-restart", demo, studio) if self.args.migrations_only
                        else self.business_recovery(scenario, demo, studio))
                self.check(scenario + ".repeatStartupDoesNotReplayMigrations", {s: self.history(s) for s in (demo, studio)} == initial_history)
                if legacy is not None:
                    self.check("legacyRowsPreservedByRestart", self.legacy_snapshot(demo) == legacy)
                self.stop(java)
                # Tamper only our history, proving the real migrator detects it;
                # restore the evidence row before ending this isolated scenario.
                checksum = self.sql("SELECT checksum FROM commerce_schema_history WHERE version=11", demo)
                self.sql("UPDATE commerce_schema_history SET checksum=REPEAT('0',64) WHERE version=11", demo)
                try:
                    mismatch = self.probe(demo, self.latest, scenario + "-checksum-tamper", expected=1)
                    self.check(scenario + ".checksumMismatchStopsMigration", "Migration checksum mismatch: V11" in mismatch)
                finally:
                    self.sql(f"UPDATE commerce_schema_history SET checksum='{checksum}' WHERE version=11", demo)
            if self.args.mixed_load:
                self.mixed_load()
            self.report.update(passed=True, status="PASSED", httpSmokeExecuted=not self.args.migrations_only)
        except BaseException as error:
            self.report.update(passed=False, status="FAILED", error=self.redact(str(error)), errorType=type(error).__name__)
            raise
        finally:
            for process, handle, path, name in reversed(self.processes):
                self.stop(process)
                handle.close()
                (self.output / (name + ".log")).write_text(self.redact(path.read_text(encoding="utf-8", errors="replace")), encoding="utf-8")
            self.report["ownedProcessesStopped"] = all(p.poll() is not None for p, _, _, _ in self.processes)
            self.report["isolatedDatabasesRetained"] = sorted(self.owned_schemas)
            final_source = source_evidence()
            self.report["sourceSha256AtEnd"] = final_source["sourceSha256"]
            self.report["sourceChangedDuringRun"] = final_source["sourceSha256"] != self.report["sourceSha256"]
            self.save()
            if self.report["sourceChangedDuringRun"]:
                self.report.update(passed=False, status="FAILED", error="SOURCE_CHANGED_DURING_RUN", errorType="RuntimeError")
                self.save()
                raise RuntimeError("SOURCE_CHANGED_DURING_RUN")


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql", help="mysql client executable; defaults to PATH")
    parser.add_argument("--mysql-defaults", help="read [client] privately; never print or reuse its default database")
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", type=int, default=3306)
    parser.add_argument("--mysql-user", default="root")
    parser.add_argument("--redis-url", default="redis://127.0.0.1:16379/0")
    parser.add_argument("--redis-executable", help="start and stop a new Redis on the requested free port")
    parser.add_argument("--java-port", type=int, default=19035)
    parser.add_argument("--python-port", type=int, default=17050)
    parser.add_argument("--jar", help="copy an already verified Boot jar; otherwise package in a private source copy")
    parser.add_argument("--historical-ref", default=HISTORICAL_REF, help="archived V11 Git source ref; requires full Git history")
    parser.add_argument("--migrations-only", action="store_true", help="do not execute HTTP business smoke")
    parser.add_argument("--mixed-load", action="store_true", help="after closure/recovery, run 1000-session mixed load in this run's fresh Studio shop")
    parser.add_argument("--output", default=str(ROOT / "logs" / ("engineering-" + datetime.now().strftime("%Y%m%d-%H%M%S"))))
    args = parser.parse_args()
    if args.mixed_load and args.migrations_only:
        parser.error("--mixed-load requires the complete HTTP verification; omit --migrations-only")
    return args


if __name__ == "__main__":
    verification = Verification(arguments())
    try:
        verification.run()
    except BaseException:
        print(json.dumps({"passed": False, "report": str(verification.output / "engineering-result.json")}, ensure_ascii=False))
        sys.exit(1)
    print(json.dumps({"passed": verification.report["passed"], "latestMigration": verification.latest,
                      "report": str(verification.output / "engineering-result.json")}, ensure_ascii=False))
