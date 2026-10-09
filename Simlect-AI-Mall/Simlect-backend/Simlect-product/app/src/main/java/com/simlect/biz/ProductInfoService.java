package com.simlect.biz;

import java.util.List;

import com.simlect.entity.dto.ProductSaveDTO;
import com.simlect.entity.vo.Product4VO;
import com.simlect.entity.query.ProductInfoQuery;
import com.simlect.entity.po.ProductInfo;
import com.simlect.entity.vo.PaginationResultVO;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public interface ProductInfoService {

	List<ProductInfo> findListByParam(ProductInfoQuery param);

	Integer findCountByParam(ProductInfoQuery param);

	PaginationResultVO<ProductInfo> findListByPage(ProductInfoQuery param);

	Integer add(ProductInfo bean);

	Integer addBatch(List<ProductInfo> listBean);

	Integer addOrUpdateBatch(List<ProductInfo> listBean);

	Integer updateByParam(ProductInfo bean,ProductInfoQuery param);

	Integer deleteByParam(ProductInfoQuery param);

	ProductInfo getProductInfoByProductId(String productId);

	Integer updateProductInfoByProductId(ProductInfo bean,String productId);

	Integer deleteProductInfoByProductId(String productId);

    void saveProduct(ProductSaveDTO productSaveDTO);

	PaginationResultVO findListByPage4ListVO(ProductInfoQuery query);

	Product4VO getProduct4VOByProductId(@NotEmpty String productId);

	void updateSkuStock(@NotNull String skuId, @NotNull Integer stock);

	void updateProductStatus(@NotNull String productId, @NotNull Integer status);

	void deleteProduct(@NotNull String productId);

	void commendProduct(@NotNull String productId, @NotNull Integer commendType);

	void updateTotalSale(List<String> productIdList);

	void updateTotalSaleByCount(java.util.Map<String, Integer> productSaleMap);
}
