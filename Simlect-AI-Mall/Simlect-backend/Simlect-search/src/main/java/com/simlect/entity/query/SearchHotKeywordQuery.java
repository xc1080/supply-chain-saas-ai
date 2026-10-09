package com.simlect.entity.query;

import java.util.Date;

public class SearchHotKeywordQuery extends BaseParam {

    private String keyword;
    private String keywordFuzzy;
    private Integer sort;
    private Integer status;
    private Date updateTime;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getKeywordFuzzy() {
        return keywordFuzzy;
    }

    public void setKeywordFuzzy(String keywordFuzzy) {
        this.keywordFuzzy = keywordFuzzy;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}
