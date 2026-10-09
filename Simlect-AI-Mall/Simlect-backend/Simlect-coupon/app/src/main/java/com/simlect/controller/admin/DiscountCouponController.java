package com.simlect.controller.admin;

import com.simlect.component.CouponRushStockService;
import com.simlect.api.dto.CouponRushStockReconcileDTO;
import com.simlect.annotation.AdminSensitiveConfirm;
import com.simlect.api.dto.DiscountCouponDTO;
import com.simlect.entity.po.DiscountCoupon;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.DiscountCouponService;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequestMapping("/admin/discountCoupon")
@RestController("adminDiscountCouponController")
public class DiscountCouponController extends com.simlect.controller.admin.ABaseController{

    @Resource
    private DiscountCouponService discountCouponService;
    @Resource
    private CouponRushStockService couponRushStockService;

    // 新增/修改优惠券
    @PostMapping("/saveDiscountCoupon")
    public ResponseVO saveDiscountCoupon(DiscountCouponDTO discountCoupon){
        discountCouponService.saveDiscountCoupon(discountCoupon);
        return getSuccessResponseVO(null);
    }

    // 获取所有优惠券
    @PostMapping("/loadDiscountCoupon")
    public ResponseVO loadDiscountCoupon(Integer pageNo, Integer pageSize, String couponNameFuzzy, Integer couponType, Integer status){
        PaginationResultVO resultVO = discountCouponService.loadDiscountCoupon4Admin(pageNo, pageSize, couponNameFuzzy, couponType, status);
        return getSuccessResponseVO(resultVO);
    }

    // 获取优惠券详情信息
    @PostMapping("/getDiscountCouponInfo")
    public ResponseVO getDiscountCouponInfo(String couponId){
        DiscountCoupon discountCoupon = discountCouponService.getDiscountCouponByCouponId(couponId);
        return getSuccessResponseVO(discountCoupon);
    }

    // 更新优惠券状态
    @PostMapping("/updateDiscountCouponStatus")
    @AdminSensitiveConfirm
    public ResponseVO updateDiscountCouponStatus(String couponId, Integer status){
        DiscountCoupon discountCoupon = discountCouponService.getDiscountCouponByCouponId(couponId);
        discountCoupon.setStatus(status);
        discountCouponService.updateDiscountCouponByCouponId(discountCoupon, couponId);
        return getSuccessResponseVO(null);
    }

    @PostMapping("/warmupRushStock")
    public ResponseVO warmupRushStock(String couponId) {
        if (StringTools.isEmpty(couponId)) {
            int count = couponRushStockService.warmupAllRushingFromDb();
            return getSuccessResponseVO(count);
        }
        DiscountCoupon coupon = discountCouponService.getDiscountCouponByCouponId(couponId);
        if (coupon == null) {
            return getSuccessResponseVO(null);
        }
        couponRushStockService.warmupStock(couponId, coupon.getRemainCount());
        return getSuccessResponseVO(coupon.getRemainCount());
    }

    @PostMapping("/reconcileRushStock")
    public ResponseVO reconcileRushStock(String couponId) {
        if (StringTools.isEmpty(couponId)) {
            List<CouponRushStockReconcileDTO> list = couponRushStockService.reconcileAllRushing();
            return getSuccessResponseVO(list);
        }
        return getSuccessResponseVO(couponRushStockService.reconcileOne(couponId));
    }
}
