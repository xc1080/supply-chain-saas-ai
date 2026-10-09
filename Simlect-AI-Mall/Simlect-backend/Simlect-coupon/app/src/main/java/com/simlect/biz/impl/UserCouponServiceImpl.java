package com.simlect.biz.impl;

import java.util.List;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import com.simlect.entity.enums.PageSize;
import com.simlect.entity.query.UserCouponQuery;
import com.simlect.entity.po.UserCoupon;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.query.SimplePage;
import com.simlect.mappers.UserCouponMapper;
import com.simlect.biz.UserCouponService;
import com.simlect.utils.StringTools;

@Service("userCouponService")
public class UserCouponServiceImpl implements UserCouponService {

	@Resource
	private UserCouponMapper<UserCoupon, UserCouponQuery> userCouponMapper;

	@Override
	public List<UserCoupon> findListByParam(UserCouponQuery param) {
		return this.userCouponMapper.selectList(param);
	}

	@Override
	public Integer findCountByParam(UserCouponQuery param) {
		return this.userCouponMapper.selectCount(param);
	}

	@Override
	public PaginationResultVO<UserCoupon> findListByPage(UserCouponQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<UserCoupon> list = this.findListByParam(param);
		PaginationResultVO<UserCoupon> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	@Override
	public Integer add(UserCoupon bean) {
		return this.userCouponMapper.insert(bean);
	}

	@Override
	public Integer addBatch(List<UserCoupon> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.userCouponMapper.insertBatch(listBean);
	}

	@Override
	public Integer addOrUpdateBatch(List<UserCoupon> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.userCouponMapper.insertOrUpdateBatch(listBean);
	}

	@Override
	public Integer updateByParam(UserCoupon bean, UserCouponQuery param) {
		StringTools.checkParam(param);
		return this.userCouponMapper.updateByParam(bean, param);
	}

	@Override
	public Integer deleteByParam(UserCouponQuery param) {
		StringTools.checkParam(param);
		return this.userCouponMapper.deleteByParam(param);
	}

	@Override
	public UserCoupon getUserCouponByUserCouponId(String userCouponId) {
		return this.userCouponMapper.selectByUserCouponId(userCouponId);
	}

	@Override
	public Integer updateUserCouponByUserCouponId(UserCoupon bean, String userCouponId) {
		return this.userCouponMapper.updateByUserCouponId(bean, userCouponId);
	}

	@Override
	public Integer deleteUserCouponByUserCouponId(String userCouponId) {
		return this.userCouponMapper.deleteByUserCouponId(userCouponId);
	}
}
