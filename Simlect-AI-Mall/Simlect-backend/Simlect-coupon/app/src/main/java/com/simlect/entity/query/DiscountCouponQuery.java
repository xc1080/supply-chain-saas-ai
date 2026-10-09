package com.simlect.entity.query;

import java.math.BigDecimal;
import java.util.Date;

public class DiscountCouponQuery extends BaseParam {

	private String couponId;

	private String couponIdFuzzy;

	private String couponName;

	private String couponNameFuzzy;

	private Integer couponType;

	private BigDecimal thresholdAmount;

	private BigDecimal discountAmount;

	private BigDecimal discountRate;

	private Integer totalCount;

	private Integer remainCount;

	private String validStartTime;

	private String validStartTimeStart;

	private String validStartTimeEnd;

	private String validEndTime;

	private String validEndTimeStart;

	private String validEndTimeEnd;

	private Integer status;

	private Integer rushingstatus;

	private String rushingStartTime;

	private String rushingStartTimeStart;

	private String rushingStartTimeEnd;

	private String rushingEndTime;

	private String rushingEndTimeStart;

	private String rushingEndTimeEnd;

	private String createTime;

	private String createTimeStart;

	private String createTimeEnd;

	private String updateTime;

	private String updateTimeStart;

	private String updateTimeEnd;

	public void setCouponId(String couponId){
		this.couponId = couponId;
	}

	public String getCouponId(){
		return this.couponId;
	}

	public void setCouponIdFuzzy(String couponIdFuzzy){
		this.couponIdFuzzy = couponIdFuzzy;
	}

	public String getCouponIdFuzzy(){
		return this.couponIdFuzzy;
	}

	public void setCouponName(String couponName){
		this.couponName = couponName;
	}

	public String getCouponName(){
		return this.couponName;
	}

	public void setCouponNameFuzzy(String couponNameFuzzy){
		this.couponNameFuzzy = couponNameFuzzy;
	}

	public String getCouponNameFuzzy(){
		return this.couponNameFuzzy;
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

	public void setValidStartTime(String validStartTime){
		this.validStartTime = validStartTime;
	}

	public String getValidStartTime(){
		return this.validStartTime;
	}

	public void setValidStartTimeStart(String validStartTimeStart){
		this.validStartTimeStart = validStartTimeStart;
	}

	public String getValidStartTimeStart(){
		return this.validStartTimeStart;
	}
	public void setValidStartTimeEnd(String validStartTimeEnd){
		this.validStartTimeEnd = validStartTimeEnd;
	}

	public String getValidStartTimeEnd(){
		return this.validStartTimeEnd;
	}

	public void setValidEndTime(String validEndTime){
		this.validEndTime = validEndTime;
	}

	public String getValidEndTime(){
		return this.validEndTime;
	}

	public void setValidEndTimeStart(String validEndTimeStart){
		this.validEndTimeStart = validEndTimeStart;
	}

	public String getValidEndTimeStart(){
		return this.validEndTimeStart;
	}
	public void setValidEndTimeEnd(String validEndTimeEnd){
		this.validEndTimeEnd = validEndTimeEnd;
	}

	public String getValidEndTimeEnd(){
		return this.validEndTimeEnd;
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

	public void setRushingStartTime(String rushingStartTime){
		this.rushingStartTime = rushingStartTime;
	}

	public String getRushingStartTime(){
		return this.rushingStartTime;
	}

	public void setRushingStartTimeStart(String rushingStartTimeStart){
		this.rushingStartTimeStart = rushingStartTimeStart;
	}

	public String getRushingStartTimeStart(){
		return this.rushingStartTimeStart;
	}
	public void setRushingStartTimeEnd(String rushingStartTimeEnd){
		this.rushingStartTimeEnd = rushingStartTimeEnd;
	}

	public String getRushingStartTimeEnd(){
		return this.rushingStartTimeEnd;
	}

	public void setRushingEndTime(String rushingEndTime){
		this.rushingEndTime = rushingEndTime;
	}

	public String getRushingEndTime(){
		return this.rushingEndTime;
	}

	public void setRushingEndTimeStart(String rushingEndTimeStart){
		this.rushingEndTimeStart = rushingEndTimeStart;
	}

	public String getRushingEndTimeStart(){
		return this.rushingEndTimeStart;
	}
	public void setRushingEndTimeEnd(String rushingEndTimeEnd){
		this.rushingEndTimeEnd = rushingEndTimeEnd;
	}

	public String getRushingEndTimeEnd(){
		return this.rushingEndTimeEnd;
	}

	public void setCreateTime(String createTime){
		this.createTime = createTime;
	}

	public String getCreateTime(){
		return this.createTime;
	}

	public void setCreateTimeStart(String createTimeStart){
		this.createTimeStart = createTimeStart;
	}

	public String getCreateTimeStart(){
		return this.createTimeStart;
	}
	public void setCreateTimeEnd(String createTimeEnd){
		this.createTimeEnd = createTimeEnd;
	}

	public String getCreateTimeEnd(){
		return this.createTimeEnd;
	}

	public void setUpdateTime(String updateTime){
		this.updateTime = updateTime;
	}

	public String getUpdateTime(){
		return this.updateTime;
	}

	public void setUpdateTimeStart(String updateTimeStart){
		this.updateTimeStart = updateTimeStart;
	}

	public String getUpdateTimeStart(){
		return this.updateTimeStart;
	}
	public void setUpdateTimeEnd(String updateTimeEnd){
		this.updateTimeEnd = updateTimeEnd;
	}

	public String getUpdateTimeEnd(){
		return this.updateTimeEnd;
	}

}
