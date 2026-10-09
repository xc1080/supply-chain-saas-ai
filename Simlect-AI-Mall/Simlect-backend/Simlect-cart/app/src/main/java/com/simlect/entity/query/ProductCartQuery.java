package com.simlect.entity.query;

public class ProductCartQuery extends BaseParam {

	private String cartId;

	private String cartIdFuzzy;

	private String userId;

	private String userIdFuzzy;

	private String productId;

	private String productIdFuzzy;

	private String propertyValueIds;

	private String propertyValueIdsFuzzy;

	private String propertyValueIdHash;

	private String propertyValueIdHashFuzzy;

	private Integer buyCount;

	private String lastUpdateTime;

	private String lastUpdateTimeStart;

	private String lastUpdateTimeEnd;

	private String createTime;

	private String createTimeStart;

	private String createTimeEnd;

	public void setCartId(String cartId){
		this.cartId = cartId;
	}

	public String getCartId(){
		return this.cartId;
	}

	public void setCartIdFuzzy(String cartIdFuzzy){
		this.cartIdFuzzy = cartIdFuzzy;
	}

	public String getCartIdFuzzy(){
		return this.cartIdFuzzy;
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

	public void setProductId(String productId){
		this.productId = productId;
	}

	public String getProductId(){
		return this.productId;
	}

	public void setProductIdFuzzy(String productIdFuzzy){
		this.productIdFuzzy = productIdFuzzy;
	}

	public String getProductIdFuzzy(){
		return this.productIdFuzzy;
	}

	public void setPropertyValueIds(String propertyValueIds){
		this.propertyValueIds = propertyValueIds;
	}

	public String getPropertyValueIds(){
		return this.propertyValueIds;
	}

	public void setPropertyValueIdsFuzzy(String propertyValueIdsFuzzy){
		this.propertyValueIdsFuzzy = propertyValueIdsFuzzy;
	}

	public String getPropertyValueIdsFuzzy(){
		return this.propertyValueIdsFuzzy;
	}

	public void setPropertyValueIdHash(String propertyValueIdHash){
		this.propertyValueIdHash = propertyValueIdHash;
	}

	public String getPropertyValueIdHash(){
		return this.propertyValueIdHash;
	}

	public void setPropertyValueIdHashFuzzy(String propertyValueIdHashFuzzy){
		this.propertyValueIdHashFuzzy = propertyValueIdHashFuzzy;
	}

	public String getPropertyValueIdHashFuzzy(){
		return this.propertyValueIdHashFuzzy;
	}

	public void setBuyCount(Integer buyCount){
		this.buyCount = buyCount;
	}

	public Integer getBuyCount(){
		return this.buyCount;
	}

	public void setLastUpdateTime(String lastUpdateTime){
		this.lastUpdateTime = lastUpdateTime;
	}

	public String getLastUpdateTime(){
		return this.lastUpdateTime;
	}

	public void setLastUpdateTimeStart(String lastUpdateTimeStart){
		this.lastUpdateTimeStart = lastUpdateTimeStart;
	}

	public String getLastUpdateTimeStart(){
		return this.lastUpdateTimeStart;
	}
	public void setLastUpdateTimeEnd(String lastUpdateTimeEnd){
		this.lastUpdateTimeEnd = lastUpdateTimeEnd;
	}

	public String getLastUpdateTimeEnd(){
		return this.lastUpdateTimeEnd;
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

}
