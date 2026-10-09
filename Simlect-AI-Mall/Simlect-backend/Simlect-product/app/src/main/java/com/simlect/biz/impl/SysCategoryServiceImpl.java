package com.simlect.biz.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.simlect.component.RedisComponent;
import com.simlect.constants.Constants;
import com.simlect.entity.po.SysProductProperty;
import com.simlect.entity.query.SysProductPropertyQuery;
import com.simlect.mappers.SysProductPropertyMapper;
import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.simlect.entity.enums.PageSize;
import com.simlect.entity.query.SysCategoryQuery;
import com.simlect.entity.po.SysCategory;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.query.SimplePage;
import com.simlect.mappers.SysCategoryMapper;
import com.simlect.biz.SysCategoryService;
import com.simlect.utils.StringTools;

@Service("sysCategoryService")
@Slf4j
public class SysCategoryServiceImpl implements SysCategoryService {

	@Resource
	private SysCategoryMapper<SysCategory, SysCategoryQuery> sysCategoryMapper;

	@Resource
	private SysProductPropertyMapper<SysProductProperty, SysProductProperty> sysProductPropertyMapper;
    @Autowired
    private SysProductPropertyServiceImpl sysProductPropertyService;

	@Resource
	private RedisComponent redisComponent;

	@Override
	public List<SysCategory> findListByParam(SysCategoryQuery param) {
		List<SysCategory> list = this.sysCategoryMapper.selectList(param);
		if (param.getParent()!=null && param.getParent()){
			list = findChildren(list, Constants.ZERO_STR);
		}
		if (param.getPropertyQuery()!=null && param.getPropertyQuery()){
			list = find4Property(list);
		}
		// Redis 有非空列表才走缓存；空列表多为库未导入时写入，需回源重建
		List<?> cached = redisComponent.getCategoryList();
		if (cached != null && !cached.isEmpty()) {
			@SuppressWarnings("unchecked")
			List<SysCategory> cachedList = (List<SysCategory>) cached;
			list = findChildren(cachedList, Constants.ZERO_STR);
			list = find4Property(list);
			return list;
		}
		saveCategoryToRedis();
		cached = redisComponent.getCategoryList();
		if (cached != null && !cached.isEmpty()) {
			@SuppressWarnings("unchecked")
			List<SysCategory> cachedList = (List<SysCategory>) cached;
			list = findChildren(cachedList, Constants.ZERO_STR);
			list = find4Property(list);
			return list;
		}
		list = findChildren(list, Constants.ZERO_STR);
		list = find4Property(list);
		return list;
	}

	public List<SysCategory> findChildren(List<SysCategory> dataList,String pCategoryId){
		List<SysCategory> childrenList = new ArrayList<>();
		for (SysCategory data : dataList) {
			if (data.getCategoryId()!=null && data.getpCategoryId()!=null && pCategoryId.equals(data.getpCategoryId())) {
				data.setChildren(findChildren(dataList, data.getCategoryId()));
				childrenList.add(data);
			}
		}
		return childrenList;
	}

	public List<SysCategory> find4Property(List<SysCategory> dataList){
		if (dataList == null || dataList.isEmpty()) {
			return new ArrayList<>();
		}
		SysProductPropertyQuery query = new SysProductPropertyQuery();
		List<SysProductProperty> productPropertyList = sysProductPropertyService.findListByParam(query);
		// 将属性按 categoryId 分组 (Map<categoryId, List<property>>)
		// 并在分组时按 propertySort 升序排序
		Map<String, List<SysProductProperty>> propertyMap = productPropertyList.stream()
				.filter(p -> p.getCategoryId() != null)
				.collect(Collectors.groupingBy(
						SysProductProperty::getCategoryId,
						Collectors.collectingAndThen(
								Collectors.toList(),
								list -> list.stream()
										.sorted(Comparator.comparing(SysProductProperty::getPropertySort, Comparator.nullsLast(Integer::compareTo)))
										.collect(Collectors.toList())
						)
				));

		// 遍历分类列表，将属性挂载到对应的分类节点上
		for (SysCategory category : dataList) {
			if (category.getCategoryId() != null) {
				// 从 Map 中取出当前分类对应的属性列表
				List<SysProductProperty> props = propertyMap.get(category.getCategoryId());
				category.setproductPropertyList(props != null ? props : new ArrayList<>());

				// 如果分类还有子节点，递归处理子节点
				if (category.getChildren() != null && !category.getChildren().isEmpty()) {
					find4Property(category.getChildren());
				}
			}
		}
		return dataList;
	}

	@Override
	public Integer findCountByParam(SysCategoryQuery param) {

		return this.sysCategoryMapper.selectCount(param);
	}

