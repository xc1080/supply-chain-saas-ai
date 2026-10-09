package com.simlect.entity.query;

import java.math.BigDecimal;

public class StatisticsInfoQuery extends BaseParam {

	// 日期
	private String statisticsDate;

	private String statisticsDateFuzzy;

	// 数据类型
	private Integer dataType;

	// 统计数据
	private BigDecimal dataValue;

	private String queryStartTime;

	private String queryEndTime;

	public String getQueryStartTime() {
		return queryStartTime;
	}

	public void setQueryStartTime(String queryStartTime) {
		this.queryStartTime = queryStartTime;
	}

	public String getQueryEndTime() {
		return queryEndTime;
	}

	public void setQueryEndTime(String queryEndTime) {
		this.queryEndTime = queryEndTime;
	}

	public void setStatisticsDate(String statisticsDate){
		this.statisticsDate = statisticsDate;
	}

	public String getStatisticsDate(){
		return this.statisticsDate;
	}

	public void setStatisticsDateFuzzy(String statisticsDateFuzzy){
		this.statisticsDateFuzzy = statisticsDateFuzzy;
	}

	public String getStatisticsDateFuzzy(){
		return this.statisticsDateFuzzy;
	}

	public void setDataType(Integer dataType){
		this.dataType = dataType;
	}

	public Integer getDataType(){
		return this.dataType;
	}

	public void setDataValue(BigDecimal dataValue){
		this.dataValue = dataValue;
	}

	public BigDecimal getDataValue(){
		return this.dataValue;
	}

}
