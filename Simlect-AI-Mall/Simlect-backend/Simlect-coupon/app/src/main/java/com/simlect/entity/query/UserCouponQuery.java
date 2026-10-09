package com.simlect.entity.query;

import java.util.Date;

public class UserCouponQuery extends BaseParam {

	private String userCouponId;

	private String userCouponIdFuzzy;

	private String userId;

	private String userIdFuzzy;

	private String couponId;

	private String couponIdFuzzy;

	private String useTime;

	private String useTimeStart;

	private String useTimeEnd;

	private Integer status;

	private Integer[] executeStatusList;

	public void setUserCouponId(String userCouponId){
		this.userCouponId = userCouponId;
	}

	public String getUserCouponId(){
		return this.userCouponId;
	}

	public void setUserCouponIdFuzzy(String userCouponIdFuzzy){
		this.userCouponIdFuzzy = userCouponIdFuzzy;
	}

	public String getUserCouponIdFuzzy(){
		return this.userCouponIdFuzzy;
	}

	public void setUserId(String userId){
		this.userId = userId;
	}

	public String getUserId(){
		return this.userId;
	}

	public void setUserIdFuzzy(String userIdFuzzy){
		this.userIdFuzzy = userIdFuzzy;
	}

	public String getUserIdFuzzy(){
		return this.userIdFuzzy;
	}

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

	public void setUseTime(String useTime){
		this.useTime = useTime;
	}

	public String getUseTime(){
		return this.useTime;
	}

	public void setUseTimeStart(String useTimeStart){
		this.useTimeStart = useTimeStart;
	}

	public String getUseTimeStart(){
		return this.useTimeStart;
	}
	public void setUseTimeEnd(String useTimeEnd){
		this.useTimeEnd = useTimeEnd;
	}

	public String getUseTimeEnd(){
		return this.useTimeEnd;
	}

	public void setStatus(Integer status){
		this.status = status;
	}

	public Integer getStatus(){
		return this.status;
	}

	public Integer[] getExecuteStatusList() {
		return executeStatusList;
	}

	public void setExecuteStatusList(Integer[] executeStatusList) {
		this.executeStatusList = executeStatusList;
	}

}
