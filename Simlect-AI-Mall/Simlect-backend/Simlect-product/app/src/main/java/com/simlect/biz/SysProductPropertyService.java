package com.simlect.biz;

import java.util.List;

import com.simlect.entity.query.SysProductPropertyQuery;
import com.simlect.entity.po.SysProductProperty;
import com.simlect.entity.vo.PaginationResultVO;

public interface SysProductPropertyService {

	List<SysProductProperty> findListByParam(SysProductPropertyQuery param);

	Integer findCountByParam(SysProductPropertyQuery param);

	PaginationResultVO<SysProductProperty> findListByPage(SysProductPropertyQuery param);

	Integer add(SysProductProperty bean);

	Integer addBatch(List<SysProductProperty> listBean);

	Integer addOrUpdateBatch(List<SysProductProperty> listBean);

	Integer updateByParam(SysProductProperty bean,SysProductPropertyQuery param);

	Integer deleteByParam(SysProductPropertyQuery param);

	SysProductProperty getSysProductPropertyByPropertyId(String propertyId);

	Integer updateSysProductPropertyByPropertyId(SysProductProperty bean,String propertyId);

	Integer deleteSysProductPropertyByPropertyId(String propertyId);

	Integer saveProductProperty(SysProductProperty bean);
}
