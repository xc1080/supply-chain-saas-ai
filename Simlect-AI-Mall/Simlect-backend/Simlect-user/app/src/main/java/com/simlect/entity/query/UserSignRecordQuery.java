package com.simlect.entity.query;

public class UserSignRecordQuery extends BaseParam {

	private String userId;

	private String userIdFuzzy;

	private Integer continuousDays;

	private Integer totalSignDays;

	private Integer usedCount;

	public void setUserId(String userId){ this.userId = userId; }
	public String getUserId(){ return this.userId; }

	public void setUserIdFuzzy(String userIdFuzzy){ this.userIdFuzzy = userIdFuzzy; }
	public String getUserIdFuzzy(){ return this.userIdFuzzy; }

	public void setContinuousDays(Integer continuousDays){ this.continuousDays = continuousDays; }
	public Integer getContinuousDays(){ return this.continuousDays; }

	public void setTotalSignDays(Integer totalSignDays){ this.totalSignDays = totalSignDays; }
	public Integer getTotalSignDays(){ return this.totalSignDays; }

	public void setUsedCount(Integer usedCount){ this.usedCount = usedCount; }
	public Integer getUsedCount(){ return this.usedCount; }

}
