package com.simlect.entity.po;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Date;
import com.simlect.entity.enums.DateTimePatternEnum;
import com.simlect.utils.DateUtil;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.List;

public class OrderComment implements Serializable {

	private String orderId;

	private String productId;

	private String commentContent;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date commentTime;

	private String commentImages;

	private Integer star;

	private String commentBizReply;

	private String recommentContent;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date recommentTime;

	private String recommentImages;

	private String userId;

	private String propertyInfo;

	private Integer status;

	private String nickName;
	private String avatar;
	private String productName;

	public String getCover() {
		return cover;
	}

	public void setCover(String cover) {
		this.cover = cover;
	}

	public String getProductName() {
		return productName;
	}

	public void setProductName(String productName) {
		this.productName = productName;
	}

	public String getAvatar() {
		return avatar;
	}

	public void setAvatar(String avatar) {
		this.avatar = avatar;
	}

	public String getNickName() {
		return nickName;
	}

	public void setNickName(String nickName) {
		this.nickName = nickName;
	}

	private String cover;

	private List<OrderItem> orderItems;

	public List<OrderItem> getOrderItems() {
		return orderItems;
	}

	public void setOrderItems(List<OrderItem> orderItems) {
		this.orderItems = orderItems;
	}

	public void setOrderId(String orderId){
		this.orderId = orderId;
	}

	public String getOrderId(){
		return this.orderId;
	}

	public void setProductId(String productId){
		this.productId = productId;
	}

	public String getProductId(){
		return this.productId;
	}

	public void setCommentContent(String commentContent){
		this.commentContent = commentContent;
	}

	public String getCommentContent(){
		return this.commentContent;
	}

	public void setCommentTime(Date commentTime){
		this.commentTime = commentTime;
	}

	public Date getCommentTime(){
		return this.commentTime;
	}

	public void setCommentImages(String commentImages){
		this.commentImages = commentImages;
	}

	public String getCommentImages(){
		return this.commentImages;
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

	public void setRecommentContent(String recommentContent){
		this.recommentContent = recommentContent;
	}

	public String getRecommentContent(){
		return this.recommentContent;
	}

	public void setRecommentTime(Date recommentTime){
		this.recommentTime = recommentTime;
	}

	public Date getRecommentTime(){
		return this.recommentTime;
	}

	public void setRecommentImages(String recommentImages){
		this.recommentImages = recommentImages;
	}

	public String getRecommentImages(){
		return this.recommentImages;
	}

	public void setUserId(String userId){
		this.userId = userId;
	}

	public String getUserId(){
		return this.userId;
	}

	public void setPropertyInfo(String propertyInfo){
		this.propertyInfo = propertyInfo;
	}

	public String getPropertyInfo(){
		return this.propertyInfo;
	}

	public void setStatus(Integer status){
		this.status = status;
	}

	public Integer getStatus(){
		return this.status;
	}

	@Override
	public String toString (){
		return "订单ID:"+(orderId == null ? "空" : orderId)+"，商品ID:"+(productId == null ? "空" : productId)+"，评价内容:"+(commentContent == null ? "空" : commentContent)+"，评价时间:"+(commentTime == null ? "空" : DateUtil.format(commentTime, DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()))+"，评价图片:"+(commentImages == null ? "空" : commentImages)+"，评价星级:"+(star == null ? "空" : star)+"，商家回复:"+(commentBizReply == null ? "空" : commentBizReply)+"，追评:"+(recommentContent == null ? "空" : recommentContent)+"，追评时间:"+(recommentTime == null ? "空" : DateUtil.format(recommentTime, DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()))+"，追评图片:"+(recommentImages == null ? "空" : recommentImages)+"，用户ID:"+(userId == null ? "空" : userId)+"，属性信息:"+(propertyInfo == null ? "空" : propertyInfo)+"，0:正常 1:已删除:"+(status == null ? "空" : status);
	}
}
