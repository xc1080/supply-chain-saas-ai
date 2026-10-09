package com.simlect.cloud.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "simlect.gateway.rate-limit")
public class GatewayRateLimitProperties {

    private boolean enabled = true;

    private double defaultQps = 200;

    private double authQps = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getDefaultQps() {
        return defaultQps;
    }

    public void setDefaultQps(double defaultQps) {
        this.defaultQps = defaultQps;
    }

    public double getAuthQps() {
        return authQps;
    }

    public void setAuthQps(double authQps) {
        this.authQps = authQps;
    }
}
