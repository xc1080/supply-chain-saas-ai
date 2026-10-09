package com.simlect.controller;

import com.simlect.annotation.GlobalInterceptor;
import com.simlect.constants.Constants;
import com.simlect.entity.config.AppConfig;
import com.simlect.api.dto.ImageUploadResultDTO;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.ImageModerationService;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

@RestController("fileController")
@RequestMapping("/file")
@Validated
@Slf4j
public class FileController extends ABaseController{

    @Resource
    private AppConfig appConfig;

    @Resource
    private ImageModerationService imageModerationService;

    @GlobalInterceptor(checkLogin = true)
    @PostMapping("/uploadImage")
    public ResponseVO uploadImage(@NotNull MultipartFile file, Boolean createThumbnail, String scene,
                                  String orderId, HttpServletRequest request) {
        String userId = getTokenUserInfo().getUserId();
        String ip = getClientIp(request);
        ImageUploadResultDTO result = imageModerationService.uploadAndModerate(
                userId, ip, file, createThumbnail, scene, orderId);
        // 合规直传：data 直接返回路径字符串，避免前端解析对象出错
        if (!Boolean.TRUE.equals(result.getPendingReview())) {
            return getSuccessResponseVO(result.getPath());
        }
        return getSuccessResponseVO(result);
    }

    @GetMapping("/getResource")
    public void getResource(HttpServletResponse response, @NotNull String sourceName) throws IOException {
        if(!StringTools.pathIsOK(sourceName)){
            return;
        }
        String suffix = StringTools.getFileSuffix(sourceName);
        response.setContentType(resolveImageContentType(suffix));
        response.setHeader("Cache-Control", "max-age=100000");
        readFile(response,sourceName);
    }

    protected void readFile(HttpServletResponse response,String filePath){
        if(!StringTools.pathIsOK(filePath)){
            return;
        }
        File file = new File(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + filePath);
        if(!file.exists()){
            String fallbackPath = filePath.replace("_thumbnail", "");
            if (!fallbackPath.equals(filePath)) {
                file = new File(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + fallbackPath);
            }
        }
        if(!file.exists()){
            return;
        }
        try (OutputStream out = response.getOutputStream();
            FileInputStream in = new FileInputStream(file)) {
                byte[] byteData =  new byte[1024];
                int len = 0;
                while((len = in.read(byteData)) != -1){
                    out.write(byteData,0,len);
                }
                out.flush();
        } catch (Exception e) {
            log.error("读取文件异常");
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (StringTools.isEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (StringTools.isEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    private static String resolveImageContentType(String suffix) {
        if (StringTools.isEmpty(suffix)) {
            return "image/jpeg";
        }
        return switch (suffix.toLowerCase()) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".bmp" -> "image/bmp";
            default -> "image/jpeg";
        };
    }
}
