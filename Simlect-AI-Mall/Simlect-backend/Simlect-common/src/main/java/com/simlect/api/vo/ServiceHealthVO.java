package com.simlect.api.vo;

import java.io.Serializable;

public class ServiceHealthVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String serviceName;
    private String status;

    public ServiceHealthVO() {
    }

    public ServiceHealthVO(String serviceName, String status) {
        this.serviceName = serviceName;
        this.status = status;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
