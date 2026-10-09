package com.simlect.entity.query;

public class CommentReportQuery extends BaseParam {

    private Integer reportId;
    private String orderId;
    private String productId;
    private String reporterUserId;
    private String reason;
    private Integer status;

    private String orderIdFuzzy;
    private String productIdFuzzy;
    private String reporterUserIdFuzzy;
    private String reportTimeStart;
    private String reportTimeEnd;

    public Integer getReportId() {
        return reportId;
    }

    public void setReportId(Integer reportId) {
        this.reportId = reportId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getReporterUserId() {
        return reporterUserId;
    }

    public void setReporterUserId(String reporterUserId) {
        this.reporterUserId = reporterUserId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getOrderIdFuzzy() {
        return orderIdFuzzy;
    }

    public void setOrderIdFuzzy(String orderIdFuzzy) {
        this.orderIdFuzzy = orderIdFuzzy;
    }

    public String getProductIdFuzzy() {
        return productIdFuzzy;
    }

    public void setProductIdFuzzy(String productIdFuzzy) {
        this.productIdFuzzy = productIdFuzzy;
    }

    public String getReporterUserIdFuzzy() {
        return reporterUserIdFuzzy;
    }

    public void setReporterUserIdFuzzy(String reporterUserIdFuzzy) {
        this.reporterUserIdFuzzy = reporterUserIdFuzzy;
    }

    public String getReportTimeStart() {
        return reportTimeStart;
    }

    public void setReportTimeStart(String reportTimeStart) {
        this.reportTimeStart = reportTimeStart;
    }

    public String getReportTimeEnd() {
        return reportTimeEnd;
    }

    public void setReportTimeEnd(String reportTimeEnd) {
        this.reportTimeEnd = reportTimeEnd;
    }
}
