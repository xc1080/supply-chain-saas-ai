package com.simlect.entity.query;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

public class ProductInfoQuery extends BaseParam {

	private String productId;

	private String productIdFuzzy;

	private String productName;

	private String productNameFuzzy;

	private String productDesc;

	private String productDescFuzzy;

	private String cover;

	private String coverFuzzy;

	private String createTime;

	private String createTimeStart;

	private String createTimeEnd;

	private String categoryId;

	private String categoryIdFuzzy;

	private String pCategoryId;

	private String pCategoryIdFuzzy;

	private Integer status;

	private BigDecimal minPrice;

	private BigDecimal maxPrice;

	private Integer totalSale;

	private Integer commendType;

	private String CategoryIdOrPCategoryId;

	private Boolean categoryUnionQuery;

	private Boolean queryDesc;

	private BigDecimal priceFrom;

	private BigDecimal priceTo;

	public Boolean getQueryDesc() {
		return queryDesc;
	}

	public void setQueryDesc(Boolean queryDesc) {
		this.queryDesc = queryDesc;
	}

	public List<String> getProductIdList() {
		return ProductIdList;
	}

	public void setProductIdList(List<String> productIdList) {
		ProductIdList = productIdList;
	}

	private List<String> ProductIdList;

	public String getCategoryIdOrPCategoryId() {
		return CategoryIdOrPCategoryId;
	}

	public void setCategoryIdOrPCategoryId(String categoryIdOrPCategoryId) {
		CategoryIdOrPCategoryId = categoryIdOrPCategoryId;
	}

	public Boolean getCategoryUnionQuery() {
		return categoryUnionQuery;
	}

	public void setCategoryUnionQuery(Boolean categoryUnionQuery) {
		this.categoryUnionQuery = categoryUnionQuery;
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

	public void setProductDesc(String productDesc){
		this.productDesc = productDesc;
	}

	public String getProductDesc(){
		return this.productDesc;
	}

	public void setProductDescFuzzy(String productDescFuzzy){
		this.productDescFuzzy = productDescFuzzy;
	}

	public String getProductDescFuzzy(){
		return this.productDescFuzzy;
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

	public void setCategoryId(String categoryId){
		this.categoryId = categoryId;
	}

	public String getCategoryId(){
		return this.categoryId;
	}

	public void setCategoryIdFuzzy(String categoryIdFuzzy){
		this.categoryIdFuzzy = categoryIdFuzzy;
	}

	public String getCategoryIdFuzzy(){
		return this.categoryIdFuzzy;
	}

	public void setpCategoryId(String pCategoryId){
		this.pCategoryId = pCategoryId;
	}

	public String getpCategoryId(){
		return this.pCategoryId;
	}

	public void setpCategoryIdFuzzy(String pCategoryIdFuzzy){
		this.pCategoryIdFuzzy = pCategoryIdFuzzy;
	}

	public String getpCategoryIdFuzzy(){
		return this.pCategoryIdFuzzy;
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

	public BigDecimal getPriceFrom() {
		return priceFrom;
	}

	public void setPriceFrom(BigDecimal priceFrom) {
		this.priceFrom = priceFrom;
	}

	public BigDecimal getPriceTo() {
		return priceTo;
	}

	public void setPriceTo(BigDecimal priceTo) {
		this.priceTo = priceTo;
	}

}
