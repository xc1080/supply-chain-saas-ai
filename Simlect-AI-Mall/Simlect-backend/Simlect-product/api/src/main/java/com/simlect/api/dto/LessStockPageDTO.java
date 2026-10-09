package com.simlect.api.dto;

import java.io.Serializable;

public class LessStockPageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer pageNo;
    private Integer pageSize;

    private Integer threshold;

    public LessStockPageDTO() {
    }

    public LessStockPageDTO(Integer pageNo, Integer pageSize, Integer threshold) {
        this.pageNo = pageNo;
        this.pageSize = pageSize;
        this.threshold = threshold;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public Integer getThreshold() {
        return threshold;
    }

    public void setThreshold(Integer threshold) {
        this.threshold = threshold;
    }
}
