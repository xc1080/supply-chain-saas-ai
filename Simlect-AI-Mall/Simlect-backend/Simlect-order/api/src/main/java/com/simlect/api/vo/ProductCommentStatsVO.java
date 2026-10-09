package com.simlect.api.vo;

import java.io.Serializable;

public class ProductCommentStatsVO implements Serializable {
    private Integer totalCount;
    private Integer goodCount;
    private Integer imageCount;

    private Integer goodRatePercent;

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public Integer getGoodCount() {
        return goodCount;
    }

    public void setGoodCount(Integer goodCount) {
        this.goodCount = goodCount;
    }

    public Integer getImageCount() {
        return imageCount;
    }

    public void setImageCount(Integer imageCount) {
        this.imageCount = imageCount;
    }

    public Integer getGoodRatePercent() {
        return goodRatePercent;
    }

    public void setGoodRatePercent(Integer goodRatePercent) {
        this.goodRatePercent = goodRatePercent;
    }
}
