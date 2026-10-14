package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.entity.File;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.FileMapper;
import com.ruoyi.system.service.impl.FileServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Exercises the production mapper with separate, concurrent database sessions. */
public class FileUploadAuthorTest {
    private SqlSessionFactory sessions;
    private JdbcTemplate jdbc;

    @Before
    public void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:file_upload_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE template_file (file_id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "file_name VARCHAR(200), file_url VARCHAR(200), create_by VARCHAR(64) NOT NULL, "
                + "create_time TIMESTAMP, update_by VARCHAR(64), update_time TIMESTAMP)");
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        config.getTypeAliasRegistry().registerAlias("File", File.class);
        String resource = "mapper/system/FileMapper.xml";
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(resource, xml);
            new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
        }
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    private FileServiceImpl service(FileMapper mapper) throws Exception {
        FileServiceImpl service = new FileServiceImpl();
        Field field = FileServiceImpl.class.getDeclaredField("fileMapper");
        field.setAccessible(true);
        field.set(service, mapper);
        return service;
    }

    @Test
    public void concurrentUploadsReturnTheirOwnIdsAndAuthors() throws Exception {
        int count = 12;
        CyclicBarrier start = new CyclicBarrier(count);
        ExecutorService workers = Executors.newFixedThreadPool(count);
        List<Future<File>> uploads = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                uploads.add(workers.submit(() -> {
                    try (SqlSession session = sessions.openSession(true)) {
                        FileServiceImpl service = service(session.getMapper(FileMapper.class));
                        start.await(10, TimeUnit.SECONDS);
                        return service.upload("file-" + index + ".pdf", "/profile/file-" + index, "user-" + index);
                    }
                }));
            }
            Set<Long> ids = new HashSet<>();
            for (int i = 0; i < count; i++) {
                File uploaded = uploads.get(i).get(15, TimeUnit.SECONDS);
                assertNotNull(uploaded.getFileId());
                assertTrue("Each upload needs its own identifier", ids.add(uploaded.getFileId()));
                try (SqlSession session = sessions.openSession()) {
                    File stored = session.getMapper(FileMapper.class).selectFileById(uploaded.getFileId());
                    assertEquals("user-" + i, stored.getCreateBy());
                    assertEquals("file-" + i + ".pdf", stored.getFileName());
                    assertEquals("/profile/file-" + i, stored.getFileUrl());
                }
            }
            assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM template_file", Integer.class).intValue());
            assertFalse("Retired author updates must not remain callable", sessions.getConfiguration()
                    .hasStatement("com.ruoyi.system.mapper.FileMapper.addFileName"));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void missingAuthorCannotCreateAnOrphanRecord() throws Exception {
        try (SqlSession session = sessions.openSession(true)) {
            try {
                service(session.getMapper(FileMapper.class)).upload("orphan.pdf", "/profile/orphan", "");
                fail("Missing author must be rejected");
            } catch (ServiceException expected) {
                assertEquals("上传文件缺少创建者", expected.getMessage());
            }
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM template_file", Integer.class).intValue());
    }
}
