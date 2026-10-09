package com.simlect.entity.po;

import java.math.BigDecimal;

import java.util.Date;
import com.simlect.entity.enums.DateTimePatternEnum;
import com.simlect.utils.DateUtil;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;

public class PayTradeRecord implements Serializable {

	private String tradeId;

	private String orderId;

	private String userId;

	private String payOrderId;

	private String channelOrderId;

	private String payChannel;

	private BigDecimal payAmount;

	private Integer tradeStatus;

	private Date payTime;

	private Date createTime;

	public void setTradeId(String tradeId){
		this.tradeId = tradeId;
	}

	public String getTradeId(){
		return this.tradeId;
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

	public void setPayOrderId(String payOrderId){
		this.payOrderId = payOrderId;
	}

	public String getPayOrderId(){
		return this.payOrderId;
	}

	public void setChannelOrderId(String channelOrderId){
		this.channelOrderId = channelOrderId;
	}

	public String getChannelOrderId(){
		return this.channelOrderId;
	}

	public void setPayChannel(String payChannel){
		this.payChannel = payChannel;
	}

	public String getPayChannel(){
		return this.payChannel;
	}

	public void setPayAmount(BigDecimal payAmount){
		this.payAmount = payAmount;
	}

	public BigDecimal getPayAmount(){
		return this.payAmount;
	}

	public void setTradeStatus(Integer tradeStatus){
		this.tradeStatus = tradeStatus;
	}

	public Integer getTradeStatus(){
		return this.tradeStatus;
	}

	public void setPayTime(Date payTime){
		this.payTime = payTime;
	}

	public Date getPayTime(){
		return this.payTime;
	}

	public void setCreateTime(Date createTime){
		this.createTime = createTime;
	}

	public Date getCreateTime(){
		return this.createTime;
	}

}
