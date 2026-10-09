package com.simlect.entity.query;

import java.math.BigDecimal;

public class OrderInfoQuery extends BaseParam {

	private String orderId;

	private String orderIdFuzzy;

	private BigDecimal amount;

	private String userId;

	private String userIdFuzzy;

	private String orderTime;

	private String orderTimeStart;

	private String orderTimeEnd;

	private Integer orderStatus;

	private String payChannel;

	private String payChannelFuzzy;

	private String payScene;

	private String paySceneFuzzy;

	private String payOrderId;

	private String payOrderIdFuzzy;

	private String channelOrderId;

	private String channelOrderIdFuzzy;

	private Integer commentStatus;

	// 标题
	private String subject;

	private Boolean queryItems;

	public void setQueryUser(Boolean queryUser) {
		this.queryUser = queryUser;
	}

	public Boolean getQueryUser() {
		return this.queryUser;
	}

	private Boolean queryUser;

	private Integer[] orderStatusList;

	private  Integer[] executeOrderStatusList;

	public Boolean isQueryItems() {
		return queryItems;
	}

	public void setQueryItems(Boolean queryItems) {
		this.queryItems = queryItems;
	}

	public Integer[] getOrderStatusList() {
		return orderStatusList;
	}

	public void setOrderStatusList(Integer[] orderStatusList) {
		this.orderStatusList = orderStatusList;
	}

	public Integer[] getExecuteOrderStatusList() {
		return executeOrderStatusList;
	}

	public void setExecuteOrderStatusList(Integer[] executeOrderStatusList) {
		this.executeOrderStatusList = executeOrderStatusList;
	}

	public String getSubject() {
		return subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

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

	public void setAmount(BigDecimal amount){
		this.amount = amount;
	}

	public BigDecimal getAmount(){
		return this.amount;
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

	public void setOrderTime(String orderTime){
		this.orderTime = orderTime;
	}

	public String getOrderTime(){
		return this.orderTime;
	}

	public void setOrderTimeStart(String orderTimeStart){
		this.orderTimeStart = orderTimeStart;
	}

	public String getOrderTimeStart(){
		return this.orderTimeStart;
	}
	public void setOrderTimeEnd(String orderTimeEnd){
		this.orderTimeEnd = orderTimeEnd;
	}

	public String getOrderTimeEnd(){
		return this.orderTimeEnd;
	}

	public void setOrderStatus(Integer orderStatus){
		this.orderStatus = orderStatus;
	}

	public Integer getOrderStatus(){
		return this.orderStatus;
	}

	public void setPayChannel(String payChannel){
		this.payChannel = payChannel;
	}

	public String getPayChannel(){
		return this.payChannel;
	}

	public void setPayChannelFuzzy(String payChannelFuzzy){
		this.payChannelFuzzy = payChannelFuzzy;
	}

	public String getPayChannelFuzzy(){
		return this.payChannelFuzzy;
	}

	public void setPayScene(String payScene){
		this.payScene = payScene;
	}

	public String getPayScene(){
		return this.payScene;
	}

	public void setPaySceneFuzzy(String paySceneFuzzy){
		this.paySceneFuzzy = paySceneFuzzy;
	}

	public String getPaySceneFuzzy(){
		return this.paySceneFuzzy;
	}

	public void setPayOrderId(String payOrderId){
		this.payOrderId = payOrderId;
	}

	public String getPayOrderId(){
		return this.payOrderId;
	}

	public void setPayOrderIdFuzzy(String payOrderIdFuzzy){
		this.payOrderIdFuzzy = payOrderIdFuzzy;
	}

	public String getPayOrderIdFuzzy(){
		return this.payOrderIdFuzzy;
	}

	public void setChannelOrderId(String channelOrderId){
		this.channelOrderId = channelOrderId;
	}

	public String getChannelOrderId(){
		return this.channelOrderId;
	}

	public void setChannelOrderIdFuzzy(String channelOrderIdFuzzy){
		this.channelOrderIdFuzzy = channelOrderIdFuzzy;
	}

	public String getChannelOrderIdFuzzy(){
		return this.channelOrderIdFuzzy;
	}

	public void setCommentStatus(Integer commentStatus){
		this.commentStatus = commentStatus;
	}

	public Integer getCommentStatus(){
		return this.commentStatus;
	}

	private Integer[] commentStatusList;

	public Integer[] getCommentStatusList() {
		return commentStatusList;
	}

	public void setCommentStatusList(Integer[] commentStatusList) {
		this.commentStatusList = commentStatusList;
	}

}
