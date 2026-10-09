package com.simlect.biz.impl;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.simlect.api.dto.ProductSnapshotBatchVO;
import com.simlect.api.support.ProductFeignSupport;
import com.simlect.api.support.StockFeignSupport;
import com.simlect.constants.Constants;
import com.simlect.api.enums.ProductStatusEnum;
import com.simlect.api.vo.ProductInfoSnapshotVO;
import com.simlect.api.vo.ProductPropertyValueSnapshotVO;
import com.simlect.api.vo.ProductSkuSnapshotVO;
import com.simlect.entity.query.*;
import com.simlect.api.vo.ProductCartVO;
import com.simlect.api.vo.PropertyData;
import jakarta.annotation.Resource;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import com.simlect.entity.enums.PageSize;
import com.simlect.entity.po.ProductCart;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.exception.BusinessException;
import com.simlect.mappers.ProductCartMapper;
import com.simlect.biz.ProductCartService;
import com.simlect.utils.StringTools;
import org.springframework.transaction.annotation.Transactional;

@Service("productCartService")
public class ProductCartServiceImpl implements ProductCartService {

	@Resource
	private ProductCartMapper<ProductCart, ProductCartQuery> productCartMapper;

	@Resource
	private StockFeignSupport stockFeignSupport;

	@Resource
	private ProductFeignSupport productFeignSupport;

	@Override
	public List<ProductCart> findListByParam(ProductCartQuery param) {
		return this.productCartMapper.selectList(param);
	}

	@Override
	public Integer findCountByParam(ProductCartQuery param) {
		return this.productCartMapper.selectCount(param);
	}

