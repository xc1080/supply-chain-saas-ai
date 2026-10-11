"""Guard tests require no SQL or HTTP writes."""
import os
from pathlib import Path
import sys
import unittest
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
from unittest.mock import patch, AsyncMock, Mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_engineering import baseline_sql, clean_environment, extract_migrations, schema_name, Verification, ROOT, JAVA_ROOT, MAINTENANCE, HISTORICAL_FIXTURE_KIND


class VerificationGuards(unittest.TestCase):
    def test_generated_names_cannot_target_existing_business_databases(self):
        self.assertEqual(schema_name("a" * 12, "fresh", "demo"), "verify_engineering_aaaaaaaaaaaa_fresh_demo")
        for values in (("ksdatabase", "fresh", "demo"), ("a" * 12, "live", "demo"), ("a" * 12, "fresh", "other"), ("a" * 12 + ";DROP", "fresh", "demo")):
            with self.assertRaises(ValueError):
                schema_name(*values)

    def test_erp_installer_never_creates_or_selects_original_database(self):
        sql = baseline_sql((JAVA_ROOT / "sql/ks_20240820.sql").read_text(encoding="utf-8"))
        self.assertNotIn("create DATABASE `KsDataBase`", sql)
        self.assertIn("drop table if exists sys_user", sql)
        for directive in ("USE ksdatabase;", "DROP DATABASE other;", "SELECT * FROM KsDataBase.product;"):
            with self.assertRaises(ValueError):
                baseline_sql("create DATABASE `KsDataBase`;\n" + directive)

    def test_nested_java_callbacks_and_quoted_parentheses_are_preserved(self):
        source = '''new CommerceSchemaMigrator(source).migrate(Arrays.asList(
            CommerceSchemaMigrator.Migration.resources(1,"one",()->{call("a)");},"db/one.sql"),
            CommerceSchemaMigrator.Migration.resources(2,"two",next::init,"db/two.sql")
        ),enabled,retry);'''
        body, rows = extract_migrations(source)
        self.assertEqual(rows, [(1, "one"), (2, "two")])
        self.assertIn('call("a)")', body)

    def test_live_migration_list_is_discovered_without_fixed_latest_version(self):
        body, rows = extract_migrations((JAVA_ROOT / MAINTENANCE).read_text(encoding="utf-8"))
        self.assertGreaterEqual(rows[-1][0], 16)
        self.assertIn("settlements::initializeSchema", body)

    def test_duplicate_or_missing_migration_versions_are_rejected(self):
        for values in ((1, 1), (1, 3)):
            source = 'new CommerceSchemaMigrator(source).migrate(Arrays.asList(' + ','.join(
                f'CommerceSchemaMigrator.Migration.resources({version},"name",next::init,"db/x.sql")' for version in values) + '),true,false);'
            with self.assertRaises(ValueError):
                extract_migrations(source)

    def test_private_provider_environment_and_sqlite_databases_are_isolated(self):
        verification = Verification.__new__(Verification)
        verification.args = SimpleNamespace(java_port=19035, redis_url="redis://127.0.0.1:16379/0")
        verification.private = ROOT / "logs" / "guard-only-private-path"
        verification.run_id = "a" * 12
        verification.service_password = "fresh-service-password"
        verification.assertion = "fresh-assertion"
        inherited = {"FUSION_AI_METRICS_DB": "runtime/ai-metrics.sqlite3", "FUSION_LLM_KEY": "live-key",
                     "FUSION_EMBEDDING_KEY": "live-key", "AI_BAILIAN_API_KEY": "live-key",
                     "DEEPSEEK_API_KEY": "live-key", "OPENAI_API_KEY": "live-key"}
        with patch.dict(os.environ, inherited):
            self.assertFalse(any(key in clean_environment() for key in inherited))
            environment = verification.application_environment("fresh")
        for key in ("FUSION_STORE_DB", "FUSION_AGENT_DB", "FUSION_AI_METRICS_DB"):
            self.assertEqual(Path(environment[key]).parent, verification.private)
        for key in inherited:
            if key != "FUSION_AI_METRICS_DB":
                self.assertEqual(environment[key], "")

    def test_jvm_spring_and_external_tracing_cannot_override_isolation(self):
        inherited = {"SPRING_APPLICATION_JSON": '{"spring":{"datasource":{"url":"jdbc:mysql://live"}}}',
                     "SPRING_DATASOURCE_DRUID_MASTER_URL": "jdbc:mysql://live",
                     "JAVA_TOOL_OPTIONS": "-Dspring.datasource.druid.master.url=jdbc:mysql://live",
                     "JDK_JAVA_OPTIONS": "-Dserver.port=8035", "_JAVA_OPTIONS": "-Dserver.port=8035",
                     "LANGSMITH_API_KEY": "live-tracing-key", "LANGCHAIN_TRACING_V2": "true",
                     "UVICORN_ENV_FILE": "runtime/private.env", "UVICORN_WORKERS": "8", "UVICORN_RELOAD": "true",
                     "OPENAI_BASE_URL": "https://live.example", "HTTPS_PROXY": "http://live.example"}
        inherited.update(MYSQL_HOME='unowned-config', MYSQL_PWD='inherited-password', MYSQL_TEST_LOGIN_FILE='unowned-login')
        with patch.dict(os.environ, inherited):
            self.assertFalse(any(key in clean_environment() for key in inherited))

    def test_history_evidence_distinguishes_source_rebuild_and_synthetic_data(self):
        for disclosure in ("SOURCE_REBUILD", "CHECKOUT_BYTES", "SYNTHETIC_DATA"):
            self.assertIn(disclosure, HISTORICAL_FIXTURE_KIND)
        self.assertNotEqual(HISTORICAL_FIXTURE_KIND, "ARCHIVED_V11_BINARY_WITH_SYNTHETIC_BUSINESS_DATA")

    def test_mixed_load_facade_uses_private_studio_identity_and_databases(self):
        verification = Verification.__new__(Verification)
        verification.args = SimpleNamespace(java_port=19035, redis_url="redis://127.0.0.1:16379/0")
        verification.private = ROOT / "logs" / "guard-only-private-path"
        verification.run_id = "a" * 12
        verification.service_password = "private-service-password"
        verification.assertion = "private-assertion"
        environment = verification.application_environment("fresh-mixed-studio", "studio")
        self.assertEqual(environment["FUSION_COMMERCE_USER"], "commerce_studio")
        self.assertEqual(environment["FUSION_TENANT"], "studio")
        self.assertEqual(environment["FUSION_SESSION_COOKIE"], "simlect_studio_session")
        self.assertTrue(environment["FUSION_STORE_KEY_PREFIX"].startswith("verification:" + verification.run_id + ":"))
        for key in ("FUSION_STORE_DB", "FUSION_AGENT_DB", "FUSION_AI_METRICS_DB"):
            self.assertEqual(Path(environment[key]).parent, verification.private)
        with self.assertRaises(ValueError):
            verification.application_environment("mixed", "existing-other-tenant")

    def test_mixed_load_rejects_existing_schema_before_any_process_starts(self):
        verification = Verification.__new__(Verification)
        verification.run_id = "a" * 12
        verification.owned_schemas = {"ksdatabase", "ksdatabase_studio"}
        verification.report = {"phases": {"fresh": {"databases": ["ksdatabase", "ksdatabase_studio"]}}}
        verification.launch_java = Mock()
        with self.assertRaises(ValueError):
            verification.mixed_load()
        verification.launch_java.assert_not_called()

    def test_compose_failure_cannot_delete_inherited_virtual_environment(self):
        bash = "C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else shutil.which("bash")
        if not bash or not Path(bash).exists():
            self.skipTest("Bash is required for the cleanup regression")
        with tempfile.TemporaryDirectory(prefix="engineering-cleanup-guard-") as directory:
            folder = Path(directory)
            inherited = folder / "inherited-unowned-directory"
            inherited.mkdir()
            sentinel = inherited / "must-survive.txt"
            sentinel.write_text("existing unrelated state", encoding="utf-8")
            script = folder / "project/scripts/verify-ci.sh"
            script.parent.mkdir(parents=True)
            script.write_text((ROOT / "scripts/verify-ci.sh").read_text(encoding="utf-8"), encoding="utf-8")
            # No tools or infrastructure execute. Exercise the actual EXIT trap
            # after a simulated Compose startup failure, with an inherited path.
            shell = '''java(){ :; }; mvn(){ :; }; npm(){ :; }; mysql(){ :; }; node(){ :; };
python3(){ printf '0123456789ab\\n'; };
docker(){ if [[ "$*" == *" up "* ]]; then return 9; fi; return 0; };
export -f java mvn npm mysql node python3 docker;
source "$1"'''
            environment = os.environ.copy()
            environment["verification_venv"] = inherited.as_posix()
            result = subprocess.run([bash, "--noprofile", "--norc", "-c", shell, "cleanup-guard", script.as_posix()], env=environment, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertTrue(sentinel.exists(), result.stderr)


class MixedLoadPreflightGuards(unittest.IsolatedAsyncioTestCase):
    async def test_capacity_failures_precede_visitor_and_procurement_writes(self):
        sys.path.insert(0, str(ROOT / "fusion-agent"))
        from smoke_mixed_load import MixedRun
        for promise, policy, bucket in ((None, None, ""), (20, {"dailyItemCapacity": 20, "dispatchDays": 1}, ""),
                                         (1000, {"dailyItemCapacity": 1000, "dispatchDays": 1}, "1000\t0\t990")):
            with self.subTest(promise=promise, bucket=bucket):
                run = MixedRun(SimpleNamespace(orders=200, users=1000, browse_rps=60, seconds=120, order_rps=None,
                                               shop_id="default", product_code="OWNED", studio_database="test_studio", demo_database="test_demo"))
                run.login = AsyncMock(return_value="private-token")
                run.http = SimpleNamespace(call=AsyncMock())
                product = {"productId": 1, "productCode": "OWNED", "snapshotReady": True, "listed": True,
                           "bookStock": 35, "onHand": 35, "reservedStock": 0, "activityStock": 0, "availableStock": 35}
                async def java(method, path, body=None, **kwargs):
                    if path == "/commerce/context":
                        return {"tenantId": "demo" if kwargs.get("platform_admin") else "studio"}
                    if path == "/commerce/inventory":
                        return [product]
                    if path == "/commerce/inventory/reconciliation":
                        return {"healthy": True}
                    if path == "/commerce/planning/quote":
                        return {"deliveryCapacity": promise, "dispatchDate": "2026-10-12"}
                    if path == "/commerce/planning/replenishment":
                        return {"dispatchPolicy": policy}
                    self.fail("Unexpected write before preflight: " + path)
                run.java = AsyncMock(side_effect=java)
                run.query = AsyncMock(side_effect=lambda query: bucket if "commerce_delivery_bucket" in query else "2026-10-11")
                with self.assertRaisesRegex(RuntimeError, "capacity"):
                    await run.prepare()
                run.http.call.assert_not_awaited()
                self.assertFalse(run.purchase_posted)
                self.assertFalse(run.activity_created)
                self.assertIn("dispatchCapacityPreflight", run.report)

    async def test_today_capacity_uses_java_business_date_instead_of_database_clock(self):
        sys.path.insert(0, str(ROOT / 'fusion-agent'))
        from smoke_mixed_load import MixedRun
        run = MixedRun(SimpleNamespace(orders=200, users=1000, browse_rps=60, seconds=120, order_rps=None,
                                       shop_id='default', studio_database='owned_studio'))
        run.pid = 1
        run.java = AsyncMock(side_effect=[{'dispatchDate': '2026-10-12', 'deliveryCapacity': 1000},
                                        {'dispatchPolicy': {'dailyItemCapacity': 1000, 'dispatchDays': 1}}])
        async def query(sql):
            self.assertNotIn('CURRENT_DATE', sql)
            self.assertIn("dispatch_date='2026-10-11'", sql)
            return '1000\t0\t990'
        run.query = AsyncMock(side_effect=query)
        with self.assertRaisesRegex(RuntimeError, 'Today dispatch capacity'):
            await run.dispatch_preflight()
        self.assertEqual(run.report['dispatchCapacityPreflight']['today'], '2026-10-11')

    async def test_invalid_server_dispatch_date_never_reaches_sql(self):
        sys.path.insert(0, str(ROOT / 'fusion-agent'))
        from smoke_mixed_load import MixedRun
        run = MixedRun(SimpleNamespace(orders=200, users=1000, browse_rps=60, seconds=120, order_rps=None,
                                       shop_id='default', studio_database='owned_studio'))
        run.pid = 1
        run.java = AsyncMock(side_effect=[{'dispatchDate': "2026-10-12' OR 1=1", 'deliveryCapacity': 1000},
                                        {'dispatchPolicy': {'dailyItemCapacity': 1000, 'dispatchDays': 1}}])
        run.query = AsyncMock()
        with self.assertRaisesRegex(RuntimeError, 'Invalid server dispatch date'):
            await run.dispatch_preflight()
        run.query.assert_not_awaited()


class MySQLClientGuards(unittest.TestCase):
    def test_load_sql_uses_only_explicit_configuration_and_absent_login_path(self):
        sys.path.insert(0, str(ROOT / 'fusion-agent'))
        import smoke_mixed_load as load
        with tempfile.TemporaryDirectory(prefix='mysql-client-guard-') as directory:
            config = Path(directory) / 'private-client.ini'
            config.write_text('[client]\nuser=owned\n', encoding='utf-8')
            with patch.object(load, 'CONFIG', config), patch.dict(os.environ, {
                'MYSQL_PWD': 'inherited', 'MYSQL_HOME': 'unowned', 'MYSQL_TEST_LOGIN_FILE': 'unowned'}), \
                    patch.object(load.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout='owned', stderr='')) as command:
                self.assertEqual(load.mysql('SELECT CURRENT_USER()'), 'owned')
            args, kwargs = command.call_args
            self.assertEqual(args[0][1], '--defaults-file=' + str(config.resolve()))
            env = kwargs['env']
            self.assertEqual({key for key in env if key.upper().startswith('MYSQL_')}, {'MYSQL_TEST_LOGIN_FILE'})
            login = Path(env['MYSQL_TEST_LOGIN_FILE'])
            self.assertEqual(login.parent, config.parent)
            self.assertFalse(login.exists())

    def test_verification_rejects_unexpected_private_login_file_before_connecting(self):
        with tempfile.TemporaryDirectory(prefix='mysql-login-guard-') as directory:
            verification = Verification.__new__(Verification)
            verification.owned_schemas = {'owned'}
            verification.db_login_file = Path(directory) / 'login'
            verification.db_login_file.write_text('unowned-options', encoding='utf-8')
            with patch('verify_engineering.subprocess.run') as command:
                with self.assertRaisesRegex(ValueError, 'Unexpected MySQL login file'):
                    verification.sql('SELECT 1', 'owned')
                command.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
