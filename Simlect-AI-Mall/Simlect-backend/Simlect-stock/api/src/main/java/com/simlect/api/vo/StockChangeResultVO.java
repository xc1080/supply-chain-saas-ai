package com.simlect.api.vo;

import java.io.Serializable;

public class StockChangeResultVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer affectedRows;

    public StockChangeResultVO() {
    }

    public StockChangeResultVO(Integer affectedRows) {
        this.affectedRows = affectedRows;
    }

    public Integer getAffectedRows() {
        return affectedRows;
    }

    public void setAffectedRows(Integer affectedRows) {
        this.affectedRows = affectedRows;
    }
}
