package com.simlect.entity.po;

import java.math.BigDecimal;

import java.util.Date;
import com.simlect.entity.enums.DateTimePatternEnum;
import com.simlect.utils.DateUtil;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;

public class OrderCouponRel implements Serializable {

	private Long id;

	private String orderId;

	private String userCouponId;

	private String couponId;

	private BigDecimal discountAmount;

	private Date createTime;

	public void setId(Long id){
		this.id = id;
	}

	public Long getId(){
		return this.id;
	}

	public void setOrderId(String orderId){
		this.orderId = orderId;
	}

	public String getOrderId(){
		return this.orderId;
	}

	public void setUserCouponId(String userCouponId){
		this.userCouponId = userCouponId;
	}

	public String getUserCouponId(){
		return this.userCouponId;
	}

	public void setCouponId(String couponId){
		this.couponId = couponId;
	}

	public String getCouponId(){
		return this.couponId;
	}

	public void setDiscountAmount(BigDecimal discountAmount){
		this.discountAmount = discountAmount;
	}

	public BigDecimal getDiscountAmount(){
		return this.discountAmount;
	}

	public void setCreateTime(Date createTime){
		this.createTime = createTime;
	}

	public Date getCreateTime(){
		return this.createTime;
	}

}
