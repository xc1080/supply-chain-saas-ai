package com.simlect.biz.impl;

import com.simlect.api.enums.CommendTypeEnum;
import com.simlect.api.enums.ProductStatusEnum;
import com.simlect.api.support.StockFeignSupport;
import com.simlect.api.vo.ProductPropertyVO;
import com.simlect.api.vo.ProductPropertyValueVO;
import com.simlect.biz.ProductInfoService;
import com.simlect.component.ProductBloomFilterComponent;
import com.simlect.constants.Constants;
import com.simlect.constants.RabbitMQConfig;
import com.simlect.constants.ReliableMessageSender;
import com.simlect.entity.dto.ProductSaveDTO;
import com.simlect.entity.dto.RagDataDTO;
import com.simlect.entity.enums.MessageReliabilityLevelEnum;
import com.simlect.entity.enums.PageSize;
import com.simlect.entity.enums.RagDataTypeEnum;
import com.simlect.entity.enums.ResponseCodeEnum;
import com.simlect.entity.po.Product4Load;
import com.simlect.entity.po.ProductInfo;
import com.simlect.entity.po.ProductPropertyValue;
import com.simlect.entity.po.ProductSku;
import com.simlect.entity.query.ProductInfoQuery;
import com.simlect.entity.query.ProductPropertyValueQuery;
import com.simlect.entity.query.ProductSkuQuery;
import com.simlect.entity.query.SimplePage;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.vo.Product4VO;
import com.simlect.exception.BusinessException;
import com.simlect.mappers.ProductInfoMapper;
import com.simlect.mappers.ProductPropertyValueMapper;
import com.simlect.mappers.ProductSkuMapper;
import com.simlect.mappers.SysCategoryMapper;
import com.simlect.support.MqIdempotencyKeys;
import com.simlect.utils.CollectionCompare;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service("productInfoService")
@Slf4j
public class ProductInfoServiceImpl implements ProductInfoService {

	@Resource
	private ProductInfoMapper<ProductInfo, ProductInfoQuery> productInfoMapper;
    @Autowired
    private ProductPropertyValueMapper productPropertyValueMapper;
    @Autowired
    private ProductSkuMapper productSkuMapper;
    @Autowired
    private SysCategoryMapper sysCategoryMapper;
	@Resource
	private ReliableMessageSender reliableMessageSender;
	@Resource
	private ProductBloomFilterComponent productBloomFilterComponent;
	@Resource
	private StockFeignSupport stockFeignSupport;

	@Override
	public List<ProductInfo> findListByParam(ProductInfoQuery param) {
		return this.productInfoMapper.selectList(param);
	}

	@Override
	public Integer findCountByParam(ProductInfoQuery param) {
		return this.productInfoMapper.selectCount(param);
	}

