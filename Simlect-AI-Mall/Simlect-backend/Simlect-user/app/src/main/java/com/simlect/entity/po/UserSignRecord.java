package com.simlect.entity.po;

import java.io.Serializable;

public class UserSignRecord implements Serializable {

	private String userId;

	private Integer continuousDays = 0;

	private Integer totalSignDays = 0;

	private Integer usedCount = 0;

	public void setUserId(String userId){
		this.userId = userId;
	}

	public String getUserId(){
		return this.userId;
	}

	public void setContinuousDays(Integer continuousDays){
		this.continuousDays = continuousDays;
	}

	public Integer getContinuousDays(){
		return this.continuousDays == null ? 0 : this.continuousDays;
	}

	public void setTotalSignDays(Integer totalSignDays){
		this.totalSignDays = totalSignDays;
	}

	public Integer getTotalSignDays(){
		return this.totalSignDays == null ? 0 : this.totalSignDays;
	}

	public void setUsedCount(Integer usedCount){
		this.usedCount = usedCount;
	}

	public Integer getUsedCount(){
		return this.usedCount == null ? 0 : this.usedCount;
	}

}
