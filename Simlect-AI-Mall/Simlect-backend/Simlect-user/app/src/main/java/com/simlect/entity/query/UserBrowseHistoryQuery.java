package com.simlect.entity.query;

import java.util.Date;

public class UserBrowseHistoryQuery extends BaseParam {

	private Long historyId;

	private String userId;

	private String userIdFuzzy;

	private String productId;

	private String productIdFuzzy;

	private Date browseTime;

	private String browseTimeStart;
	private String browseTimeEnd;

	public void setHistoryId(Long historyId){ this.historyId = historyId; }
	public Long getHistoryId(){ return this.historyId; }

	public void setUserId(String userId){ this.userId = userId; }
	public String getUserId(){ return this.userId; }

	public void setUserIdFuzzy(String userIdFuzzy){ this.userIdFuzzy = userIdFuzzy; }
	public String getUserIdFuzzy(){ return this.userIdFuzzy; }

	public void setProductId(String productId){ this.productId = productId; }
	public String getProductId(){ return this.productId; }

	public void setProductIdFuzzy(String productIdFuzzy){ this.productIdFuzzy = productIdFuzzy; }
	public String getProductIdFuzzy(){ return this.productIdFuzzy; }

	public void setBrowseTime(Date browseTime){ this.browseTime = browseTime; }
	public Date getBrowseTime(){ return this.browseTime; }

	public void setBrowseTimeStart(String browseTimeStart){ this.browseTimeStart = browseTimeStart; }
	public String getBrowseTimeStart(){ return this.browseTimeStart; }
	public void setBrowseTimeEnd(String browseTimeEnd){ this.browseTimeEnd = browseTimeEnd; }
	public String getBrowseTimeEnd(){ return this.browseTimeEnd; }

}
