package com.simlect.mappers;

import com.simlect.entity.po.SysCategory;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysCategoryMapper<T,P> extends BaseMapper<T,P> {

	 Integer updateByCategoryId(@Param("bean") T t,@Param("categoryId") String categoryId);

	 Integer deleteByCategoryId(@Param("categoryId") String categoryId);

	 T selectByCategoryId(@Param("categoryId") String categoryId);

	List<T> selectByPCategoryId(@Param("pCategoryId") String pCategoryId);

	Integer selectMaxSort(@Param("pCategoryId")String pId);

	void updateBatch(@Param("categoryList")List<SysCategory> categoryList);

	String selectNameByCategoryId(@Param("categoryId") String categoryId);

}
