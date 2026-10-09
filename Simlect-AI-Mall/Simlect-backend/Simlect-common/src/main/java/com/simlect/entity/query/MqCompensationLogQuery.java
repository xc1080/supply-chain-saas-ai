package com.simlect.entity.query;

public class MqCompensationLogQuery extends BaseParam {

    private Integer logId;
    private String idempotencyKey;
    private String idempotencyKeyFuzzy;
    private String exchange;
    private String routingKey;
    private String bizScene;
    private Integer status;
    private String createTimeStart;
    private String createTimeEnd;

    private Boolean ragRelatedOnly;

    public Boolean getRagRelatedOnly() {
        return ragRelatedOnly;
    }

    public void setRagRelatedOnly(Boolean ragRelatedOnly) {
        this.ragRelatedOnly = ragRelatedOnly;
    }

    public Integer getLogId() {
        return logId;
    }

    public void setLogId(Integer logId) {
        this.logId = logId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKeyFuzzy() {
        return idempotencyKeyFuzzy;
    }

    public void setIdempotencyKeyFuzzy(String idempotencyKeyFuzzy) {
        this.idempotencyKeyFuzzy = idempotencyKeyFuzzy;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getBizScene() {
        return bizScene;
    }

    public void setBizScene(String bizScene) {
        this.bizScene = bizScene;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getCreateTimeStart() {
        return createTimeStart;
    }

    public void setCreateTimeStart(String createTimeStart) {
        this.createTimeStart = createTimeStart;
    }

    public String getCreateTimeEnd() {
        return createTimeEnd;
    }

    public void setCreateTimeEnd(String createTimeEnd) {
        this.createTimeEnd = createTimeEnd;
    }
}
