package com.simlect.entity.po;

import java.util.Date;
import com.simlect.entity.enums.DateTimePatternEnum;
import com.simlect.utils.DateUtil;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;

public class UserProductFavorite implements Serializable {

	private String favoriteId;

	private String userId;

	private String productId;

	private Date createTime;

	public void setFavoriteId(String favoriteId){
		this.favoriteId = favoriteId;
	}

	public String getFavoriteId(){
		return this.favoriteId;
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

	public void setCreateTime(Date createTime){
		this.createTime = createTime;
	}

	public Date getCreateTime(){
		return this.createTime;
	}

}
