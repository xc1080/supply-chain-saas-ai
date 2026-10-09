package com.simlect.entity.po;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.simlect.entity.enums.DateTimePatternEnum;
import com.simlect.utils.DateUtil;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

public class Product4Load implements Serializable {

	private String productId;

	private String productName;

	private String productDesc;

	private String cover;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date createTime;

	private String categoryId;

	private String pCategoryId;

	private Integer status;

	private BigDecimal minPrice;

	private BigDecimal maxPrice;

	private Integer totalSale;

	private Integer commendType;

	private Integer totalStock;

	private Integer skuCount;

	private String categoryName;

	public Integer getTotalStock() {
		return totalStock;
	}

	public void setTotalStock(Integer totalStock) {
		this.totalStock = totalStock;
	}

	public Integer getSkuCount() {
		return skuCount;
	}

	public void setSkuCount(Integer skuCount) {
		this.skuCount = skuCount;
	}

	public String getCategoryName() {
		return categoryName;
	}

	public void setCategoryName(String categoryName) {
		this.categoryName = categoryName;
	}

	public void setProductId(String productId){
		this.productId = productId;
	}

	public String getProductId(){
		return this.productId;
	}

	public void setProductName(String productName){
		this.productName = productName;
	}

	public String getProductName(){
		return this.productName;
	}

	public void setProductDesc(String productDesc){
		this.productDesc = productDesc;
	}

	public String getProductDesc(){
		return this.productDesc;
	}

	public void setCover(String cover){
		this.cover = cover;
	}

	public String getCover(){
		return this.cover;
	}

	public void setCreateTime(Date createTime){
		this.createTime = createTime;
	}

	public Date getCreateTime(){
		return this.createTime;
	}

	public void setCategoryId(String categoryId){
		this.categoryId = categoryId;
	}

	public String getCategoryId(){
		return this.categoryId;
	}

	public void setpCategoryId(String pCategoryId){
		this.pCategoryId = pCategoryId;
	}

	public String getpCategoryId(){
		return this.pCategoryId;
	}

	public void setStatus(Integer status){
		this.status = status;
	}

	public Integer getStatus(){
		return this.status;
	}

	public void setMinPrice(BigDecimal minPrice){
		this.minPrice = minPrice;
	}

	public BigDecimal getMinPrice(){
		return this.minPrice;
	}

	public void setMaxPrice(BigDecimal maxPrice){
		this.maxPrice = maxPrice;
	}

	public BigDecimal getMaxPrice(){
		return this.maxPrice;
	}

	public void setTotalSale(Integer totalSale){
		this.totalSale = totalSale;
	}

	public Integer getTotalSale(){
		return this.totalSale;
	}

	public void setCommendType(Integer commendType){
		this.commendType = commendType;
	}

	public Integer getCommendType(){
		return this.commendType;
	}

	@Override
	public String toString (){
		return "商品ID:"+(productId == null ? "空" : productId)+"，商品名称:"+(productName == null ? "空" : productName)+"，商品描述:"+(productDesc == null ? "空" : productDesc)+"，封面:"+(cover == null ? "空" : cover)+"，创建时间:"+(createTime == null ? "空" : DateUtil.format(createTime, DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()))+"，分类ID:"+(categoryId == null ? "空" : categoryId)+"，分类父ID:"+(pCategoryId == null ? "空" : pCategoryId)+"，-1:已删除 0:下架  1:上架:"+(status == null ? "空" : status)+"，最低价格:"+(minPrice == null ? "空" : minPrice)+"，最高价格:"+(maxPrice == null ? "空" : maxPrice)+"，销量:"+(totalSale == null ? "空" : totalSale)+"，0:未推荐 1:已经推荐:"+(commendType == null ? "空" : commendType);
	}
}
