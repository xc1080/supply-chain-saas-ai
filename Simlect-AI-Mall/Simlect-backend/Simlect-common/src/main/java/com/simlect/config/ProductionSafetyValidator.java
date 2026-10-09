package com.simlect.config;

import com.simlect.utils.StringTools;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProductionSafetyValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyValidator.class);
    private static final String WEAK_INTERNAL = "your-token";
    private static final String WEAK_ADMIN_PWD = "admin123456";

    @Value("${simlect.production-ready:false}")
    private boolean productionReady;

    @Value("${simlect.internal.token:}")
    private String internalToken;

    @Value("${admin.password:}")
    private String adminPassword;

    @Value("${simlect.dev-login-bypass:false}")
    private boolean devLoginBypass;

    @Value("${project.folder:}")
    private String projectFolder;

    @PostConstruct
    public void validate() {
        if (!productionReady) {
            log.warn("simlect.production-ready=false：当前为开发/联调模式，上线前请设为 true 并配置强密钥");
            return;
        }
        StringBuilder errors = new StringBuilder();
        if (StringTools.isEmpty(internalToken) || WEAK_INTERNAL.equals(internalToken)) {
            errors.append("SIMLECT_INTERNAL_TOKEN 未设置或仍为开发默认值; ");
        }
        if (StringTools.isEmpty(adminPassword) || WEAK_ADMIN_PWD.equals(adminPassword)) {
            errors.append("ADMIN_PASSWORD 未设置或仍为默认 admin123456; ");
        }
        if (devLoginBypass || System.getProperty("dev") != null) {
            errors.append("禁止生产开启 simlect.dev-login-bypass 或 -Ddev; ");
        }
        if (StringTools.isEmpty(projectFolder) || projectFolder.contains("./data")) {
            log.warn("生产建议将 PROJECT_FOLDER 指向持久化磁盘目录，当前={}", projectFolder);
        }
        if (errors.length() > 0) {
            throw new IllegalStateException("生产就绪校验失败: " + errors);
        }
        log.info("生产就绪校验通过");
    }
}
