"""Idempotent local tenant provisioning. Copies schema/public configuration, never orders or customer records."""
from pathlib import Path
import subprocess
import json
import secrets
import bcrypt
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
    sql("CREATE TABLE IF NOT EXISTS ksdatabase.commerce_tenant_registry(tenant_id VARCHAR(32) PRIMARY KEY,status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP); INSERT IGNORE INTO ksdatabase.commerce_tenant_registry(tenant_id) VALUES ('demo'),('studio')")
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

def provision_commerce_identities():
    """Dedicated customer proxy credentials never inherit ERP or merchant permissions."""
    path = ROOT / "runtime/commerce-service-credentials.json"
    credentials = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {
        tenant: {"username": f"commerce_{tenant}", "password": secrets.token_urlsafe(36)}
        for tenant in ("demo", "studio")}
    if not path.exists():
        path.write_text(json.dumps(credentials,ensure_ascii=False,indent=2),encoding="utf-8")
    if "_paymentWebhookSecret" not in credentials:
        credentials["_paymentWebhookSecret"] = secrets.token_urlsafe(48)
        path.write_text(json.dumps(credentials,ensure_ascii=False,indent=2),encoding="utf-8")
    for role_id, role_key, role_name in ((9001,"commerce_customer","商城客户服务"),(9002,"shop_staff","店铺岗位")):
        existing = sql(f"SELECT role_key FROM ksdatabase.sys_role WHERE role_id={role_id}")
        if existing and existing != role_key:
            raise RuntimeError("Reserved commerce role ID conflicts with an existing role")
        for database in ("ksdatabase","ksdatabase_studio"):
            sql(f"INSERT IGNORE INTO {database}.sys_role(role_id,role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time) VALUES({role_id},'{role_name}','{role_key}',99,'1','0','0','commerce-bootstrap',CURRENT_TIMESTAMP)")
    for tenant, user_id, reviewer_id, source_user in (("demo",201,301,1),("studio",202,302,101)):
        database = "ksdatabase" if tenant == "demo" else "ksdatabase_studio"
        account = credentials[tenant]
        expected_user = f"commerce_{tenant}"
        if account["username"] != expected_user or not account["password"].isascii() or len(account["password"]) < 32:
            raise RuntimeError("Invalid commerce service credential configuration")
        existing = sql(f"SELECT user_name FROM ksdatabase.sys_user WHERE user_id={user_id}")
        if existing and existing != expected_user:
            raise RuntimeError("Reserved commerce user ID conflicts with an existing user")
        password_hash = bcrypt.hashpw(account["password"].encode(), bcrypt.gensalt(rounds=12)).decode()
        sql(f"INSERT INTO ksdatabase.sys_user(user_id,dept_id,user_name,nick_name,user_type,password,status,del_flag,create_by,create_time) SELECT {user_id},dept_id,'{expected_user}','商城服务身份','00','{password_hash}','0','0','commerce-bootstrap',CURRENT_TIMESTAMP FROM ksdatabase.sys_user WHERE user_id={source_user} AND NOT EXISTS(SELECT 1 FROM ksdatabase.sys_user WHERE user_id={user_id})")
        sql(f"UPDATE ksdatabase.sys_user SET password='{password_hash}' WHERE user_id={user_id}")
        # Restore exactly the intended role, including on repeat bootstrap; never copy administrator roles.
        sql(f"DELETE FROM ksdatabase.sys_user_role WHERE user_id={user_id}; INSERT INTO ksdatabase.sys_user_role VALUES({user_id},9001); INSERT INTO ksdatabase.commerce_tenant_binding VALUES({user_id},'{tenant}',1) ON DUPLICATE KEY UPDATE tenant_id=VALUES(tenant_id)")
        reviewer = f"{tenant}_supply_reviewer"
        existing_reviewer = sql(f"SELECT user_name FROM ksdatabase.sys_user WHERE user_id={reviewer_id}")
        if existing_reviewer and existing_reviewer != reviewer:
            raise RuntimeError("Reserved reviewer ID conflicts with an existing user")
        # This human demo reviewer inherits the current demo administrator password, never a service secret.
        sql(f"INSERT INTO ksdatabase.sys_user(user_id,dept_id,user_name,nick_name,user_type,password,status,del_flag,create_by,create_time) SELECT {reviewer_id},dept_id,'{reviewer}','备货审批员','00',password,'0','0','commerce-bootstrap',CURRENT_TIMESTAMP FROM ksdatabase.sys_user WHERE user_id={source_user} AND NOT EXISTS(SELECT 1 FROM ksdatabase.sys_user WHERE user_id={reviewer_id}); INSERT IGNORE INTO ksdatabase.sys_user_role VALUES({reviewer_id},9002); INSERT IGNORE INTO ksdatabase.commerce_tenant_binding VALUES({reviewer_id},'{tenant}',1)")
        if database != "ksdatabase":
            for identity in (user_id, reviewer_id):
                sql(f"INSERT IGNORE INTO {database}.sys_user SELECT * FROM ksdatabase.sys_user WHERE user_id={identity}; DELETE FROM {database}.sys_user_role WHERE user_id={identity}; INSERT INTO {database}.sys_user_role SELECT * FROM ksdatabase.sys_user_role WHERE user_id={identity}")
            sql(f"UPDATE {database}.sys_user SET password='{password_hash}' WHERE user_id={user_id}")
        # Membership can be provisioned before the commerce migration without granting any catalog access.
        sql(f"CREATE TABLE IF NOT EXISTS {database}.commerce_shop_member(shop_id VARCHAR(32) NOT NULL,user_id BIGINT NOT NULL,member_role VARCHAR(16) NOT NULL,PRIMARY KEY(shop_id,user_id),INDEX commerce_member_user(user_id,shop_id)); INSERT INTO {database}.commerce_shop_member VALUES('default',{user_id},'CUSTOMER_SERVICE'),('default',{reviewer_id},'SUPPLY_REVIEWER') ON DUPLICATE KEY UPDATE member_role=VALUES(member_role)")
        # Only the commerce workspace menu is granted to the human reviewer.
        sql(f"INSERT IGNORE INTO {database}.sys_role_menu SELECT 9002,menu_id FROM {database}.sys_menu WHERE path IN ('commerce','orders') AND (path='commerce' OR component='commerce/orders/index')")
    print("Dedicated commerce proxy and separate supply reviewers provisioned")

if __name__ == '__main__':
    provision()
    provision_commerce_identities()
