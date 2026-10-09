package com.simlect.entity.query;

import java.util.Date;

public class OrderLogisticsInfoRecordQuery extends BaseParam {

	private Integer recordId;

	private String orderId;

	private String orderIdFuzzy;

	private String recordTime;

	private String recordTimeStart;

	private String recordTimeEnd;

	private String recordAddress;

	private String recordAddressFuzzy;

	public void setRecordId(Integer recordId){
		this.recordId = recordId;
	}

	public Integer getRecordId(){
		return this.recordId;
	}

	public void setOrderId(String orderId){
		this.orderId = orderId;
	}

	public String getOrderId(){
		return this.orderId;
	}

	public void setOrderIdFuzzy(String orderIdFuzzy){
		this.orderIdFuzzy = orderIdFuzzy;
	}

	public String getOrderIdFuzzy(){
		return this.orderIdFuzzy;
	}

	public void setRecordTime(String recordTime){
		this.recordTime = recordTime;
	}

	public String getRecordTime(){
		return this.recordTime;
	}

	public void setRecordTimeStart(String recordTimeStart){
		this.recordTimeStart = recordTimeStart;
	}

	public String getRecordTimeStart(){
		return this.recordTimeStart;
	}
	public void setRecordTimeEnd(String recordTimeEnd){
		this.recordTimeEnd = recordTimeEnd;
	}

	public String getRecordTimeEnd(){
		return this.recordTimeEnd;
	}

	public void setRecordAddress(String recordAddress){
		this.recordAddress = recordAddress;
	}

	public String getRecordAddress(){
		return this.recordAddress;
	}

	public void setRecordAddressFuzzy(String recordAddressFuzzy){
		this.recordAddressFuzzy = recordAddressFuzzy;
	}

	public String getRecordAddressFuzzy(){
		return this.recordAddressFuzzy;
	}

}
