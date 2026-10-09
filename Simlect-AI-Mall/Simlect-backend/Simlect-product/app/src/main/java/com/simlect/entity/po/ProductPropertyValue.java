package com.simlect.entity.po;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotEmpty;

import java.io.Serializable;

public class ProductPropertyValue implements Serializable {

	private String productId;

	@NotEmpty
	private String propertyId;

	@NotEmpty
	private String propertyName;

	@NotEmpty
	private Integer propertySort;

	@NotEmpty
	private Integer coverType;

	@NotEmpty
	private String propertyValueId;

	private String propertyCover;

	@NotEmpty
	private String propertyValue;

	private String propertyRemark;

	private Integer sort;

	public void setProductId(String productId){
		this.productId = productId;
	}

	public String getProductId(){
		return this.productId;
	}

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

	public void setPropertyCover(String propertyCover){
		this.propertyCover = propertyCover;
	}

	public String getPropertyCover(){
		return this.propertyCover;
	}

	public void setPropertyValue(String propertyValue){
		this.propertyValue = propertyValue;
	}

	public String getPropertyValue(){
		return this.propertyValue;
	}

	public void setPropertyRemark(String propertyRemark){
		this.propertyRemark = propertyRemark;
	}

	public String getPropertyRemark(){
		return this.propertyRemark;
	}

	public void setSort(Integer sort){
		this.sort = sort;
	}

	public Integer getSort(){
		return this.sort;
	}

	@Override
	public String toString (){
		return "商品ID:"+(productId == null ? "空" : productId)+"，属性ID:"+(propertyId == null ? "空" : propertyId)+"，属性名称:"+(propertyName == null ? "空" : propertyName)+"，属性排序:"+(propertySort == null ? "空" : propertySort)+"，0:无需传封面 1:需传封面:"+(coverType == null ? "空" : coverType)+"，propertyValueId:"+(propertyValueId == null ? "空" : propertyValueId)+"，属性封面:"+(propertyCover == null ? "空" : propertyCover)+"，属性值:"+(propertyValue == null ? "空" : propertyValue)+"，备注:"+(propertyRemark == null ? "空" : propertyRemark)+"，属性值排序:"+(sort == null ? "空" : sort);
	}
}
