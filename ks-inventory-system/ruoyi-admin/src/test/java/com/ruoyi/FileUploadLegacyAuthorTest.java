package com.ruoyi;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.File;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.system.service.FileService;
import com.ruoyi.web.controller.basedate.FileController;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class FileUploadLegacyAuthorTest {
    @Rule
    public TemporaryFolder uploads = new TemporaryFolder();

    @Test
    public void uploadUsesTheServerUserAndReturnsTheSavedRecordIdentifier() throws Exception {
        FileController controller = new FileController() {
            @Override public String getUsername() { return "server-user"; }
        };
        FileService service = (FileService) Proxy.newProxyInstance(FileService.class.getClassLoader(),
                new Class<?>[]{FileService.class}, (proxy, method, args) -> {
                    assertEquals("upload", method.getName());
                    assertEquals("example.pdf", args[0]);
                    assertEquals("server-user", args[2]);
                    File saved = new File();
                    saved.setFileId(73L);
                    saved.setFileName((String) args[0]);
                    saved.setFileUrl((String) args[1]);
                    return saved;
                });
        Field serviceField = FileController.class.getDeclaredField("fileService");
        serviceField.setAccessible(true);
        serviceField.set(controller, service);
        byte[] bytes = "%PDF fixture".getBytes(StandardCharsets.UTF_8);
        MultipartFile file = new MultipartFile() {
            public String getName() { return "file"; }
            public String getOriginalFilename() { return "example.pdf"; }
            public String getContentType() { return "application/pdf"; }
            public boolean isEmpty() { return false; }
            public long getSize() { return bytes.length; }
            public byte[] getBytes() { return bytes; }
            public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
            public void transferTo(java.io.File target) throws java.io.IOException { Files.write(target.toPath(), bytes); }
        };
        // Initialize the default upload path before changing the isolated test profile.
        FileUploadUtils.getDefaultBaseDir();
        String originalProfile = RuoYiConfig.getProfile();
        try {
            new RuoYiConfig().setProfile(uploads.getRoot().getAbsolutePath());
            AjaxResult result = controller.upload(file);
            assertTrue(result.isSuccess());
            assertEquals(73L, result.get("fileId"));
            assertEquals("example.pdf", result.get("fileName"));
            assertTrue(result.get("fileUrl").toString().startsWith("/profile/avatar/"));
        } finally {
            new RuoYiConfig().setProfile(originalProfile);
        }
    }

    @Test
    public void retiredAuthorEndpointRejectsRequestsWithOrWithoutAnotherUsersFileId() {
        FileController controller = new FileController();
        File request = new File();
        assertTrue(controller.add(request).isError());
        request.setFileId(123L);
        request.setCreateBy("another-user");
        AjaxResult result = controller.add(request);
        assertTrue(result.isError());
    }
}
