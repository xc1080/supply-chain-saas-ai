package com.simlect.entity.query;

import java.util.List;

public class ProductPropertyValueQuery extends BaseParam {

	private String productId;

	private String productIdFuzzy;

	private String propertyId;

	private String propertyIdFuzzy;

	private String propertyName;

	private String propertyNameFuzzy;

	private Integer propertySort;

	private Integer coverType;

	private String propertyValueId;

	private String propertyValueIdFuzzy;

	private String propertyCover;

	private String propertyCoverFuzzy;

	private String propertyValue;

	private String propertyValueFuzzy;

	private String propertyRemark;

	private String propertyRemarkFuzzy;

	private Integer sort;

	private List<String> ProductIdList;

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

	public void setPropertyId(String propertyId){
		this.propertyId = propertyId;
	}

	public String getPropertyId(){
		return this.propertyId;
	}

	public void setPropertyIdFuzzy(String propertyIdFuzzy){
		this.propertyIdFuzzy = propertyIdFuzzy;
	}

	public String getPropertyIdFuzzy(){
		return this.propertyIdFuzzy;
	}

	public void setPropertyName(String propertyName){
		this.propertyName = propertyName;
	}

	public String getPropertyName(){
		return this.propertyName;
	}

	public void setPropertyNameFuzzy(String propertyNameFuzzy){
		this.propertyNameFuzzy = propertyNameFuzzy;
	}

	public String getPropertyNameFuzzy(){
		return this.propertyNameFuzzy;
	}

	public void setPropertySort(Integer propertySort){
		this.propertySort = propertySort;
	}

	public Integer getPropertySort(){
		return this.propertySort;
	}

	public void setCoverType(Integer coverType){
		this.coverType = coverType;
	}

	public Integer getCoverType(){
		return this.coverType;
	}

	public void setPropertyValueId(String propertyValueId){
		this.propertyValueId = propertyValueId;
	}

	public String getPropertyValueId(){
		return this.propertyValueId;
	}

	public void setPropertyValueIdFuzzy(String propertyValueIdFuzzy){
		this.propertyValueIdFuzzy = propertyValueIdFuzzy;
	}

	public String getPropertyValueIdFuzzy(){
		return this.propertyValueIdFuzzy;
	}

	public void setPropertyCover(String propertyCover){
		this.propertyCover = propertyCover;
	}

	public String getPropertyCover(){
		return this.propertyCover;
	}

	public void setPropertyCoverFuzzy(String propertyCoverFuzzy){
		this.propertyCoverFuzzy = propertyCoverFuzzy;
	}

	public String getPropertyCoverFuzzy(){
		return this.propertyCoverFuzzy;
	}

	public void setPropertyValue(String propertyValue){
		this.propertyValue = propertyValue;
	}

	public String getPropertyValue(){
		return this.propertyValue;
	}

	public void setPropertyValueFuzzy(String propertyValueFuzzy){
		this.propertyValueFuzzy = propertyValueFuzzy;
	}

	public String getPropertyValueFuzzy(){
		return this.propertyValueFuzzy;
	}

	public void setPropertyRemark(String propertyRemark){
		this.propertyRemark = propertyRemark;
	}

	public String getPropertyRemark(){
		return this.propertyRemark;
	}

	public void setPropertyRemarkFuzzy(String propertyRemarkFuzzy){
		this.propertyRemarkFuzzy = propertyRemarkFuzzy;
	}

	public String getPropertyRemarkFuzzy(){
		return this.propertyRemarkFuzzy;
	}

	public void setSort(Integer sort){
		this.sort = sort;
	}

	public Integer getSort(){
		return this.sort;
	}

}
