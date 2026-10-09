package com.simlect.biz.impl;

import java.util.List;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.simlect.entity.enums.PageSize;
import com.simlect.entity.query.ProductPropertyValueQuery;
import com.simlect.entity.po.ProductPropertyValue;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.query.SimplePage;
import com.simlect.mappers.ProductPropertyValueMapper;
import com.simlect.biz.ProductPropertyValueService;
import com.simlect.utils.StringTools;

@Service("productPropertyValueService")
public class ProductPropertyValueServiceImpl implements ProductPropertyValueService {

	@Resource
	private ProductPropertyValueMapper<ProductPropertyValue, ProductPropertyValueQuery> productPropertyValueMapper;

	@Override
	public List<ProductPropertyValue> findListByParam(ProductPropertyValueQuery param) {
		return this.productPropertyValueMapper.selectList(param);
	}

	@Override
	public Integer findCountByParam(ProductPropertyValueQuery param) {
		return this.productPropertyValueMapper.selectCount(param);
	}

	@Override
	public PaginationResultVO<ProductPropertyValue> findListByPage(ProductPropertyValueQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<ProductPropertyValue> list = this.findListByParam(param);
		PaginationResultVO<ProductPropertyValue> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	@Override
	public Integer add(ProductPropertyValue bean) {
		return this.productPropertyValueMapper.insert(bean);
	}

	@Override
	public Integer addBatch(List<ProductPropertyValue> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productPropertyValueMapper.insertBatch(listBean);
	}

	@Override
	public Integer addOrUpdateBatch(List<ProductPropertyValue> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productPropertyValueMapper.insertOrUpdateBatch(listBean);
	}

	@Override
	public Integer updateByParam(ProductPropertyValue bean, ProductPropertyValueQuery param) {
		StringTools.checkParam(param);
		return this.productPropertyValueMapper.updateByParam(bean, param);
	}

	@Override
	public Integer deleteByParam(ProductPropertyValueQuery param) {
		StringTools.checkParam(param);
		return this.productPropertyValueMapper.deleteByParam(param);
	}

	@Override
	public ProductPropertyValue getProductPropertyValueByProductIdAndPropertyValueId(String productId, String propertyValueId) {
		return this.productPropertyValueMapper.selectByProductIdAndPropertyValueId(productId, propertyValueId);
	}

	@Override
	public Integer updateProductPropertyValueByProductIdAndPropertyValueId(ProductPropertyValue bean, String productId, String propertyValueId) {
		return this.productPropertyValueMapper.updateByProductIdAndPropertyValueId(bean, productId, propertyValueId);
	}

	@Override
	public Integer deleteProductPropertyValueByProductIdAndPropertyValueId(String productId, String propertyValueId) {
		return this.productPropertyValueMapper.deleteByProductIdAndPropertyValueId(productId, propertyValueId);
	}
}
