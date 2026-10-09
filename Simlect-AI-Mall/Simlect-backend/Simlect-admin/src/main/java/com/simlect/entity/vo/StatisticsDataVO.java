package com.simlect.entity.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class StatisticsDataVO {
    private Integer dataType;
    private List<String> dateList = new ArrayList<>();
    private List<BigDecimal> dataList = new ArrayList<>();

    public Integer getDataType() {
        return dataType;
    }

    public void setDataType(Integer dataType) {
        this.dataType = dataType;
    }

    public List<String> getDateList() {
        return dateList;
    }

    public void setDateList(List<String> dateList) {
        this.dateList = dateList;
    }

    public List<BigDecimal> getDataList() {
        return dataList;
    }

    public void setDataList(List<BigDecimal> dataList) {
        this.dataList = dataList;
    }
}
