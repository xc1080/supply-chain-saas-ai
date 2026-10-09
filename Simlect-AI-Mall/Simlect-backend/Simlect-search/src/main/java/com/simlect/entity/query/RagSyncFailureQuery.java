package com.simlect.entity.query;

public class RagSyncFailureQuery extends BaseParam {

    private String dataIdFuzzy;
    private String dataType;
    private Integer status;
    private String source;

    public String getDataIdFuzzy() {
        return dataIdFuzzy;
    }

    public void setDataIdFuzzy(String dataIdFuzzy) {
        this.dataIdFuzzy = dataIdFuzzy;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
