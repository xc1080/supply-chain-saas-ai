package com.simlect.entity.vo;

import java.math.BigDecimal;

public class TodayDataVO {
    private String type;
    private BigDecimal todayValue;
    private BigDecimal yesterdayValue;
    private BigDecimal increase;

    public TodayDataVO() {
    }

    public TodayDataVO(String type, BigDecimal todayValue, BigDecimal yesterdayValue) {
        this.type = type;
        this.todayValue = todayValue;
        this.yesterdayValue = yesterdayValue;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public BigDecimal getTodayValue() {
        return todayValue;
    }

    public void setTodayValue(BigDecimal todayValue) {
        this.todayValue = todayValue;
    }

    public BigDecimal getYesterdayValue() {
        return yesterdayValue;
    }

    public void setYesterdayValue(BigDecimal yesterdayValue) {
        this.yesterdayValue = yesterdayValue;
    }

    public BigDecimal getIncrease() {
        if (yesterdayValue.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        increase = todayValue.subtract(yesterdayValue).divide(yesterdayValue, 2, BigDecimal.ROUND_HALF_UP).multiply(new BigDecimal(100));
        increase = increase.setScale(2, BigDecimal.ROUND_HALF_UP);
        return increase;
    }

    public void setIncrease(BigDecimal increase) {
        this.increase = increase;
    }
}