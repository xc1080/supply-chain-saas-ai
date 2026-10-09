package com.simlect.entity.query;

import com.simlect.entity.po.SysCategory;
import com.simlect.entity.po.SysProductProperty;

import java.util.ArrayList;
import java.util.List;

public class SysCategoryQuery extends BaseParam {

	private String categoryId;

	private String categoryIdFuzzy;

	private String categoryName;

	private String categoryNameFuzzy;

	private String pCategoryId;

	private String pCategoryIdFuzzy;

	private Integer sort;

	// 是否是父节点
	private Boolean isParent;

	private List<SysCategory> children = new ArrayList<>();

	public List<SysCategory> getChildren() {
		return children;
	}

	public void setChildren(List<SysCategory> children) {
		this.children = children;
	}

	public List<SysProductProperty> getproductPropertyList() {
		return productPropertyList;
	}

	public void setproductPropertyList(List<SysProductProperty> productPropertyList) {
		this.productPropertyList = productPropertyList;
	}

	private List<SysProductProperty> productPropertyList = new ArrayList<>();

	public Boolean getPropertyQuery() {
		return propertyQuery;
	}

	public void setPropertyQuery(Boolean propertyQuery) {
		this.propertyQuery = propertyQuery;
	}

	private Boolean propertyQuery;

	public Boolean getParent() {
		return isParent;
	}

	public void setParent(Boolean parent) {
		isParent = parent;
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

	public void setCategoryName(String categoryName){
		this.categoryName = categoryName;
	}

	public String getCategoryName(){
		return this.categoryName;
	}

	public void setCategoryNameFuzzy(String categoryNameFuzzy){
		this.categoryNameFuzzy = categoryNameFuzzy;
	}

	public String getCategoryNameFuzzy(){
		return this.categoryNameFuzzy;
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

	public void setSort(Integer sort){
		this.sort = sort;
	}

	public Integer getSort(){
		return this.sort;
	}

}
