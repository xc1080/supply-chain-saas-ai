package com.simlect.controller;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.simlect.annotation.CouponRushRateLimit;
import com.simlect.annotation.GlobalInterceptor;
import com.simlect.api.dto.PayInfoDTO;
import com.simlect.entity.dto.TokenUserInfoDTO;
import com.simlect.entity.po.DiscountCoupon;
import com.simlect.entity.po.UserCoupon;
import com.simlect.entity.query.UserCouponQuery;
import com.simlect.api.enums.UserCouponStatusEnum;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.api.vo.UserCouponDetailVO;
import com.simlect.api.support.OrderFeignSupport;
import com.simlect.biz.DiscountCouponService;
import com.simlect.biz.UserCouponService;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("discountCouponController")
@RequestMapping("/discountCoupon")
public class DiscountCouponController extends ABaseController{

    @Resource
    private DiscountCouponService discountCouponService;
    @Resource
    private UserCouponService userCouponService;
    @Resource
    private OrderFeignSupport orderFeignSupport;

    @PostMapping("/getDiscountCouponDetail")
    public ResponseVO getDiscountCouponDetail(@NotEmpty String couponId) {
        DiscountCoupon coupon = discountCouponService.getDiscountCouponByCouponId(couponId);
        return getSuccessResponseVO(coupon);
    }

    // 加载所有优惠卷
    @PostMapping("/loadDiscountCoupon")
    public ResponseVO loadDiscountCoupon(Integer pageNo, Integer pageSize, @NotEmpty String status, String keyword){
        PaginationResultVO<DiscountCoupon> resultVO = discountCouponService.loadDiscountCoupon(pageNo, pageSize, status, keyword);
        TokenUserInfoDTO tokenUser = getTokenUserInfo();
        if (tokenUser != null && resultVO.getList() != null && !resultVO.getList().isEmpty()) {
            discountCouponService.fillHasBoughtForPlaza(tokenUser.getUserId(), resultVO.getList());
        }
        return getSuccessResponseVO(resultVO);
    }

    // 抢购：预占 + 扣库存 + 创建待支付订单，前端跳转确认订单页倒计时支付
    @PostMapping("/rushCoupon")
    @GlobalInterceptor(checkLogin = true)
    @CouponRushRateLimit
    public ResponseVO rushCoupon(@NotEmpty String couponId) {
        String userId = getTokenUserInfo().getUserId();
        return getSuccessResponseVO(discountCouponService.rushCoupon(userId, couponId));
    }

    // 确认订单页：为已创建的秒杀订单发起支付
    @PostMapping("/buyDiscountCoupon")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO buyDiscountCoupon(@NotEmpty String couponId, @NotEmpty String payMethod) {
        String userId = getTokenUserInfo().getUserId();
        PayInfoDTO payInfoDTO = discountCouponService.buyDiscountCoupon(userId, couponId, payMethod);
        return getSuccessResponseVO(payInfoDTO);
    }

    // 我的优惠券
    @PostMapping("/loadUserCoupon")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO loadUserCoupon(@NotNull Integer pageNo, Integer status) {
        String userId = getTokenUserInfo().getUserId();
        orderFeignSupport.syncPaidCouponRushUserCoupons(userId);
        UserCouponQuery query = new UserCouponQuery();
        query.setUserId(userId);
        query.setPageNo(pageNo);
        query.setOrderBy("use_time desc, user_coupon_id desc");
        // 我的优惠券页默认不展示"已作废"
        query.setExecuteStatusList(new Integer[]{UserCouponStatusEnum.CANT.getStatus()});
        // 查"未使用"时不过滤DB状态，因为过期券在DB里仍是0，需查出来后再用getDynamicStatus过滤
        boolean needsPostFilter = status != null && (
                status.equals(UserCouponStatusEnum.NOUSE.getStatus()) ||
                status.equals(UserCouponStatusEnum.OVERDUE.getStatus()));
        if (status != null && !needsPostFilter) {
            query.setStatus(status);
        }
        if (needsPostFilter) {
            query.setExecuteStatusList(new Integer[]{
                    UserCouponStatusEnum.USED.getStatus(),
                    UserCouponStatusEnum.CANT.getStatus()
            });
        }
        PaginationResultVO<UserCoupon> page = userCouponService.findListByPage(query);
        List<UserCouponDetailVO> list = new ArrayList<>();
        if (page.getList() != null) {
            for (UserCoupon uc : page.getList()) {
                DiscountCoupon dc = discountCouponService.getDiscountCouponByCouponId(uc.getCouponId());
                if (dc == null) {
                    continue;
                }
                UserCouponDetailVO vo = new UserCouponDetailVO();
                vo.setUserCouponId(uc.getUserCouponId());
                vo.setCouponId(dc.getCouponId());
                vo.setCouponName(dc.getCouponName());
                vo.setCouponType(dc.getCouponType());
                vo.setThresholdAmount(dc.getThresholdAmount());
                vo.setDiscountAmount(dc.getDiscountAmount());
                vo.setDiscountRate(dc.getDiscountRate());
                vo.setValidStartTime(dc.getValidStartTime());
                vo.setValidEndTime(dc.getValidEndTime());
                vo.setStatus(getDynamicStatus(uc.getStatus(), dc.getValidEndTime()));
                list.add(vo);
            }
        }
        // getDynamicStatus可能把过期券改成已过期，查"未使用"时需过滤掉
        if (needsPostFilter && status != null) {
            list.removeIf(vo -> !vo.getStatus().equals(status));
        }
        PaginationResultVO<UserCouponDetailVO> result = new PaginationResultVO<>(
                page.getTotalCount(), page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
        return getSuccessResponseVO(result);
    }

    private Integer getDynamicStatus(Integer dbStatus, Date validEndTime) {
        if (dbStatus != null && dbStatus == UserCouponStatusEnum.NOUSE.getStatus()
                && validEndTime != null && validEndTime.before(new Date())) {
            return UserCouponStatusEnum.OVERDUE.getStatus();
        }
        return dbStatus;
    }
}
