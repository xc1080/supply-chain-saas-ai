package com.simlect.cloud.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "simlect.gateway.auth")
public class GatewayAuthProperties {

    private boolean enabled = true;

    private List<String> webExcludePaths = new ArrayList<>();

    private List<String> adminExcludePaths = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getWebExcludePaths() {
        return webExcludePaths;
    }

    public void setWebExcludePaths(List<String> webExcludePaths) {
        this.webExcludePaths = webExcludePaths;
    }

    public List<String> getAdminExcludePaths() {
        return adminExcludePaths;
    }

    public void setAdminExcludePaths(List<String> adminExcludePaths) {
        this.adminExcludePaths = adminExcludePaths;
    }
}