	@Override
	public PaginationResultVO<ProductInfo> findListByPage(ProductInfoQuery param) {
		boolean categoryUnion = useCategoryUnionQuery(param);
		if (categoryUnion) {
			prepareCategoryUnionQuery(param);
		}
		int count = categoryUnion
				? productInfoMapper.selectCountByCategoryUnion(param)
				: this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<ProductInfo> list = categoryUnion
				? productInfoMapper.selectListByCategoryUnion(param)
				: this.findListByParam(param);
		PaginationResultVO<ProductInfo> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	private boolean useCategoryUnionQuery(ProductInfoQuery param) {
		return !StringTools.isEmpty(param.getCategoryIdOrPCategoryId());
	}

	private void prepareCategoryUnionQuery(ProductInfoQuery param) {
		param.setCategoryUnionQuery(true);
		if (param.getOrderBy() != null && param.getOrderBy().contains("p.")) {
			param.setOrderBy(param.getOrderBy().replace("p.", ""));
		}
	}

	@Override
	public Integer add(ProductInfo bean) {
		return this.productInfoMapper.insert(bean);
	}

	@Override
	public Integer addBatch(List<ProductInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productInfoMapper.insertBatch(listBean);
	}

	@Override
	public Integer addOrUpdateBatch(List<ProductInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productInfoMapper.insertOrUpdateBatch(listBean);
	}

	@Override
	public Integer updateByParam(ProductInfo bean, ProductInfoQuery param) {
		StringTools.checkParam(param);
		return this.productInfoMapper.updateByParam(bean, param);
	}

	@Override
	public Integer deleteByParam(ProductInfoQuery param) {
		StringTools.checkParam(param);
		return this.productInfoMapper.deleteByParam(param);
	}

	@Override
	public ProductInfo getProductInfoByProductId(String productId) {
		if (!productBloomFilterComponent.mightExist(productId)) {
			return null;
		}
		ProductInfo productInfo = this.productInfoMapper.selectByProductId(productId);
		if (productInfo != null) {
			productBloomFilterComponent.add(productId);
		}
		return productInfo;
	}

	@Override
	public Integer updateProductInfoByProductId(ProductInfo bean, String productId) {
		return this.productInfoMapper.updateByProductId(bean, productId);
	}

	@Override
	public Integer deleteProductInfoByProductId(String productId) {
		return this.productInfoMapper.deleteByProductId(productId);
	}

	@Override
	@Transactional
	public void saveProduct(ProductSaveDTO productSaveDTO) {
		ProductInfo productInfo = productSaveDTO.getProductInfo();
		List<ProductPropertyValue> productPropertyList = productSaveDTO.getProductPropertyList();
		List<ProductSku> productSkuList = productSaveDTO.getSkuList();
		//判断是否是新增
		Boolean isAdd = productInfo.getProductId() == null;
		// 如果是新增，生成15位随机数id,设置创建时间精确到秒
		if (isAdd) {
			productInfo.setProductId(StringTools.getRandomNumber(Constants.LENGTH_15));
		}
		// 分别为productProperty和productSku设置productId
		for (ProductPropertyValue productPropertyValue : productPropertyList) {
			productPropertyValue.setProductId(productInfo.getProductId());
		}
		for (ProductSku productSku : productSkuList) {
			productSku.setProductId(productInfo.getProductId());
		}
		productInfo.setStatus(null);
		productInfo.setCommendType(null);
		// 获取商品的最低价格
		productInfo.setMinPrice(productSkuList.stream().map(ProductSku::getPrice).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO));
		// 获取商品的最高价格
		productInfo.setMaxPrice(productSkuList.stream().map(ProductSku::getPrice).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO));
		if (isAdd){
			productInfo.setCreateTime(StringTools.getCurrentDate());
			// 设置状态为未上架
			productInfo.setStatus(Constants.NOT_ON_SALE);
			// 同时将商品信息、商品属性、商品SKU插入数据库
			productInfoMapper.insert(productInfo);
			productPropertyValueMapper.insertBatch(productPropertyList);
			productSkuMapper.insertBatch(productSkuList);
			productBloomFilterComponent.add(productInfo.getProductId());
		}// 否则为修改
		 else{
			 // 修改商品信息、商品属性、商品SKU
			 // 为商品信息设置不能修改的属性
			 productInfo.setStatus(null);
			 productInfo.setProductId(productInfo.getProductId());
			 productInfo.setCreateTime(null);
			 productInfo.setCategoryId(null);
			 productInfo.setpCategoryId(null);
			 productInfo.setCommendType(null);
			 productInfoMapper.updateByProductId(productInfo, productInfo.getProductId());
			 // 关于新增、修改、删除的比较可以使用Utils类中的CollectionCompare().compare()方法
			 // 对于商品属性，需要比较是新增，修改，还是删除
			// 创建查询query获取所有商品属性
			 ProductPropertyValueQuery productPropertyValueQuery = new ProductPropertyValueQuery();
			 productPropertyValueQuery.setProductId(productInfo.getProductId());
			 List<ProductPropertyValue> dbPropertyList = productPropertyValueMapper.selectList(productPropertyValueQuery);
			CollectionCompare.CompareResult<ProductPropertyValue> compareResult = new CollectionCompare<ProductPropertyValue>().compare(dbPropertyList, productPropertyList, ProductPropertyValue::getPropertyValueId);
			if (compareResult.addList != null && !compareResult.addList.isEmpty()){
				productPropertyValueMapper.insertBatch(compareResult.addList);
			}
			if (compareResult.updateList != null && !compareResult.updateList.isEmpty()){
				productPropertyValueMapper.updateBatch(productInfo.getProductId(),compareResult.updateList);
			}
			if (compareResult.deleteList != null && !compareResult.deleteList.isEmpty()){
				productPropertyValueMapper.deleteBatch(productInfo.getProductId(),compareResult.deleteList);
			}
			// 对于商品SKU，需要比较是新增，修改，还是删除
			// 创建查询query获取所有商品SKU
			ProductSkuQuery productSkuQuery = new ProductSkuQuery();
			productSkuQuery.setProductId(productInfo.getProductId());
			List<ProductSku> dbSkuList = productSkuMapper.selectList(productSkuQuery);
			CollectionCompare.CompareResult<ProductSku> compareResult4Sku = new CollectionCompare<ProductSku>().compare(dbSkuList, productSkuList, ProductSku::getPropertyValueIdHash);
			if (compareResult4Sku.addList != null && !compareResult4Sku.addList.isEmpty()){
				productSkuMapper.insertBatch(compareResult4Sku.addList);
			}
			if (compareResult4Sku.updateList != null && !compareResult4Sku.updateList.isEmpty()){
				productSkuMapper.updateBatch(productInfo.getProductId(),compareResult4Sku.updateList);
			}
			if (compareResult4Sku.deleteList != null && !compareResult4Sku.deleteList.isEmpty()){
				productSkuMapper.deleteBatch(productInfo.getProductId(),compareResult4Sku.deleteList);
			}
		}
		 writeProductInfo2ES(productInfo.getProductId());
		final boolean added = isAdd;
		// 待数据库操作完成后
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
			@Override
			public void afterCommit() {
				if (added) {
					productBloomFilterComponent.add(productInfo.getProductId());
				}
				// 将商品数据保存到向量数据库中，放入队列
				RagDataDTO ragDataDTO = new RagDataDTO(productInfo.getProductId(), RagDataTypeEnum.PRODUCT.getType());
				reliableMessageSender.sendMessage(
						RabbitMQConfig.RAG_EXCHANGE,
						RabbitMQConfig.RAG_QUEUE_KEY,
						ragDataDTO,
						MqIdempotencyKeys.ragProduct(productInfo.getProductId()),
						MessageReliabilityLevelEnum.HIGH);
			}
		});
	}

	@Override
	public PaginationResultVO findListByPage4ListVO(ProductInfoQuery query) {
		// 在findListByPage的基础上在list中多查询totalStock（product_sku表）；skuCount（product_sku表）；categoryName（sys_category表）；
		PaginationResultVO<ProductInfo> resultVO = this.findListByPage(query);
		List<ProductInfo> list = resultVO.getList();
		List<Product4Load> product4LoadList = new ArrayList<>();
		for (ProductInfo productInfo : list) {
			// 创建一个Product4Load对象，将productInfo中的数据复制到Product4Load对象中
			Product4Load product4Load = new Product4Load();
			BeanUtils.copyProperties(productInfo, product4Load);
			// 加入新属性
			product4Load.setTotalStock(stockFeignSupport.totalByProduct(productInfo.getProductId()));
			product4Load.setSkuCount(productSkuMapper.selectCountByProductId(productInfo.getProductId()));
			product4Load.setCategoryName(sysCategoryMapper.selectNameByCategoryId(productInfo.getCategoryId()));
			// 加入product4LoadList
			product4LoadList.add(product4Load);
		}
		return new PaginationResultVO<>(resultVO.getTotalCount(), resultVO.getPageSize(), resultVO.getPageNo(), resultVO.getPageTotal(), product4LoadList);
	}

	@Override
	public Product4VO getProduct4VOByProductId(String productId) {
		if (!productBloomFilterComponent.mightExist(productId)) {
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}
		// 根据productId查询productInfo,productPropertyList,skuList
		ProductInfo productInfo = productInfoMapper.selectByProductId(productId);
		if (productInfo == null) {
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}
		productBloomFilterComponent.add(productId);
		// 获取productPropertyList
		// 创建Map<String,ProductPropertyValue>其中的String为productPropertyId
		// 同一个属性可以对应多个属性值，其中属性只需存储一次
		Map<String, ProductPropertyVO> productPropertyMap = new HashMap<>();
		// 查询所有属性值，创建查询query
		ProductPropertyValueQuery productPropertyValueQuery = new ProductPropertyValueQuery();
		productPropertyValueQuery.setProductId(productId);
		productPropertyValueQuery.setOrderBy("property_sort asc");
		List<ProductPropertyVO> ProductPropertyVOS = new ArrayList<>();
		List<ProductPropertyValue> productPropertyValueList = productPropertyValueMapper.selectList(productPropertyValueQuery);
		for (ProductPropertyValue productPropertyValue : productPropertyValueList) {
			// 创建ProductPropertyVO从Map中获取
			ProductPropertyVO productPropertyVO = productPropertyMap.get(productPropertyValue.getPropertyId());
			// 创建productPropertyValueVO
			ProductPropertyValueVO productPropertyValueVO = new ProductPropertyValueVO();
			productPropertyValueVO.setPropertyValueId(productPropertyValue.getPropertyValueId());
			productPropertyValueVO.setPropertyValue(productPropertyValue.getPropertyValue());
			productPropertyValueVO.setPropertyCover(productPropertyValue.getPropertyCover());
			productPropertyValueVO.setPropertyRemark(productPropertyValue.getPropertyRemark());
			// 如果Map中没有，则创建,并添加属性，添加到Map中
			if (productPropertyVO == null){
				productPropertyVO = new ProductPropertyVO();
				productPropertyVO.setPropertyId(productPropertyValue.getPropertyId());
				productPropertyVO.setPropertyName(productPropertyValue.getPropertyName());
				productPropertyVO.setPropertySort(productPropertyValue.getPropertySort());
				productPropertyVO.setCoverType(productPropertyValue.getCoverType());
				// 添加到Map中
				productPropertyMap.put(productPropertyValue.getPropertyId(), productPropertyVO);
				// 创建List<ProductPropertyValueVO>
				 List<ProductPropertyValueVO> propertyValueVOS = new ArrayList<>();
				 // 将productPropertyValueVO加到propertyValueVOS中
				 propertyValueVOS.add(productPropertyValueVO);
				 // 将propertyValueVOS加到productPropertyVO中
				 productPropertyVO.setPropertyValues(propertyValueVOS);
				 // 将productPropertyVO加到productPropertyVOS中
				 ProductPropertyVOS.add(productPropertyVO);
			}// 如果Map中有，则添加属性值
			else{
				productPropertyVO.getPropertyValues().add(productPropertyValueVO);
			}
		}
		// 获取skuList
		// 创建查询query
		ProductSkuQuery productSkuQuery = new ProductSkuQuery();
		productSkuQuery.setProductId(productId);
		productSkuQuery.setOrderBy("sort asc");
		List<ProductSku> productSkuList = productSkuMapper.selectList(productSkuQuery);
		for (ProductSku sku : productSkuList) {
			sku.setStock(stockFeignSupport.getAvailable(sku.getProductId(), sku.getPropertyValueIdHash()));
		}
		// 封装到Product4VO
		Product4VO product4VO = new Product4VO();
		product4VO.setProductInfo(productInfo);
		product4VO.setProductPropertyList(ProductPropertyVOS);
		product4VO.setSkuList(productSkuList);
		return product4VO;
	}

	@Override
	public void updateSkuStock(String skuId, Integer stock) {
		throw new BusinessException("请使用 productId + propertyValueIdHash 调整库存");
	}

	@Override
	public void updateProductStatus(String productId, Integer status) {
		ProductStatusEnum statusEnum = ProductStatusEnum.getByStatus(status);
		if (statusEnum == null || statusEnum == ProductStatusEnum.DELETE ) {
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}
		if (statusEnum == ProductStatusEnum.OFF_SALE) {
			ProductInfo existing = productInfoMapper.selectByProductId(productId);
			if (existing == null) {
				throw new BusinessException(ResponseCodeEnum.CODE_600);
			}
			if (CommendTypeEnum.COMMEND.getType().equals(existing.getCommendType())) {
				throw new BusinessException(ResponseCodeEnum.CODE_600.getCode(), "请先取消推荐后再下架");
			}
		}
		ProductInfo productInfo = new ProductInfo();
		productInfo.setStatus(status);
		productInfoMapper.updateByProductId(productInfo, productId);
		writeProductInfo2ES(productId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void deleteProduct(String productId) {
		ProductInfo existing = productInfoMapper.selectByProductId(productId);
		if (existing == null) {
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}
		if (ProductStatusEnum.ON_SALE.getStatus().equals(existing.getStatus())) {
			throw new BusinessException(ResponseCodeEnum.CODE_600.getCode(), "请先下架商品后再删除");
		}
		if (CommendTypeEnum.COMMEND.getType().equals(existing.getCommendType())) {
			throw new BusinessException(ResponseCodeEnum.CODE_600.getCode(), "请先取消推荐后再删除");
		}
		ProductInfo productInfo = new ProductInfo();
		productInfo.setStatus(ProductStatusEnum.DELETE.getStatus());
		productInfoMapper.updateByProductId(productInfo, productId);
		writeProductInfo2ES(productId);
		// 待数据库操作完成后
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
			@Override
			public void afterCommit() {
				RagDataDTO ragDataDTO = new RagDataDTO(productId, RagDataTypeEnum.PRODUCT.getType());
				reliableMessageSender.sendMessage(
						RabbitMQConfig.RAG_EXCHANGE,
						RabbitMQConfig.RAG_QUEUE_KEY,
						ragDataDTO,
						MqIdempotencyKeys.ragProduct(productId),
						MessageReliabilityLevelEnum.HIGH);
			}
		});
	}

	@Override
	public void commendProduct(String productId, Integer commendType) {
		CommendTypeEnum commendTypeEnum = CommendTypeEnum.getByType(commendType);
		if (commendTypeEnum == null) {
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}
		if (commendTypeEnum == CommendTypeEnum.COMMEND) {
			ProductInfo existing = productInfoMapper.selectByProductId(productId);
			if (existing == null || !ProductStatusEnum.ON_SALE.getStatus().equals(existing.getStatus())) {
				throw new BusinessException(ResponseCodeEnum.CODE_600.getCode(), "仅已上架商品可设为推荐");
			}
		}
		ProductInfo productInfo = new ProductInfo();
		productInfo.setCommendType(commendType);
		productInfoMapper.updateByProductId(productInfo, productId);
		if (commendTypeEnum == CommendTypeEnum.COMMEND) {
			productBloomFilterComponent.add(productId);
		}
	}

	private void writeProductInfo2ES(String productId) {
		RagDataDTO ragDataDTO = new RagDataDTO(productId, RagDataTypeEnum.PRODUCT.getType());
		reliableMessageSender.sendMessage(
				RabbitMQConfig.RAG_EXCHANGE,
				RabbitMQConfig.RAG_QUEUE_KEY,
				ragDataDTO,
				MqIdempotencyKeys.ragProduct(productId),
				MessageReliabilityLevelEnum.HIGH);
	}

	@Override
	public void updateTotalSale(List<String> productIdList) {
		if (productIdList == null || productIdList.isEmpty()) {
			return;
		}
		productInfoMapper.updateTotalSale(productIdList);
		// 更新ES中的销量信息
		for (String productId : productIdList) {
			writeProductInfo2ES(productId);
		}
	}

	@Override
	public void updateTotalSaleByCount(java.util.Map<String, Integer> productSaleMap) {
		if (productSaleMap == null || productSaleMap.isEmpty()) {
			return;
		}
		productInfoMapper.updateTotalSaleByCount(productSaleMap);
		// 更新ES中的销量信息
		for (String productId : productSaleMap.keySet()) {
			writeProductInfo2ES(productId);
		}
	}
}
