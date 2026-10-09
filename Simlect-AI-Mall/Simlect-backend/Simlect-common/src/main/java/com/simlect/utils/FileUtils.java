package com.simlect.utils;

import com.simlect.constants.Constants;
import com.simlect.entity.config.AppConfig;
import com.simlect.entity.enums.DateTimePatternEnum;
import com.simlect.entity.enums.ResponseCodeEnum;
import com.simlect.exception.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;

@Component
@Slf4j
public class FileUtils {

    @Resource
    private AppConfig appConfig;

    public ImageCompressUtils.PreparedImage prepareUploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResponseCodeEnum.CODE_600.getCode(), "请选择要上传的图片");
        }
        try {
            return ImageCompressUtils.prepare(file.getBytes(), file.getOriginalFilename());
        } catch (IOException e) {
            log.warn("读取上传文件失败: {}", e.getMessage());
            throw new BusinessException(ResponseCodeEnum.CODE_600.getCode(), "读取图片失败，请重试");
        }
    }

    public String uploadImage(MultipartFile file, Boolean createThumbnail) {
        ImageCompressUtils.PreparedImage prepared = prepareUploadImage(file);
        return savePreparedImage(prepared, createThumbnail);
    }

    public String savePreparedImage(ImageCompressUtils.PreparedImage prepared, Boolean createThumbnail) {
        return writeImage(prepared, "", createThumbnail);
    }

    public String saveModerationQuarantineImage(ImageCompressUtils.PreparedImage prepared) {
        return writeImage(prepared, "moderation/pending/", false);
    }

    private String writeImage(ImageCompressUtils.PreparedImage prepared, String subFolder, Boolean createThumbnail) {
        String folderName = subFolder
                + DateUtil.format(new Date(), DateTimePatternEnum.YYYYMM.getPattern()) + "/";
        String folderPath = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + folderName;
        File folder = new File(folderPath);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new BusinessException(ResponseCodeEnum.CODE_500.getCode(), "创建上传目录失败");
        }
        String fileName = StringTools.getRandomString(Constants.LENGTH_30);
        String suffix = prepared.getSuffix();
        String resultFileName = fileName + suffix;
        String fullPath = folderPath + resultFileName;

        try (OutputStream os = new FileOutputStream(fullPath)) {
            os.write(prepared.getData());
            os.flush();
            log.info("图片落盘: {}, size={} bytes", fullPath, prepared.getData().length);
        } catch (IOException e) {
            log.error("保存图片失败: {}", fullPath, e);
            throw new BusinessException(ResponseCodeEnum.CODE_500.getCode(), "保存图片失败，请稍后重试");
        }

        if (Boolean.TRUE.equals(createThumbnail)) {
            String thumbnail = fileName + Constants.IMAGE_THUMBNAIL_SUFFIX + suffix;
            createImageThumbnail(fullPath, folderPath + thumbnail);
        }
        return folderName + resultFileName;
    }

    public String allocateNormalImageRelativePath(String suffix) {
        if (StringTools.isEmpty(suffix)) {
            suffix = ".jpg";
        }
        String folderName = DateUtil.format(new Date(), DateTimePatternEnum.YYYYMM.getPattern()) + "/";
        return folderName + StringTools.getRandomString(Constants.LENGTH_30) + suffix;
    }

    public void materializeQuarantineToNormal(String quarantinePath, String normalRelativePath, boolean createThumbnail) {
        if (StringTools.isEmpty(quarantinePath) || !isModerationQuarantinePath(quarantinePath)) {
            return;
        }
        if (StringTools.isEmpty(normalRelativePath)) {
            throw new BusinessException("正式图片路径无效");
        }
        File src = resolveStoredFile(quarantinePath);
        if (!src.exists()) {
            throw new BusinessException("隔离区图片不存在");
        }
        File dest = resolveStoredFile(normalRelativePath);
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new BusinessException("创建上传目录失败");
        }
        try {
            java.nio.file.Files.copy(src.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (createThumbnail) {
                String suffix = StringTools.getFileSuffix(normalRelativePath);
                String thumbName = dest.getName().replace(suffix, Constants.IMAGE_THUMBNAIL_SUFFIX + suffix);
                createImageThumbnail(dest.getAbsolutePath(), new File(parent, thumbName).getAbsolutePath());
            }
            if (!src.delete()) {
                log.warn("删除隔离区图片失败: {}", quarantinePath);
            }
        } catch (IOException e) {
            throw new BusinessException("迁移审核图片失败");
        }
    }

    public String promoteQuarantineToNormal(String quarantinePath, boolean createThumbnail) {
        if (StringTools.isEmpty(quarantinePath) || !isModerationQuarantinePath(quarantinePath)) {
            return quarantinePath;
        }
        File src = new File(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + quarantinePath);
        if (!src.exists()) {
            throw new BusinessException("隔离区图片不存在");
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(src.toPath());
            String suffix = StringTools.getFileSuffix(quarantinePath);
            if (StringTools.isEmpty(suffix)) {
                suffix = ".jpg";
            }
            ImageCompressUtils.PreparedImage prepared = new ImageCompressUtils.PreparedImage(bytes, suffix);
            String normalPath = savePreparedImage(prepared, createThumbnail);
            if (!src.delete()) {
                log.warn("删除隔离区图片失败: {}", quarantinePath);
            }
            return normalPath;
        } catch (IOException e) {
            throw new BusinessException("迁移审核图片失败");
        }
    }

    public static boolean isModerationQuarantinePath(String path) {
        return path != null && path.startsWith(Constants.MODERATION_PENDING_PREFIX);
    }

    public void deleteStoredFileQuietly(String relativePath) {
        if (StringTools.isEmpty(relativePath)) {
            return;
        }
        File file = resolveStoredFile(relativePath);
        if (file.exists() && !file.delete()) {
            log.warn("删除文件失败: {}", relativePath);
        }
    }

    public void deleteUserImageWithThumbnailQuietly(String relativePath) {
        if (StringTools.isEmpty(relativePath)) {
            return;
        }
        deleteStoredFileQuietly(relativePath);
        String thumbnailPath = toThumbnailRelativePath(relativePath);
        if (thumbnailPath != null) {
            deleteStoredFileQuietly(thumbnailPath);
        }
    }

    public static String toThumbnailRelativePath(String relativePath) {
        if (StringTools.isEmpty(relativePath)) {
            return null;
        }
        String suffix = StringTools.getFileSuffix(relativePath);
        if (StringTools.isEmpty(suffix)) {
            return null;
        }
        String base = relativePath.substring(0, relativePath.length() - suffix.length());
        if (base.endsWith(Constants.IMAGE_THUMBNAIL_SUFFIX)) {
            return null;
        }
        return base + Constants.IMAGE_THUMBNAIL_SUFFIX + suffix;
    }

    private File resolveStoredFile(String relativePath) {
        return new File(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + relativePath);
    }

    private void createImageThumbnail(String filePath, String thumbnailPath) {
        try {
            final String CMD_CREATE_IMAGE_THUMBNAIL = "ffmpeg -i \"%s\" -vf scale=200:-1 \"%s\"";
            String cmd = String.format(CMD_CREATE_IMAGE_THUMBNAIL, filePath, thumbnailPath);
            ProcessUtils.executeCommand(cmd, true);
        } catch (Exception e) {
            log.warn("生成缩略图失败，ffmpeg可能未安装: {}", e.getMessage());
        }
    }
}
