package com.simlect.biz.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.simlect.api.dto.SkuStockDTO;
import com.simlect.api.support.StockFeignSupport;
import com.simlect.api.enums.ProductStatusEnum;
import com.simlect.entity.po.ProductInfo;
import com.simlect.entity.po.ProductPropertyValue;
import com.simlect.api.vo.ProductSkuListVO;
import com.simlect.api.vo.ProductSkuProperDataVO;
import com.simlect.exception.BusinessException;
import com.simlect.mappers.ProductInfoMapper;
import com.simlect.mappers.ProductPropertyValueMapper;
import com.simlect.biz.ProductInfoService;
import jakarta.annotation.Resource;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.simlect.entity.enums.PageSize;
import com.simlect.entity.query.ProductSkuQuery;
import com.simlect.entity.po.ProductSku;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.query.SimplePage;
import com.simlect.mappers.ProductSkuMapper;
import com.simlect.biz.ProductSkuService;
import com.simlect.utils.StringTools;
import org.springframework.transaction.annotation.Transactional;

@Service("productSkuService")
public class ProductSkuServiceImpl implements ProductSkuService {

	@Resource
	private ProductSkuMapper<ProductSku, ProductSkuQuery> productSkuMapper;
	@Resource
	private StockFeignSupport stockFeignSupport;
	@Resource
	private ProductInfoService productInfoService;
    @Autowired
    private ProductInfoMapper productInfoMapper;
    @Autowired
    private ProductPropertyValueMapper productPropertyValueMapper;

	@Override
	public List<ProductSku> findListByParam(ProductSkuQuery param) {
		return this.productSkuMapper.selectList(param);
	}

	@Override
	public Integer findCountByParam(ProductSkuQuery param) {
		return this.productSkuMapper.selectCount(param);
	}

	@Override
	public PaginationResultVO<ProductSku> findListByPage(ProductSkuQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<ProductSku> list = this.findListByParam(param);
		PaginationResultVO<ProductSku> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	@Override
	public Integer add(ProductSku bean) {
		return this.productSkuMapper.insert(bean);
	}

	@Override
	public Integer addBatch(List<ProductSku> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productSkuMapper.insertBatch(listBean);
	}

	@Override
	public Integer addOrUpdateBatch(List<ProductSku> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productSkuMapper.insertOrUpdateBatch(listBean);
	}

	@Override
	public Integer updateByParam(ProductSku bean, ProductSkuQuery param) {
		StringTools.checkParam(param);
		return this.productSkuMapper.updateByParam(bean, param);
	}

	@Override
	public Integer deleteByParam(ProductSkuQuery param) {
		StringTools.checkParam(param);
		return this.productSkuMapper.deleteByParam(param);
	}

	@Override
	public ProductSku getProductSkuByProductIdAndPropertyValueIdHash(String productId, String propertyValueIdHash) {
		return this.productSkuMapper.selectByProductIdAndPropertyValueIdHash(productId, propertyValueIdHash);
	}

	@Override
	public Integer updateProductSkuByProductIdAndPropertyValueIdHash(ProductSku bean, String productId, String propertyValueIdHash) {
		return this.productSkuMapper.updateByProductIdAndPropertyValueIdHash(bean, productId, propertyValueIdHash);
	}

	@Override
	public Integer deleteProductSkuByProductIdAndPropertyValueIdHash(String productId, String propertyValueIdHash) {
		return this.productSkuMapper.deleteByProductIdAndPropertyValueIdHash(productId, propertyValueIdHash);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void updateStock(String productId, String propertyValueIdHash, Integer changeStock) {
		ProductSku productSku = this.getProductSkuByProductIdAndPropertyValueIdHash(productId, propertyValueIdHash);
		if (productSku == null) {
			throw new BusinessException("商品sku不存在");
		}
		stockFeignSupport.changeStock(productId, propertyValueIdHash, changeStock);
	}

	@Override
	public PaginationResultVO<ProductSkuListVO> findListByPage4ListVO(ProductSkuQuery query) {
		int count = this.findCountByParam(query);
		int pageSize = query.getPageSize() == null ? PageSize.SIZE15.getSize() : query.getPageSize();
		SimplePage page = new SimplePage(query.getPageNo(), count, pageSize);
		query.setSimplePage(page);
		List<ProductSku> productSkuList = findListByParam(query);
		List<ProductSkuListVO> productSkuListVOList = new ArrayList<>();
		for (ProductSku productSku : productSkuList){
			ProductInfo productInfo = productInfoService.getProductInfoByProductId(productSku.getProductId());
			if (productInfo == null) {
				continue;
			}
			ProductSkuListVO productSkuListVO = new ProductSkuListVO();
			BeanUtils.copyProperties(productSku, productSkuListVO);
			productSkuListVO.setStock(stockFeignSupport.getAvailable(
					productSku.getProductId(), productSku.getPropertyValueIdHash()));
			productSkuListVO.setProductName(productInfo.getProductName());
			productSkuListVO.setProductOnsale(ProductStatusEnum.ON_SALE.getStatus().equals(productInfo.getStatus()));
			List<ProductSkuProperDataVO> productSkuProperDataVOList = new ArrayList<>();
			if (!StringTools.isEmpty(productSku.getPropertyValueIds())) {
				String[] propertyValueIds = productSku.getPropertyValueIds().split("-");
				for (String propertyValueId : propertyValueIds) {
					ProductPropertyValue productPropertyValue = (ProductPropertyValue) productPropertyValueMapper.selectByProductIdAndPropertyValueId(productSku.getProductId(), propertyValueId);
					if (productPropertyValue == null) {
						continue;
					}
					ProductSkuProperDataVO productSkuProperDataVO = new ProductSkuProperDataVO();
					productSkuProperDataVO.setPropertyName(productPropertyValue.getPropertyName());
					productSkuProperDataVO.setPropertyValue(productPropertyValue.getPropertyValue());
					productSkuProperDataVOList.add(productSkuProperDataVO);
				}
			}
			if (productSkuListVO.getProductCover() == null && !StringTools.isEmpty(productInfo.getCover())){
				productSkuListVO.setProductCover(productInfo.getCover().split(",")[0]);
			}
			productSkuListVO.setPropertyData(productSkuProperDataVOList);
			productSkuListVOList.add(productSkuListVO);
		}
		return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), productSkuListVOList);
	}

