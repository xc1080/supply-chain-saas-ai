package com.simlect.biz;

import java.util.List;

import com.simlect.entity.po.SysProductProperty;
import com.simlect.entity.query.SysCategoryQuery;
import com.simlect.entity.po.SysCategory;
import com.simlect.entity.vo.PaginationResultVO;

public interface SysCategoryService {

	List<SysCategory> findListByParam(SysCategoryQuery param);

	Integer findCountByParam(SysCategoryQuery param);

	PaginationResultVO<SysCategory> findListByPage(SysCategoryQuery param);

	Integer add(SysCategory bean);

	Integer addBatch(List<SysCategory> listBean);

	Integer addOrUpdateBatch(List<SysCategory> listBean);

	Integer updateByParam(SysCategory bean,SysCategoryQuery param);

	Integer deleteByParam(SysCategoryQuery param);

	SysCategory getSysCategoryByCategoryId(String categoryId);

	Integer updateSysCategoryByCategoryId(SysCategory bean,String categoryId);

	Integer deleteSysCategoryByCategoryId(String categoryId);

	void saveCategory(SysCategory bean);

	void deleteSysCategory(SysCategory bean);

	void changeCategorySort(String categoryIds);

	Integer saveProductProperty(SysProductProperty bean);

	Integer delProductProperty(SysProductProperty bean);

	// 将信息从redis中取出
	List<SysCategory> getAllCategoryList();
}
