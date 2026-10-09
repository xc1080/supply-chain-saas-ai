package com.simlect.entity.po;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class SysCategory implements Serializable {

	private String categoryId;

	private String categoryName;

	private String pCategoryId;

	private Integer sort;

	// 子节点
	private List<SysCategory> children = new ArrayList<>();

	public List<SysProductProperty> getproductPropertyList() {
		return productPropertyList;
	}

	public void setproductPropertyList(List<SysProductProperty> productPropertyList) {
		this.productPropertyList = productPropertyList;
	}

	private List<SysProductProperty> productPropertyList;

	public List<SysCategory> getChildren() {
		return children;
	}

	public void setChildren(List<SysCategory> children) {
		this.children = children;
	}

	public void setCategoryId(String categoryId){
		this.categoryId = categoryId;
	}

	public String getCategoryId(){
		return this.categoryId;
	}

	public void setCategoryName(String categoryName){
		this.categoryName = categoryName;
	}

	public String getCategoryName(){
		return this.categoryName;
	}

	public void setpCategoryId(String pCategoryId){
		this.pCategoryId = pCategoryId;
	}

	public String getpCategoryId(){
		return this.pCategoryId;
	}

	public void setSort(Integer sort){
		this.sort = sort;
	}

	public Integer getSort(){
		return this.sort;
	}

	@Override
	public String toString (){
		return "categoryId:"+(categoryId == null ? "空" : categoryId)+"，categoryName:"+(categoryName == null ? "空" : categoryName)+"，pCategoryId:"+(pCategoryId == null ? "空" : pCategoryId)+"，sort:"+(sort == null ? "空" : sort);
	}
}