	@Override
	public PaginationResultVO<ProductCart> findListByPage(ProductCartQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<ProductCart> list = this.findListByParam(param);
		PaginationResultVO<ProductCart> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	@Override
	public Integer add(ProductCart bean) {
		return this.productCartMapper.insert(bean);
	}

	@Override
	public Integer addBatch(List<ProductCart> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productCartMapper.insertBatch(listBean);
	}

	@Override
	public Integer addOrUpdateBatch(List<ProductCart> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productCartMapper.insertOrUpdateBatch(listBean);
	}

	@Override
	public Integer updateByParam(ProductCart bean, ProductCartQuery param) {
		StringTools.checkParam(param);
		return this.productCartMapper.updateByParam(bean, param);
	}

	@Override
	public Integer deleteByParam(ProductCartQuery param) {
		StringTools.checkParam(param);
		return this.productCartMapper.deleteByParam(param);
	}

	@Override
	public ProductCart getProductCartByCartId(String cartId) {
		return this.productCartMapper.selectByCartId(cartId);
	}

	@Override
	public Integer updateProductCartByCartId(ProductCart bean, String cartId) {
		return this.productCartMapper.updateByCartId(bean, cartId);
	}

	@Override
	public Integer deleteProductCartByCartId(String cartId) {
		return this.productCartMapper.deleteByCartId(cartId);
	}

	@Override
	public ProductCart getProductCartByProductIdAndPropertyValueIdHashAndUserId(String productId, String propertyValueIdHash, String userId) {
		return this.productCartMapper.selectByProductIdAndPropertyValueIdHashAndUserId(productId, propertyValueIdHash, userId);
	}

	@Override
	public Integer updateProductCartByProductIdAndPropertyValueIdHashAndUserId(ProductCart bean, String productId, String propertyValueIdHash, String userId) {
		return this.productCartMapper.updateByProductIdAndPropertyValueIdHashAndUserId(bean, productId, propertyValueIdHash, userId);
	}

	@Override
	public Integer deleteProductCartByProductIdAndPropertyValueIdHashAndUserId(String productId, String propertyValueIdHash, String userId) {
		return this.productCartMapper.deleteByProductIdAndPropertyValueIdHashAndUserId(productId, propertyValueIdHash, userId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void add2Cart(ProductCart productCart) {
		// 获取当前时间
		Date now = StringTools.getCurrentDate();
		String propertyValueIds = resolvePropertyValueIds(productCart.getProductId(), productCart.getPropertyValueIds());
		productCart.setPropertyValueIds(propertyValueIds);
		// 根据propertyValueIds计算Hash
		String propertyValueIdHash = StringTools.encodeByMD5(propertyValueIds);
		// 查询当前商品是否已经在购物车中
		ProductCart cart = this.getProductCartByProductIdAndPropertyValueIdHashAndUserId(productCart.getProductId(), propertyValueIdHash, productCart.getUserId());
		// 如果已经存在购物车中，则修改数量（保留首次加入时的单价）
		if (cart != null) {
			// 修改数据在数据库中操作
			productCartMapper.setBuyCountByProductIdAndPropertyValueIdHashAndUserId(productCart.getBuyCount(),productCart.getProductId(), propertyValueIdHash, productCart.getUserId());
			// 修改lastUpdateTime,此时不修改buyCount / addPrice
			productCart.setLastUpdateTime(now);
			productCart.setBuyCount(null);
			productCart.setAddPrice(null);
			productCartMapper.updateByProductIdAndPropertyValueIdHashAndUserId(productCart, productCart.getProductId(), propertyValueIdHash, productCart.getUserId());
			// 历史数据若缺少加购价，按当前价补一次
			if (cart.getAddPrice() == null) {
				ProductCart pricePatch = new ProductCart();
				pricePatch.setAddPrice(resolveSkuPrice(productCart.getProductId(), propertyValueIds));
				productCartMapper.updateByProductIdAndPropertyValueIdHashAndUserId(
						pricePatch, productCart.getProductId(), propertyValueIdHash, productCart.getUserId());
			}
		}else{
			// 如果不存在购物车中，则添加，并记录当时单价
			productCart.setPropertyValueIdHash(propertyValueIdHash);
			productCart.setAddPrice(resolveSkuPrice(productCart.getProductId(), propertyValueIds));
			productCart.setCreateTime(now);
			productCart.setLastUpdateTime(now);
			// 计算长度为15的随机数作为cartId
			String cartId = StringTools.getRandomNumber(Constants.LENGTH_15);
			productCart.setCartId(cartId);
			this.add(productCart);
		}
	}

	// 获取购物车列表
	@Override
	@Transactional(rollbackFor = Exception.class)
	public PaginationResultVO<ProductCartVO> findListByPageAndUserId(ProductCartQuery param, String userId) {
		if (userId == null) {
			return new PaginationResultVO<ProductCartVO>();
		}
		param.setUserId(userId);
		PaginationResultVO<ProductCart> resultVO = this.findListByPage(param);
		List<ProductCart> list = resultVO.getList();
		// 如果为空，返回空PaginationResultVO
		if (list == null || list.isEmpty()) {
			return new PaginationResultVO<ProductCartVO>();
		}
		// ProductCartQuery查询ProductCart
		list = this.findListByParam(param);
		// 创建List<ProductCartVO> ,把ProductCart转换成ProductCartVO
		// productCart中有cartId,productId,propertyValueIds,propertyValueIdHash,buyCount
		 List<ProductCartVO> listVO = new ArrayList<ProductCartVO>();
		 for (ProductCart productCart : list) {
			 ProductCartVO productCartVO = new ProductCartVO();
			 BeanUtils.copyProperties(productCart, productCartVO);
			 listVO.add(productCartVO);
		 }
		 // 还需补充productCover,propertyData在product_property_value表中
		// 若product_property_value表中productCover为null，则用product_info中的cover主图
		// price,stock在product_sku表中
		// productOnSale在product_info表中
		// 先查product_info表获得Cover,productOnSale
		// 通过productId查询,创建productId数组,从list逐一取出productId
		 List<String> productIdList = listVO.stream().map(ProductCartVO::getProductId).collect(Collectors.toList());
		 ProductSnapshotBatchVO snapshot = productFeignSupport.snapshotBatch(productIdList);
		 Map<String, ProductInfoSnapshotVO> productInfoMap = productFeignSupport.toProductInfoMap(snapshot);
		 Map<String, ProductPropertyValueSnapshotVO> productPropertyValueMap = productFeignSupport.toPropertyValueMap(snapshot);
		 Map<String, ProductSkuSnapshotVO> productSkuMap = productFeignSupport.toSkuMapByPropertyValueIds(snapshot);
		 Map<String, ProductSkuSnapshotVO> defaultSkuByProductId = productFeignSupport.toDefaultSkuByProductId(snapshot);
		 // 创建新的List<ProductCartVO>
		 List<ProductCartVO> newListVO = new ArrayList<ProductCartVO>();
		 // 遍历listVO，获取缺失的数据
		 for (ProductCartVO productCartVO : listVO) {
			 // 创建一个新的ProductCartVO
			 ProductCartVO newProductCartVO = new ProductCartVO();
			 BeanUtils.copyProperties(productCartVO, newProductCartVO);
			 // 填充缺失的数据
			 ProductInfoSnapshotVO productInfo = productInfoMap.get(productCartVO.getProductId());
			 if (productInfo != null){
				 String cover = productInfo.getCover();
				 // 如果cover包含多个图片，只取第一张
				 if (!StringTools.isEmpty(cover) && cover.contains(",")) {
					 cover = cover.split(",")[0];
				 }
				 newProductCartVO.setProductCover(cover);
				 newProductCartVO.setProductOnSale((Objects.equals(ProductStatusEnum.ON_SALE.getStatus(), productInfo.getStatus())));
				 newProductCartVO.setProductName(productInfo.getProductName());
			 }
		 	// 获得productSku对象
			 String cartPropertyValueIds = productCartVO.getPropertyValueIds();
			 ProductSkuSnapshotVO productSku = null;
			 if (!StringTools.isEmpty(cartPropertyValueIds)) {
				 productSku = productSkuMap.get(productCartVO.getProductId() + cartPropertyValueIds);
			 }
			 if (productSku == null) {
				 productSku = defaultSkuByProductId.get(productCartVO.getProductId());
			 }
			 if (productSku != null){
				 // price = 当前最新价；addPrice 已从购物车行带出（加入时单价）
				 newProductCartVO.setPrice(productSku.getPrice());
				 if (newProductCartVO.getAddPrice() == null) {
					 newProductCartVO.setAddPrice(productSku.getPrice());
				 }
				 newProductCartVO.setStock(stockFeignSupport.getAvailable(
						 productSku.getProductId(), productSku.getPropertyValueIdHash()));
				 if (StringTools.isEmpty(cartPropertyValueIds) && !StringTools.isEmpty(productSku.getPropertyValueIds())) {
					 newProductCartVO.setPropertyValueIds(productSku.getPropertyValueIds());
				 }
			 }
			 // 处理多个属性值ID
			 if (StringTools.isEmpty(cartPropertyValueIds)) {
				 newListVO.add(newProductCartVO);
				 continue;
			 }
			 String[] propertyValueIdArray = cartPropertyValueIds.split("-");
			 for (String propertyValueId : propertyValueIdArray) {
				 // 获得productPropertyValue对象
				 ProductPropertyValueSnapshotVO productPropertyValue = productPropertyValueMap.get(productCartVO.getProductId() + propertyValueId);
				 if (productPropertyValue != null) {
					 // 若productPropertyValue中productCover为null，则用product_info中的cover主图，不set
					 if (productPropertyValue.getPropertyCover() != null && !StringTools.isEmpty(productPropertyValue.getPropertyCover())) {
						 newProductCartVO.setProductCover(productPropertyValue.getPropertyCover());
					 }
					 // 创建PropertyData对象
					 PropertyData propertyData = new PropertyData();
					 propertyData.setPropertyName(productPropertyValue.getPropertyName());
					 propertyData.setPropertyValue(productPropertyValue.getPropertyValue());
					 // 将propertyData添加到newProductCartVO.propertyData
					 if (newProductCartVO.getPropertyData() == null) {
						 newProductCartVO.setPropertyData(new ArrayList<>());
					 }
					 newProductCartVO.getPropertyData().add(propertyData);
				 }
			 }
			 // 将newProductCartVO添加到newListVO
			 newListVO.add(newProductCartVO);
		 }
		return new PaginationResultVO<>(resultVO.getTotalCount(), resultVO.getPageSize(), resultVO.getPageNo(), resultVO.getPageTotal(), newListVO);
	}

	private String resolvePropertyValueIds(String productId, String propertyValueIds) {
		if (!StringTools.isEmpty(propertyValueIds)) {
			return propertyValueIds;
		}
		ProductSkuSnapshotVO defaultSku = productFeignSupport.defaultSku(productId);
		if (defaultSku == null) {
			throw new BusinessException("该商品暂不可加入购物车");
		}
		String resolved = defaultSku.getPropertyValueIds();
		return StringTools.isEmpty(resolved) ? "" : resolved;
	}

	private BigDecimal resolveSkuPrice(String productId, String propertyValueIds) {
		ProductSnapshotBatchVO snapshot = productFeignSupport.snapshotBatch(Collections.singletonList(productId));
		ProductSkuSnapshotVO productSku = null;
		if (!StringTools.isEmpty(propertyValueIds)) {
			productSku = productFeignSupport.toSkuMapByPropertyValueIds(snapshot).get(productId + propertyValueIds);
		}
		if (productSku == null) {
			productSku = productFeignSupport.toDefaultSkuByProductId(snapshot).get(productId);
		}
		if (productSku == null || productSku.getPrice() == null) {
			throw new BusinessException("该商品暂不可加入购物车");
		}
		return productSku.getPrice();
	}
}
