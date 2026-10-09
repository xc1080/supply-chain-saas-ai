package com.simlect.entity.po;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.simlect.api.enums.LogisticsStatusEnum;

import java.io.Serializable;
import java.util.List;

public class OrderLogisticsInfo implements Serializable {

	private String orderId;

	private String userId;

	private String logisticsNo;

	private String logisticsCompany;

	private String senderName;

	private String senderPhone;

	private String senderAddress;

	private String receiverName;

	private String receiverPhone;

	private String receiverAddress;

	private Integer logisticsStatus;

	private String logisticsStatusName;

	public String getLogisticsStatusName() {
		return this.logisticsStatus == null ? "" : LogisticsStatusEnum.getByStatus(this.logisticsStatus).getDesc();
	}

	private List<OrderLogisticsInfoRecord> recordList;

	public List<OrderLogisticsInfoRecord> getRecordList() {
		return recordList;
	}

	public void setRecordList(List<OrderLogisticsInfoRecord> recordList) {
		this.recordList = recordList;
	}

	public void setOrderId(String orderId){
		this.orderId = orderId;
	}

	public String getOrderId(){
		return this.orderId;
	}

	public void setUserId(String userId){
		this.userId = userId;
	}

	public String getUserId(){
		return this.userId;
	}

	public void setLogisticsNo(String logisticsNo){
		this.logisticsNo = logisticsNo;
	}

	public String getLogisticsNo(){
		return this.logisticsNo;
	}

	public void setLogisticsCompany(String logisticsCompany){
		this.logisticsCompany = logisticsCompany;
	}

	public String getLogisticsCompany(){
		return this.logisticsCompany;
	}

	public void setSenderName(String senderName){
		this.senderName = senderName;
	}

	public String getSenderName(){
		return this.senderName;
	}

	public void setSenderPhone(String senderPhone){
		this.senderPhone = senderPhone;
	}

	public String getSenderPhone(){
		return this.senderPhone;
	}

	public void setSenderAddress(String senderAddress){
		this.senderAddress = senderAddress;
	}

	public String getSenderAddress(){
		return this.senderAddress;
	}

	public void setReceiverName(String receiverName){
		this.receiverName = receiverName;
	}

	public String getReceiverName(){
		return this.receiverName;
	}

	public void setReceiverPhone(String receiverPhone){
		this.receiverPhone = receiverPhone;
	}

	public String getReceiverPhone(){
		return this.receiverPhone;
	}

	public void setReceiverAddress(String receiverAddress){
		this.receiverAddress = receiverAddress;
	}

	public String getReceiverAddress(){
		return this.receiverAddress;
	}

	public void setLogisticsStatus(Integer logisticsStatus){
		this.logisticsStatus = logisticsStatus;
	}

	public Integer getLogisticsStatus(){
		return this.logisticsStatus;
	}

	@Override
	public String toString (){
		return "订单编号:"+(orderId == null ? "空" : orderId)+"，用户ID:"+(userId == null ? "空" : userId)+"，物流单号:"+(logisticsNo == null ? "空" : logisticsNo)+"，物流公司:"+(logisticsCompany == null ? "空" : logisticsCompany)+"，发货人姓名:"+(senderName == null ? "空" : senderName)+"，发货人电话:"+(senderPhone == null ? "空" : senderPhone)+"，发货地址:"+(senderAddress == null ? "空" : senderAddress)+"，收件人姓名:"+(receiverName == null ? "空" : receiverName)+"，收件人电话:"+(receiverPhone == null ? "空" : receiverPhone)+"，收件地址:"+(receiverAddress == null ? "空" : receiverAddress)+"，物流状态：0待发货 1运输中 2已送达 3订单取消:"+(logisticsStatus == null ? "空" : logisticsStatus);
	}
}
