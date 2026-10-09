package com.simlect.entity.po;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.io.Serializable;

public class StatisticsInfo implements Serializable {

	// 日期
	private String statisticsDate;

	// 数据类型
	private Integer dataType;

	// 统计数据
	private BigDecimal dataValue;

	public void setStatisticsDate(String statisticsDate){
		this.statisticsDate = statisticsDate;
	}

	public String getStatisticsDate(){
		return this.statisticsDate;
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

	@Override
	public String toString (){
		return "日期:"+(statisticsDate == null ? "空" : statisticsDate)+"，数据类型:"+(dataType == null ? "空" : dataType)+"，统计数据:"+(dataValue == null ? "空" : dataValue);
	}
}