	@Override
	public PaginationResultVO<SysCategory> findListByPage(SysCategoryQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<SysCategory> list = this.findListByParam(param);
		PaginationResultVO<SysCategory> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	@Override
	public Integer add(SysCategory bean) {
		return this.sysCategoryMapper.insert(bean);
	}

	@Override
	public Integer addBatch(List<SysCategory> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.sysCategoryMapper.insertBatch(listBean);
	}

	@Override
	public Integer addOrUpdateBatch(List<SysCategory> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.sysCategoryMapper.insertOrUpdateBatch(listBean);
	}

	@Override
	public Integer updateByParam(SysCategory bean, SysCategoryQuery param) {
		StringTools.checkParam(param);
		return this.sysCategoryMapper.updateByParam(bean, param);
	}

	@Override
	public Integer deleteByParam(SysCategoryQuery param) {
		StringTools.checkParam(param);
		return this.sysCategoryMapper.deleteByParam(param);
	}

	@Override
	public SysCategory getSysCategoryByCategoryId(String categoryId) {
		return this.sysCategoryMapper.selectByCategoryId(categoryId);
	}

	@Override
	public Integer updateSysCategoryByCategoryId(SysCategory bean, String categoryId) {
		return this.sysCategoryMapper.updateByCategoryId(bean, categoryId);
	}

	@Override
	public Integer deleteSysCategoryByCategoryId(String categoryId) {
		return this.sysCategoryMapper.deleteByCategoryId(categoryId);
	}

	@Override
	public void saveCategory(SysCategory bean) {
		// 如果是修改，直接修改分类名
		// 如果是添加， 则pCategoryId为父类id， categoryId为随机生成的五位纯数字，并且sort的值为当前同层级下的最大值+1
		if (bean.getCategoryId() == null) {
			// 新增
			String newId = StringTools.getRandomNumber(Constants.LENGTH_5);
			String pId = bean.getpCategoryId();
			// 获取当前分类下最大的sort的值
			// slect max(sort) from sys_category where pCategoryId = #{bean.CategoryId}
			Integer maxSort = this.sysCategoryMapper.selectMaxSort(pId);
			if (maxSort == null) {
				maxSort = 1; // 如果该层级没有其他分类，默认从1开始
			} else {
				maxSort = maxSort + 1;
			}
			bean.setCategoryId(newId);
			bean.setSort(maxSort);
			bean.setpCategoryId(pId);
			this.sysCategoryMapper.insert(bean);
		}
		this.sysCategoryMapper.updateByCategoryId(bean, bean.getCategoryId());
		// 存入redis
		saveCategoryToRedis();
	}

	@Override
	public void deleteSysCategory(SysCategory bean) {
		// 先查询当前节点的所有直接子节点
		List<SysCategory> children = this.sysCategoryMapper.selectByPCategoryId(bean.getCategoryId());
		// 如果当前节点下还有子节点，则连同子节点一起删除
		if (children != null || !children.isEmpty()){
			for (SysCategory child : children) {
				this.deleteSysCategory(child);
			}
		}
		// 如果当前节点下没有子节点，则直接删除
		this.sysCategoryMapper.deleteByCategoryId(bean.getCategoryId());
		// 存入redis
		saveCategoryToRedis();
	}

	@Override
	public void changeCategorySort(String categoryIds) {
		// categoryIds是以逗号分割的id顺序的新序列
		// 获取新序列
		String[] ids = categoryIds.split(",");
		// 按照id顺序改变旧序列的排序
		// sort从1开始累加
		int sort = 1;
		List<SysCategory> list = new ArrayList<>();
		for (String id : ids){
			SysCategory bean = new SysCategory();
			bean = this.sysCategoryMapper.selectByCategoryId(id);
			bean.setSort(sort++);
			list.add(bean);
		}
		this.sysCategoryMapper.updateBatch(list);
		// 存入redis
		saveCategoryToRedis();
	}

	@Override
	public Integer saveProductProperty(SysProductProperty bean) {
		// 如果属性id为空，则生成长度为10的随机id，并获取当前层级下最大的property_sort值
		if (bean.getPropertyId() == null || bean.getPropertyId().isEmpty()) {
			bean.setPropertyId(StringTools.getRandomNumber(Constants.LENGTH_10));
			Integer maxSort = sysProductPropertyMapper.selectMaxPropertySort(bean.getCategoryId());
			if (maxSort == null) {
				maxSort = 1;
			} else {
				maxSort = maxSort + 1;
			}
			bean.setPropertySort(maxSort);
		}
		return sysProductPropertyMapper.insertOrUpdate(bean);
	}

	@Override
	public Integer delProductProperty(SysProductProperty bean) {
		return sysProductPropertyMapper.deleteByPropertyId(bean.getPropertyId());
	}

	// 将信息存入redis
	private void saveCategoryToRedis(){
		// 创建查询query，按sort升序排列
		SysCategoryQuery query = new SysCategoryQuery();
		query.setOrderBy("sort asc");
		// 查询列表
		List<SysCategory> list = this.sysCategoryMapper.selectList(query);
		redisComponent.saveCategory2Redis(list);
	}

	// 将信息从redis中取出
	@Override
	public List<SysCategory> getAllCategoryList() {
		// 从redis中取出
		List<?> cached = redisComponent.getCategoryList();
		if (cached == null || cached.isEmpty()) {
			saveCategoryToRedis();
			cached = redisComponent.getCategoryList();
		}
		if (cached == null) {
			return new ArrayList<>();
		}
		@SuppressWarnings("unchecked")
		List<SysCategory> list = (List<SysCategory>) cached;
		return list;
	}
}