	@Override
	public PaginationResultVO<ProductSkuListVO> lessStockSkuPage(Integer pageNo, Integer pageSize, Integer threshold) {
		int size = pageSize == null ? PageSize.SIZE15.getSize() : pageSize;
		int no = pageNo == null || pageNo < 1 ? 1 : pageNo;
		// 先拉全量低库存再富化过滤，最后内存分页。
		// 若按 stock 页直接切页，无 product_info 的孤儿 SKU 会占满首页（如 pageSize=4），
		// 导致真实库存=5 的预警被挤掉、列表为空或只剩残缺数据。
		List<ProductSkuListVO> all = new ArrayList<>();
		int stockPageNo = 1;
		final int batch = 100;
		while (true) {
			PaginationResultVO<SkuStockDTO> stockPage = stockFeignSupport.listLessThan(stockPageNo, batch, threshold);
			if (stockPage == null || stockPage.getList() == null || stockPage.getList().isEmpty()) {
				break;
			}
			for (SkuStockDTO skuStock : stockPage.getList()) {
				ProductSkuListVO vo = buildLessStockSkuVo(skuStock);
				if (vo != null) {
					all.add(vo);
				}
			}
			int stockPageTotal = stockPage.getPageTotal() == null ? 1 : stockPage.getPageTotal();
			if (stockPageNo >= stockPageTotal) {
				break;
			}
			stockPageNo++;
			if (stockPageNo > 200) {
				break;
			}
		}
		int count = all.size();
		SimplePage page = new SimplePage(no, count, size);
		int from = page.getStart();
		List<ProductSkuListVO> slice = from >= count
				? Collections.emptyList()
				: all.subList(from, Math.min(from + size, count));
		return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), slice);
	}

	private ProductSkuListVO buildLessStockSkuVo(SkuStockDTO skuStock) {
		if (skuStock == null || StringTools.isEmpty(skuStock.getProductId())
				|| StringTools.isEmpty(skuStock.getPropertyValueIdHash())) {
			return null;
		}
		ProductSku productSku = getProductSkuByProductIdAndPropertyValueIdHash(
				skuStock.getProductId(), skuStock.getPropertyValueIdHash());
		if (productSku == null) {
			return null;
		}
		ProductInfo productInfo = productInfoService.getProductInfoByProductId(productSku.getProductId());
		if (productInfo == null) {
			return null;
		}
		ProductSkuListVO productSkuListVO = new ProductSkuListVO();
		BeanUtils.copyProperties(productSku, productSkuListVO);
		productSkuListVO.setStock(skuStock.getStock() == null ? 0 : skuStock.getStock());
		productSkuListVO.setProductName(productInfo.getProductName());
		productSkuListVO.setProductOnsale(ProductStatusEnum.ON_SALE.getStatus().equals(productInfo.getStatus()));
		List<ProductSkuProperDataVO> productSkuProperDataVOList = new ArrayList<>();
		if (!StringTools.isEmpty(productSku.getPropertyValueIds())) {
			String[] propertyValueIds = productSku.getPropertyValueIds().split("-");
			for (String propertyValueId : propertyValueIds) {
				ProductPropertyValue productPropertyValue = (ProductPropertyValue) productPropertyValueMapper
						.selectByProductIdAndPropertyValueId(productSku.getProductId(), propertyValueId);
				if (productPropertyValue == null) {
					continue;
				}
				ProductSkuProperDataVO productSkuProperDataVO = new ProductSkuProperDataVO();
				productSkuProperDataVO.setPropertyName(productPropertyValue.getPropertyName());
				productSkuProperDataVO.setPropertyValue(productPropertyValue.getPropertyValue());
				productSkuProperDataVOList.add(productSkuProperDataVO);
			}
		}
		if (productSkuListVO.getProductCover() == null && !StringTools.isEmpty(productInfo.getCover())) {
			productSkuListVO.setProductCover(productInfo.getCover().split(",")[0]);
		}
		productSkuListVO.setPropertyData(productSkuProperDataVOList);
		return productSkuListVO;
	}
}
