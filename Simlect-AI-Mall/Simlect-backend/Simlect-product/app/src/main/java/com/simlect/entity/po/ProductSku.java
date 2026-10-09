package com.simlect.entity.po;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.io.Serializable;

public class ProductSku implements Serializable {

	private String productId;

	@NotEmpty
	private String propertyValueIdHash;

	@NotEmpty
	private String propertyValueIds;

	@NotEmpty
	private BigDecimal price;

	private Integer stock;

	@NotEmpty
	private Integer sort;

	public void setProductId(String productId){
		this.productId = productId;
	}

	public String getProductId(){
		return this.productId;
	}

	public void setPropertyValueIdHash(String propertyValueIdHash){
		this.propertyValueIdHash = propertyValueIdHash;
	}

	public String getPropertyValueIdHash(){
		return this.propertyValueIdHash;
	}

	public void setPropertyValueIds(String propertyValueIds){
		this.propertyValueIds = propertyValueIds;
	}

	public String getPropertyValueIds(){
		return this.propertyValueIds;
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

	@Override
	public String toString (){
		return "商品ID:"+(productId == null ? "空" : productId)+"，属性值id组hash:"+(propertyValueIdHash == null ? "空" : propertyValueIdHash)+"，属性值id组:"+(propertyValueIds == null ? "空" : propertyValueIds)+"，价格:"+(price == null ? "空" : price)+"，库存:"+(stock == null ? "空" : stock)+"，排序:"+(sort == null ? "空" : sort);
	}
}
