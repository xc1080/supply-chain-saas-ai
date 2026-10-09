package com.ruoyi.system.service;

import org.junit.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CommerceSchemaMigratorTest {
    private JdbcDataSource source(){JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:migrate_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");return source;}
    private CommerceSchemaMigrator.Migration migration(int version,String checksum,Runnable run){return new CommerceSchemaMigrator.Migration(version,"fixture",checksum,run);}
    private void rejects(Runnable run,String message){try{run.run();fail("Expected rejection");}catch(IllegalStateException error){assertTrue(error.getMessage(),error.getMessage().contains(message));}}
    @Test public void twoInitializersExecuteOnceAndReadOnlyStartupOnlyValidates()throws Exception {
        JdbcDataSource source=source();AtomicInteger count=new AtomicInteger();
        List<CommerceSchemaMigrator.Migration> migrations=Arrays.asList(migration(1,"stable",()->{count.incrementAndGet();try{Thread.sleep(30);}catch(InterruptedException e){throw new IllegalStateException(e);}}));
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try{Future<?> one=workers.submit(()->new CommerceSchemaMigrator(source).migrate(migrations,true,false));Future<?> two=workers.submit(()->new CommerceSchemaMigrator(source).migrate(migrations,true,false));one.get();two.get();}finally{workers.shutdownNow();}
        new CommerceSchemaMigrator(source).migrate(migrations,false,false);assertEquals(1,count.get());
        assertEquals("APPLIED",new JdbcTemplate(source).queryForObject("SELECT state FROM commerce_schema_history",String.class));
    }
    @Test public void failedDdlIsRecordedAndRequiresExplicitRetry(){
        JdbcDataSource source=source();AtomicInteger count=new AtomicInteger();
        List<CommerceSchemaMigrator.Migration> migrations=Arrays.asList(migration(1,"stable",()->{if(count.getAndIncrement()==0)throw new IllegalStateException("fixture fault");}));
        CommerceSchemaMigrator runner=new CommerceSchemaMigrator(source);rejects(()->runner.migrate(migrations,true,false),"fixture fault");
        assertEquals("FAILED",new JdbcTemplate(source).queryForObject("SELECT state FROM commerce_schema_history",String.class));
        rejects(()->runner.migrate(migrations,true,false),"operator retry");assertEquals(1,count.get());
        runner.migrate(migrations,true,true);assertEquals(2,count.get());
    }
    @Test public void changedChecksumAndOldBinariesFailClosed(){
        JdbcDataSource source=source();CommerceSchemaMigrator runner=new CommerceSchemaMigrator(source);
        runner.migrate(Arrays.asList(migration(1,"stable",()->{})),true,false);
        rejects(()->runner.migrate(Arrays.asList(migration(1,"changed",()->fail("Must not execute"))),true,false),"checksum mismatch");
        rejects(()->runner.migrate(Collections.emptyList(),true,false),"newer");
        rejects(()->runner.migrate(Arrays.asList(migration(1,"stable",()->{}),migration(2,"new",()->fail("Must not execute"))),false,false),"required");
    }
}
