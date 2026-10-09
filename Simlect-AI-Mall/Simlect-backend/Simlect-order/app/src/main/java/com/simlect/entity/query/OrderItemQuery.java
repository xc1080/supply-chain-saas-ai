package com.simlect.entity.query;

import java.math.BigDecimal;

public class OrderItemQuery extends BaseParam {

	private String orderItemId;

	private String orderItemIdFuzzy;

	private String orderId;

	private String orderIdFuzzy;

	private String cover;

	private String coverFuzzy;

	private String productId;

	private String productIdFuzzy;

	private String productName;

	private String productNameFuzzy;

	private String propertyValueIdHash;

	private String propertyValueIdHashFuzzy;

	private String propertyInfo;

	private String propertyInfoFuzzy;

	private BigDecimal itemAmount;

	private Integer buyCount;

	private Integer orderItemStatus;

	private String remark;

	private String remarkFuzzy;

	private String refundOrderId;

	private String refundOrderIdFuzzy;

	public void setOrderItemId(String orderItemId){
		this.orderItemId = orderItemId;
	}

	public String getOrderItemId(){
		return this.orderItemId;
	}

	public void setOrderItemIdFuzzy(String orderItemIdFuzzy){
		this.orderItemIdFuzzy = orderItemIdFuzzy;
	}

	public String getOrderItemIdFuzzy(){
		return this.orderItemIdFuzzy;
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

	public void setCover(String cover){
		this.cover = cover;
	}

	public String getCover(){
		return this.cover;
	}

	public void setCoverFuzzy(String coverFuzzy){
		this.coverFuzzy = coverFuzzy;
	}

	public String getCoverFuzzy(){
		return this.coverFuzzy;
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

	public void setProductName(String productName){
		this.productName = productName;
	}

	public String getProductName(){
		return this.productName;
	}

	public void setProductNameFuzzy(String productNameFuzzy){
		this.productNameFuzzy = productNameFuzzy;
	}

	public String getProductNameFuzzy(){
		return this.productNameFuzzy;
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

	public void setItemAmount(BigDecimal itemAmount){
		this.itemAmount = itemAmount;
	}

	public BigDecimal getItemAmount(){
		return this.itemAmount;
	}

	public void setBuyCount(Integer buyCount){
		this.buyCount = buyCount;
	}

	public Integer getBuyCount(){
		return this.buyCount;
	}

	public void setOrderItemStatus(Integer orderItemStatus){
		this.orderItemStatus = orderItemStatus;
	}

	public Integer getOrderItemStatus(){
		return this.orderItemStatus;
	}

	public void setRemark(String remark){
		this.remark = remark;
	}

	public String getRemark(){
		return this.remark;
	}

	public void setRemarkFuzzy(String remarkFuzzy){
		this.remarkFuzzy = remarkFuzzy;
	}

	public String getRemarkFuzzy(){
		return this.remarkFuzzy;
	}

	public void setRefundOrderId(String refundOrderId){
		this.refundOrderId = refundOrderId;
	}

	public String getRefundOrderId(){
		return this.refundOrderId;
	}

	public void setRefundOrderIdFuzzy(String refundOrderIdFuzzy){
		this.refundOrderIdFuzzy = refundOrderIdFuzzy;
	}

	public String getRefundOrderIdFuzzy(){
		return this.refundOrderIdFuzzy;
	}

}
