package com.simlect.entity.query;

import java.math.BigDecimal;
import java.util.List;

public class ProductSkuQuery extends BaseParam {

	private String productId;

	private String productIdFuzzy;

	private String propertyValueIdHash;

	private String propertyValueIdHashFuzzy;

	private String propertyValueIds;

	private String propertyValueIdsFuzzy;

	private BigDecimal price;

	private Integer stock;

	private Integer sort;

	private List<String> ProductIdList;

	private Integer lessStock;

	public Integer getLessStock() {
		return lessStock;
	}

	public void setLessStock(Integer lessStock) {
		this.lessStock = lessStock;
	}

	public List<String> getProductIdList() {
		return ProductIdList;
	}

	public void setProductIdList(List<String> productIdList) {
		ProductIdList = productIdList;
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

	public void setPrice(BigDecimal price){
		this.price = price;
	}

	public BigDecimal getPrice(){
		return this.price;
	}

	public void setStock(Integer stock){
		this.stock = stock;
	}

	public Integer getStock(){
		return this.stock;
	}

	public void setSort(Integer sort){
		this.sort = sort;
	}

	public Integer getSort(){
		return this.sort;
	}

}
