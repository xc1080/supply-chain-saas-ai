package com.simlect.biz.impl;

import java.util.List;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.simlect.entity.enums.PageSize;
import com.simlect.entity.query.SysProductPropertyQuery;
import com.simlect.entity.po.SysProductProperty;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.query.SimplePage;
import com.simlect.mappers.SysProductPropertyMapper;
import com.simlect.biz.SysProductPropertyService;
import com.simlect.utils.StringTools;

@Service("sysProductPropertyService")
public class SysProductPropertyServiceImpl implements SysProductPropertyService {

	@Resource
	private SysProductPropertyMapper<SysProductProperty, SysProductPropertyQuery> sysProductPropertyMapper;

	@Override
	public List<SysProductProperty> findListByParam(SysProductPropertyQuery param) {
		return this.sysProductPropertyMapper.selectList(param);
	}

	@Override
	public Integer findCountByParam(SysProductPropertyQuery param) {
		return this.sysProductPropertyMapper.selectCount(param);
	}

	@Override
	public PaginationResultVO<SysProductProperty> findListByPage(SysProductPropertyQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<SysProductProperty> list = this.findListByParam(param);
		PaginationResultVO<SysProductProperty> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	@Override
	public Integer add(SysProductProperty bean) {
		return this.sysProductPropertyMapper.insert(bean);
	}

	@Override
	public Integer addBatch(List<SysProductProperty> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.sysProductPropertyMapper.insertBatch(listBean);
	}

	@Override
	public Integer addOrUpdateBatch(List<SysProductProperty> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.sysProductPropertyMapper.insertOrUpdateBatch(listBean);
	}

	@Override
	public Integer updateByParam(SysProductProperty bean, SysProductPropertyQuery param) {
		StringTools.checkParam(param);
		return this.sysProductPropertyMapper.updateByParam(bean, param);
	}

	@Override
	public Integer deleteByParam(SysProductPropertyQuery param) {
		StringTools.checkParam(param);
		return this.sysProductPropertyMapper.deleteByParam(param);
	}

	@Override
	public SysProductProperty getSysProductPropertyByPropertyId(String propertyId) {
		return this.sysProductPropertyMapper.selectByPropertyId(propertyId);
	}

	@Override
	public Integer updateSysProductPropertyByPropertyId(SysProductProperty bean, String propertyId) {
		return this.sysProductPropertyMapper.updateByPropertyId(bean, propertyId);
	}

	@Override
	public Integer deleteSysProductPropertyByPropertyId(String propertyId) {
		return this.sysProductPropertyMapper.deleteByPropertyId(propertyId);
	}

	@Override
	public Integer saveProductProperty(SysProductProperty bean) {
		return this.sysProductPropertyMapper.insertOrUpdate(bean);
	}
}
