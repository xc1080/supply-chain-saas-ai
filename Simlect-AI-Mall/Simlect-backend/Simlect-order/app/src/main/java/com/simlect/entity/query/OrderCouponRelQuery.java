package com.simlect.entity.query;

import java.math.BigDecimal;
import java.util.Date;

public class OrderCouponRelQuery extends BaseParam {

	private Long id;

	private String orderId;

	private String orderIdFuzzy;

	private String userCouponId;

	private String userCouponIdFuzzy;

	private String couponId;

	private String couponIdFuzzy;

	private BigDecimal discountAmount;

	private Date createTime;

	private String createTimeStart;
	private String createTimeEnd;

	public void setId(Long id){ this.id = id; }
	public Long getId(){ return this.id; }

	public void setOrderId(String orderId){ this.orderId = orderId; }
	public String getOrderId(){ return this.orderId; }

	public void setOrderIdFuzzy(String orderIdFuzzy){ this.orderIdFuzzy = orderIdFuzzy; }
	public String getOrderIdFuzzy(){ return this.orderIdFuzzy; }

	public void setUserCouponId(String userCouponId){ this.userCouponId = userCouponId; }
	public String getUserCouponId(){ return this.userCouponId; }

	public void setUserCouponIdFuzzy(String userCouponIdFuzzy){ this.userCouponIdFuzzy = userCouponIdFuzzy; }
	public String getUserCouponIdFuzzy(){ return this.userCouponIdFuzzy; }

	public void setCouponId(String couponId){ this.couponId = couponId; }
	public String getCouponId(){ return this.couponId; }

	public void setCouponIdFuzzy(String couponIdFuzzy){ this.couponIdFuzzy = couponIdFuzzy; }
	public String getCouponIdFuzzy(){ return this.couponIdFuzzy; }

	public void setDiscountAmount(BigDecimal discountAmount){ this.discountAmount = discountAmount; }
	public BigDecimal getDiscountAmount(){ return this.discountAmount; }

	public void setCreateTime(Date createTime){ this.createTime = createTime; }
	public Date getCreateTime(){ return this.createTime; }

	public void setCreateTimeStart(String createTimeStart){ this.createTimeStart = createTimeStart; }
	public String getCreateTimeStart(){ return this.createTimeStart; }
	public void setCreateTimeEnd(String createTimeEnd){ this.createTimeEnd = createTimeEnd; }
	public String getCreateTimeEnd(){ return this.createTimeEnd; }

}
