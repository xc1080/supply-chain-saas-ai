package com.simlect.entity.query;

import java.util.Date;

public class UserProductFavoriteQuery extends BaseParam {

	private String favoriteId;

	private String favoriteIdFuzzy;

	private String userId;

	private String userIdFuzzy;

	private String productId;

	private String productIdFuzzy;

	private Date createTime;

	private String createTimeStart;
	private String createTimeEnd;

	public void setFavoriteId(String favoriteId){ this.favoriteId = favoriteId; }
	public String getFavoriteId(){ return this.favoriteId; }

	public void setFavoriteIdFuzzy(String favoriteIdFuzzy){ this.favoriteIdFuzzy = favoriteIdFuzzy; }
	public String getFavoriteIdFuzzy(){ return this.favoriteIdFuzzy; }

	public void setUserId(String userId){ this.userId = userId; }
	public String getUserId(){ return this.userId; }

	public void setUserIdFuzzy(String userIdFuzzy){ this.userIdFuzzy = userIdFuzzy; }
	public String getUserIdFuzzy(){ return this.userIdFuzzy; }

	public void setProductId(String productId){ this.productId = productId; }
	public String getProductId(){ return this.productId; }

	public void setProductIdFuzzy(String productIdFuzzy){ this.productIdFuzzy = productIdFuzzy; }
	public String getProductIdFuzzy(){ return this.productIdFuzzy; }

	public void setCreateTime(Date createTime){ this.createTime = createTime; }
	public Date getCreateTime(){ return this.createTime; }

	public void setCreateTimeStart(String createTimeStart){ this.createTimeStart = createTimeStart; }
	public String getCreateTimeStart(){ return this.createTimeStart; }
	public void setCreateTimeEnd(String createTimeEnd){ this.createTimeEnd = createTimeEnd; }
	public String getCreateTimeEnd(){ return this.createTimeEnd; }

}
