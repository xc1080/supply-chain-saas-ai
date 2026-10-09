package com.simlect.entity.query;

public class SysProductPropertyQuery extends BaseParam {

	private String propertyId;

	private String propertyIdFuzzy;

	private String propertyName;

	private String propertyNameFuzzy;

	private String pCategoryId;

	private String pCategoryIdFuzzy;

	private String categoryId;

	private String categoryIdFuzzy;

	private Integer propertySort;

	private Integer coverType;

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

}
