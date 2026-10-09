package com.simlect.biz;

import java.util.List;

import com.simlect.entity.query.UserCouponQuery;
import com.simlect.entity.po.UserCoupon;
import com.simlect.entity.vo.PaginationResultVO;

public interface UserCouponService {

	List<UserCoupon> findListByParam(UserCouponQuery param);

	Integer findCountByParam(UserCouponQuery param);

	PaginationResultVO<UserCoupon> findListByPage(UserCouponQuery param);

	Integer add(UserCoupon bean);

	Integer addBatch(List<UserCoupon> listBean);

	Integer addOrUpdateBatch(List<UserCoupon> listBean);

	Integer updateByParam(UserCoupon bean,UserCouponQuery param);

	Integer deleteByParam(UserCouponQuery param);

	UserCoupon getUserCouponByUserCouponId(String userCouponId);

	Integer updateUserCouponByUserCouponId(UserCoupon bean,String userCouponId);

	Integer deleteUserCouponByUserCouponId(String userCouponId);

}
