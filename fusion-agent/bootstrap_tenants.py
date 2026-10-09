"""Idempotent local tenant provisioning. Copies schema/public configuration, never orders or customer records."""
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parent.parent
MYSQL = ROOT / "runtime/mysql-8.0.46-winx64/bin/mysql.exe"
CONFIG = ROOT / "runtime/mysql-root.cnf"

def sql(query):
    result = subprocess.run([str(MYSQL), "--defaults-extra-file=" + str(CONFIG), "--default-character-set=utf8mb4", "-N", "-B"], input=query, text=True, encoding="utf-8", capture_output=True)
    if result.returncode:
        raise RuntimeError("Tenant provisioning SQL failed: " + result.stderr[:600])
    return result.stdout.strip()

def provision():
    account = sql("SELECT CONCAT(user_id,':',user_name) FROM ksdatabase.sys_user WHERE user_id=101 OR user_name='studio_admin'")
    if account and account != "101:studio_admin":
        raise RuntimeError("User 101 or studio_admin already belongs to another account")
    sql("CREATE TABLE IF NOT EXISTS ksdatabase.commerce_tenant_binding(user_id BIGINT PRIMARY KEY,tenant_id VARCHAR(32) NOT NULL,enabled SMALLINT NOT NULL DEFAULT 1); INSERT IGNORE INTO ksdatabase.commerce_tenant_binding VALUES(1,'demo',1),(101,'studio',1)")
    sql("INSERT INTO ksdatabase.sys_user(user_id,dept_id,user_name,nick_name,user_type,password,status,del_flag,create_by,create_time) SELECT 101,dept_id,'studio_admin','Studio 租户管理员',user_type,password,'0','0','tenant-bootstrap',CURRENT_TIMESTAMP FROM ksdatabase.sys_user WHERE user_id=1 AND NOT EXISTS(SELECT 1 FROM ksdatabase.sys_user WHERE user_id=101); INSERT IGNORE INTO ksdatabase.sys_user_role(user_id,role_id) VALUES(101,1)")
    sql("CREATE DATABASE IF NOT EXISTS ksdatabase_studio CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
    sql("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON ksdatabase_studio.* TO 'ks_learning'@'127.0.0.1'")
    tables = sql("SHOW TABLES FROM ksdatabase").splitlines()
    for table in tables:
        if table.startswith("commerce_"):
            continue
        if not table.replace('_','').isalnum(): raise RuntimeError("Unexpected schema identifier")
        sql(f"CREATE TABLE IF NOT EXISTS ksdatabase_studio.`{table}` LIKE ksdatabase.`{table}`")
    sql("CREATE TABLE IF NOT EXISTS ksdatabase_studio.tenant_bootstrap_marker(id SMALLINT PRIMARY KEY)")
    if not sql("SELECT id FROM ksdatabase_studio.tenant_bootstrap_marker WHERE id=1"):
        # Only explicitly public reference/configuration tables are copied.
        public = ['sys_dept','sys_role','sys_menu','sys_role_menu','sys_role_dept','sys_dict_type','sys_dict_data','sys_config','sys_post','product_type']
        statements = [f"INSERT IGNORE INTO ksdatabase_studio.`{table}` SELECT * FROM ksdatabase.`{table}`" for table in public if table in tables]
        statements += ["INSERT IGNORE INTO ksdatabase_studio.sys_user SELECT * FROM ksdatabase.sys_user WHERE user_id=101", "INSERT IGNORE INTO ksdatabase_studio.sys_user_role VALUES(101,1)", "INSERT INTO ksdatabase_studio.tenant_bootstrap_marker VALUES(1)"]
        sql("START TRANSACTION;" + ";".join(statements) + ";COMMIT;")
    sql("INSERT IGNORE INTO ksdatabase.sys_role_menu SELECT 1,menu_id FROM ksdatabase.sys_menu; INSERT IGNORE INTO ksdatabase_studio.sys_role_menu SELECT 1,menu_id FROM ksdatabase_studio.sys_menu")
    print("Tenant demo/studio provisioned; private business data not copied")
if __name__ == '__main__': provision()
