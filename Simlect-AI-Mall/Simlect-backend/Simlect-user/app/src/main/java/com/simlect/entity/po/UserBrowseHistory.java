package com.simlect.entity.po;

import java.util.Date;
import com.simlect.entity.enums.DateTimePatternEnum;
import com.simlect.utils.DateUtil;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;

public class UserBrowseHistory implements Serializable {

	private Long historyId;

	private String userId;

	private String productId;

	private Date browseTime;

	public void setHistoryId(Long historyId){
		this.historyId = historyId;
	}

	public Long getHistoryId(){
		return this.historyId;
	}

	public void setUserId(String userId){
		this.userId = userId;
	}

	public String getUserId(){
		return this.userId;
	}

	public void setProductId(String productId){
		this.productId = productId;
	}

	public String getProductId(){
		return this.productId;
	}

	public void setBrowseTime(Date browseTime){
		this.browseTime = browseTime;
	}

	public Date getBrowseTime(){
		return this.browseTime;
	}

}
