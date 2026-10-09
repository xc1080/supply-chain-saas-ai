package com.simlect.entity.query;

import java.util.Date;

public class OrderCommentQuery extends BaseParam {

	private String orderId;

	private String orderIdFuzzy;

	private String productId;

	private String productIdFuzzy;

	private String commentContent;

	private String commentContentFuzzy;

	private String commentTime;

	private String commentTimeStart;

	private String commentTimeEnd;

	private String commentImages;

	private String commentImagesFuzzy;

	private Integer star;

	private String commentBizReply;

	private String commentBizReplyFuzzy;

	private String recommentContent;

	private String recommentContentFuzzy;

	private String recommentTime;

	private String recommentTimeStart;

	private String recommentTimeEnd;

	private String recommentImages;

	private String recommentImagesFuzzy;

	private String userId;

	private String userIdFuzzy;

	private String propertyInfo;

	private String propertyInfoFuzzy;

	private Integer status;

	public void setQueryProduct(Boolean queryProduct) {
		this.queryProduct = queryProduct;
	}

	public Boolean getQueryProduct() {
		return this.queryProduct;
	}

	public void setQueryUserInfo(Boolean queryUserInfo) {
		this.queryUserInfo = queryUserInfo;
	}

	public Boolean getQueryUserInfo() {
		return this.queryUserInfo;
	}

	private Boolean queryProduct;

	private Boolean queryUserInfo;

	public void setOrderId(String orderId){
		this.orderId = orderId;
	}

	public String getOrderId(){
		return this.orderId;
	}

	public void setOrderIdFuzzy(String orderIdFuzzy){
		this.orderIdFuzzy = orderIdFuzzy;
	}

	public String getOrderIdFuzzy(){
		return this.orderIdFuzzy;
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

	public void setCommentContent(String commentContent){
		this.commentContent = commentContent;
	}

	public String getCommentContent(){
		return this.commentContent;
	}

	public void setCommentContentFuzzy(String commentContentFuzzy){
		this.commentContentFuzzy = commentContentFuzzy;
	}

	public String getCommentContentFuzzy(){
		return this.commentContentFuzzy;
	}

	public void setCommentTime(String commentTime){
		this.commentTime = commentTime;
	}

	public String getCommentTime(){
		return this.commentTime;
	}

	public void setCommentTimeStart(String commentTimeStart){
		this.commentTimeStart = commentTimeStart;
	}

	public String getCommentTimeStart(){
		return this.commentTimeStart;
	}
	public void setCommentTimeEnd(String commentTimeEnd){
		this.commentTimeEnd = commentTimeEnd;
	}

	public String getCommentTimeEnd(){
		return this.commentTimeEnd;
	}

	public void setCommentImages(String commentImages){
		this.commentImages = commentImages;
	}

	public String getCommentImages(){
		return this.commentImages;
	}

	public void setCommentImagesFuzzy(String commentImagesFuzzy){
		this.commentImagesFuzzy = commentImagesFuzzy;
	}

	public String getCommentImagesFuzzy(){
		return this.commentImagesFuzzy;
	}

	public void setStar(Integer star){
		this.star = star;
	}

	public Integer getStar(){
		return this.star;
	}

	public void setCommentBizReply(String commentBizReply){
		this.commentBizReply = commentBizReply;
	}

	public String getCommentBizReply(){
		return this.commentBizReply;
	}

	public void setCommentBizReplyFuzzy(String commentBizReplyFuzzy){
		this.commentBizReplyFuzzy = commentBizReplyFuzzy;
	}

	public String getCommentBizReplyFuzzy(){
		return this.commentBizReplyFuzzy;
	}

	public void setRecommentContent(String recommentContent){
		this.recommentContent = recommentContent;
	}

	public String getRecommentContent(){
		return this.recommentContent;
	}

	public void setRecommentContentFuzzy(String recommentContentFuzzy){
		this.recommentContentFuzzy = recommentContentFuzzy;
	}

	public String getRecommentContentFuzzy(){
		return this.recommentContentFuzzy;
	}

	public void setRecommentTime(String recommentTime){
		this.recommentTime = recommentTime;
	}

	public String getRecommentTime(){
		return this.recommentTime;
	}

	public void setRecommentTimeStart(String recommentTimeStart){
		this.recommentTimeStart = recommentTimeStart;
	}

	public String getRecommentTimeStart(){
		return this.recommentTimeStart;
	}
	public void setRecommentTimeEnd(String recommentTimeEnd){
		this.recommentTimeEnd = recommentTimeEnd;
	}

	public String getRecommentTimeEnd(){
		return this.recommentTimeEnd;
	}

	public void setRecommentImages(String recommentImages){
		this.recommentImages = recommentImages;
	}

	public String getRecommentImages(){
		return this.recommentImages;
	}

	public void setRecommentImagesFuzzy(String recommentImagesFuzzy){
		this.recommentImagesFuzzy = recommentImagesFuzzy;
	}

	public String getRecommentImagesFuzzy(){
		return this.recommentImagesFuzzy;
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

	public void setPropertyInfo(String propertyInfo){
		this.propertyInfo = propertyInfo;
	}

	public String getPropertyInfo(){
		return this.propertyInfo;
	}

	public void setPropertyInfoFuzzy(String propertyInfoFuzzy){
		this.propertyInfoFuzzy = propertyInfoFuzzy;
	}

	public String getPropertyInfoFuzzy(){
		return this.propertyInfoFuzzy;
	}

	public void setStatus(Integer status){
		this.status = status;
	}

	public Integer getStatus(){
		return this.status;
	}

}
