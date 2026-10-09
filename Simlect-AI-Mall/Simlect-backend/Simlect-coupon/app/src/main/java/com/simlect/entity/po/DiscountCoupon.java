package com.simlect.entity.po;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.util.Date;
import com.simlect.entity.enums.DateTimePatternEnum;
import com.simlect.utils.DateUtil;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;

public class DiscountCoupon implements Serializable {

	private String couponId;

	private String couponName;

	private Integer couponType;

	private BigDecimal thresholdAmount;

	private BigDecimal discountAmount;

	private BigDecimal discountRate;

	private Integer totalCount;

	private Integer remainCount;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date validStartTime;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date validEndTime;

	private Integer status;

	private Integer rushingstatus;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date rushingStartTime;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date rushingEndTime;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date createTime;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date updateTime;

	private Boolean hasBought;

	public void setCouponId(String couponId){
		this.couponId = couponId;
	}

	public String getCouponId(){
		return this.couponId;
	}

	public void setCouponName(String couponName){
		this.couponName = couponName;
	}

	public String getCouponName(){
		return this.couponName;
	}

	public void setCouponType(Integer couponType){
		this.couponType = couponType;
	}

	public Integer getCouponType(){
		return this.couponType;
	}

	public void setThresholdAmount(BigDecimal thresholdAmount){
		this.thresholdAmount = thresholdAmount;
	}

	public BigDecimal getThresholdAmount(){
		return this.thresholdAmount;
	}

	public void setDiscountAmount(BigDecimal discountAmount){
		this.discountAmount = discountAmount;
	}

	public BigDecimal getDiscountAmount(){
		return this.discountAmount;
	}

	public void setDiscountRate(BigDecimal discountRate){
		this.discountRate = discountRate;
	}

	public BigDecimal getDiscountRate(){
		return this.discountRate;
	}

	public void setTotalCount(Integer totalCount){
		this.totalCount = totalCount;
	}

	public Integer getTotalCount(){
		return this.totalCount;
	}

	public void setRemainCount(Integer remainCount){
		this.remainCount = remainCount;
	}

	public Integer getRemainCount(){
		return this.remainCount;
	}

	public boolean isUnlimitedStock() {
		return totalCount != null && totalCount == 0;
	}

	public void setValidStartTime(Date validStartTime){
		this.validStartTime = validStartTime;
	}

	public Date getValidStartTime(){
		return this.validStartTime;
	}

	public void setValidEndTime(Date validEndTime){
		this.validEndTime = validEndTime;
	}

	public Date getValidEndTime(){
		return this.validEndTime;
	}

	public void setStatus(Integer status){
		this.status = status;
	}

	public Integer getStatus(){
		return this.status;
	}

	public void setRushingstatus(Integer rushingstatus){
		this.rushingstatus = rushingstatus;
	}

	public Integer getRushingstatus(){
		return this.rushingstatus;
	}

	public void setRushingStartTime(Date rushingStartTime){
		this.rushingStartTime = rushingStartTime;
	}

	public Date getRushingStartTime(){
		return this.rushingStartTime;
	}

	public void setRushingEndTime(Date rushingEndTime){
		this.rushingEndTime = rushingEndTime;
	}

	public Date getRushingEndTime(){
		return this.rushingEndTime;
	}

	public void setCreateTime(Date createTime){
		this.createTime = createTime;
	}

	public Date getCreateTime(){
		return this.createTime;
	}

	public void setUpdateTime(Date updateTime){
		this.updateTime = updateTime;
	}

	public Date getUpdateTime(){
		return this.updateTime;
	}

	public Boolean getHasBought() {
		return hasBought;
	}

	public void setHasBought(Boolean hasBought) {
		this.hasBought = hasBought;
	}

	@Override
	public String toString (){
		return "优惠券ID，如CP20260527001:"+(couponId == null ? "空" : couponId)+"，优惠券名称，如\"618满减券\":"+(couponName == null ? "空" : couponName)+"，优惠券类型 1:满减券 2:折扣券 3:无门槛券:"+(couponType == null ? "空" : couponType)+"，使用门槛金额，0表示无门槛:"+(thresholdAmount == null ? "空" : thresholdAmount)+"，优惠金额（满减/无门槛时填写）:"+(discountAmount == null ? "空" : discountAmount)+"，折扣率（折扣券时填写，如0.85表示85折）:"+(discountRate == null ? "空" : discountRate)+"，发放总量，0表示不限量:"+(totalCount == null ? "空" : totalCount)+"，剩余数量:"+(remainCount == null ? "空" : remainCount)+"，有效期开始时间:"+(validStartTime == null ? "空" : DateUtil.format(validStartTime, DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()))+"，有效期结束时间:"+(validEndTime == null ? "空" : DateUtil.format(validEndTime, DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()))+"，状态 0:已停用 1:进行中 2:已过期 3:已发完:"+(status == null ? "空" : status)+"，是否秒杀优惠卷 0:否 1:是:"+(rushingstatus == null ? "空" : rushingstatus)+"，秒杀开始时间:"+(rushingStartTime == null ? "空" : DateUtil.format(rushingStartTime, DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()))+"，秒杀结束时间:"+(rushingEndTime == null ? "空" : DateUtil.format(rushingEndTime, DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()))+"，创建时间:"+(createTime == null ? "空" : DateUtil.format(createTime, DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()))+"，更新时间:"+(updateTime == null ? "空" : DateUtil.format(updateTime, DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()));
	}
}
