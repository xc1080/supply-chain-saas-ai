package com.simlect.entity.po;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.Serializable;
import java.util.List;

public class SysProductProperty implements Serializable {

	private String propertyId;

	private String propertyName;

	private String pCategoryId;

	private String categoryId;

	private Integer propertySort;

	private Integer coverType;

	public void setPropertyId(String propertyId){
		this.propertyId = propertyId;
	}

	public String getPropertyId(){
		return this.propertyId;
	}

	public void setPropertyName(String propertyName){
		this.propertyName = propertyName;
	}

	public String getPropertyName(){
		return this.propertyName;
	}

	public void setpCategoryId(String pCategoryId){
		this.pCategoryId = pCategoryId;
	}

	public String getpCategoryId(){
		return this.pCategoryId;
	}

	public void setCategoryId(String categoryId){
		this.categoryId = categoryId;
	}

	public String getCategoryId(){
		return this.categoryId;
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

	@Override
	public String toString (){
		return "属性ID:"+(propertyId == null ? "空" : propertyId)+"，属性名称:"+(propertyName == null ? "空" : propertyName)+"，一级分类:"+(pCategoryId == null ? "空" : pCategoryId)+"，二级分类:"+(categoryId == null ? "空" : categoryId)+"，排序:"+(propertySort == null ? "空" : propertySort)+"，0:无需传封面 1:需传封面:"+(coverType == null ? "空" : coverType);
	}
}
