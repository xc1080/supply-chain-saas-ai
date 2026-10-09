package com.ruoyi.system.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Ordered migrations, with a database-wide lock and immutable checksums. DDL is not transactional in MySQL. */
public final class CommerceSchemaMigrator {
    private static final Map<String,ReentrantLock> TEST_LOCKS=new ConcurrentHashMap<>();
    public static final class Migration {
        final int version; final String name,checksum; final Runnable action;
        public Migration(int version,String name,String checksum,Runnable action) {
            this.version=version;this.name=name;this.checksum=checksum;this.action=action;
        }
        public static Migration resources(int version,String name,Runnable action,String... resources) {
            try {
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                digest.update((version+":"+name).getBytes(StandardCharsets.UTF_8));
                for(String resource:resources) {
                    digest.update(resource.getBytes(StandardCharsets.UTF_8));
                    try(InputStream input=new ClassPathResource(resource).getInputStream()) {
                        byte[] buffer=new byte[8192];int count;
                        while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);
                    }
                }
                return new Migration(version,name,hex(digest.digest()),action);
            }catch(Exception error){throw new IllegalStateException("Cannot load migration resources",error);}
        }
    }
    private final DataSource source;
    public CommerceSchemaMigrator(DataSource source){this.source=source;}
    public void migrate(List<Migration> migrations,boolean enabled,boolean retryFailed) {
        List<Migration> ordered=new ArrayList<>(migrations);ordered.sort(Comparator.comparingInt(m->m.version));
        Set<Integer> versions=new HashSet<>();for(Migration m:ordered)if(!versions.add(m.version))throw new IllegalArgumentException("Duplicate migration version");
        try(Connection connection=source.getConnection()) {
            boolean mysql=connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
            String identity=connection.getMetaData().getURL()+":"+connection.getCatalog();
            String lockName="commerce-migrate-"+sha(identity).substring(0,32);
            ReentrantLock testLock=TEST_LOCKS.computeIfAbsent(identity,k->new ReentrantLock());
            if(mysql){if(!databaseLock(connection,lockName))throw new IllegalStateException("Migration lock unavailable");}
            else testLock.lock();
            try {
                JdbcTemplate jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                if(enabled)jdbc.execute("CREATE TABLE IF NOT EXISTS commerce_schema_history(version INT PRIMARY KEY,name VARCHAR(80) NOT NULL,checksum CHAR(64) NOT NULL,state VARCHAR(16) NOT NULL,started_at TIMESTAMP NOT NULL,finished_at TIMESTAMP NULL,error_type VARCHAR(120) NULL)");
                Map<Integer,Map<String,Object>> applied=new HashMap<>();
                for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_schema_history"))applied.put(((Number)row.get("version")).intValue(),row);
                Set<Integer> expected=new HashSet<>();for(Migration m:ordered)expected.add(m.version);
                if(!expected.containsAll(applied.keySet()))throw new IllegalStateException("Database schema is newer than this application");
                for(Migration migration:ordered) {
                    Map<String,Object> old=applied.get(migration.version);
                    if(old!=null&&!migration.checksum.equals(old.get("checksum")))throw new IllegalStateException("Migration checksum mismatch: V"+migration.version);
                    if(old!=null&&"APPLIED".equals(old.get("state")))continue;
                    if(!enabled)throw new IllegalStateException("Schema migration required: V"+migration.version);
                    if(old!=null&&!retryFailed)throw new IllegalStateException("Incomplete migration requires operator retry: V"+migration.version);
                    if(old==null)jdbc.update("INSERT INTO commerce_schema_history(version,name,checksum,state,started_at) VALUES (?,?,?,'RUNNING',CURRENT_TIMESTAMP)",migration.version,migration.name,migration.checksum);
                    else jdbc.update("UPDATE commerce_schema_history SET state='RUNNING',started_at=CURRENT_TIMESTAMP,finished_at=NULL,error_type=NULL WHERE version=?",migration.version);
                    try {
                        migration.action.run();
                        jdbc.update("UPDATE commerce_schema_history SET state='APPLIED',finished_at=CURRENT_TIMESTAMP,error_type=NULL WHERE version=?",migration.version);
                    }catch(RuntimeException error) {
                        jdbc.update("UPDATE commerce_schema_history SET state='FAILED',finished_at=CURRENT_TIMESTAMP,error_type=? WHERE version=?",error.getClass().getSimpleName(),migration.version);
                        throw error;
                    }
                }
            }finally {if(mysql)releaseLock(connection,lockName);else testLock.unlock();}
        }catch(SQLException error){throw new IllegalStateException("Migration connection failed",error);}
    }
    private static boolean databaseLock(Connection connection,String name)throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement("SELECT GET_LOCK(?,30)")) {
            statement.setString(1,name);try(ResultSet result=statement.executeQuery()){return result.next()&&result.getInt(1)==1;}
        }
    }
    private static void releaseLock(Connection connection,String name)throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement("SELECT RELEASE_LOCK(?)")){statement.setString(1,name);statement.execute();}
    }
    private static String sha(String value){try{return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hex(byte[] value){StringBuilder result=new StringBuilder();for(byte item:value)result.append(String.format("%02x",item));return result.toString();}
}
