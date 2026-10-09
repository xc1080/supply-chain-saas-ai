package com.simlect.biz.impl;

import com.simlect.api.support.CouponFeignSupport;
import com.simlect.api.vo.DiscountCouponVO;
import com.simlect.component.RedisComponent;
import com.simlect.entity.config.AppConfig;
import com.simlect.entity.dto.MemberLevelRewardConfigDTO;
import com.simlect.exception.BusinessException;
import com.simlect.biz.MemberLevelRewardConfigService;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service("memberLevelRewardConfigService")
public class MemberLevelRewardConfigServiceImpl implements MemberLevelRewardConfigService {

    @Resource
    private RedisComponent redisComponent;
    @Resource
    private CouponFeignSupport couponFeignSupport;
    @Resource
    private AppConfig appConfig;

    @Override
    public MemberLevelRewardConfigDTO getConfig() {
        MemberLevelRewardConfigDTO dto = redisComponent.getMemberLevelRewardConfig();
        if (dto == null) {
            dto = defaultFromYml();
        }
        enrichCouponNames(dto);
        return dto;
    }

    @Override
    public void saveConfig(MemberLevelRewardConfigDTO config) {
        if (config == null) {
            throw new BusinessException("配置不能为空");
        }
        MemberLevelRewardConfigDTO toSave = new MemberLevelRewardConfigDTO();
        toSave.setLevel2CouponId(resolveCouponIdForSave(config.getLevel2CouponId(), "银卡"));
        toSave.setLevel3CouponId(resolveCouponIdForSave(config.getLevel3CouponId(), "金卡"));
        if (!StringTools.isEmpty(toSave.getLevel2CouponId())) {
            DiscountCouponVO c = couponFeignSupport.getCoupon(toSave.getLevel2CouponId());
            if (c != null) {
                toSave.setLevel2CouponName(c.getCouponName());
            }
        }
        if (!StringTools.isEmpty(toSave.getLevel3CouponId())) {
            DiscountCouponVO c = couponFeignSupport.getCoupon(toSave.getLevel3CouponId());
            if (c != null) {
                toSave.setLevel3CouponName(c.getCouponName());
            }
        }
        redisComponent.saveMemberLevelRewardConfig(toSave);
    }

    @Override
    public String resolveLevelCouponId(int levelCode) {
        MemberLevelRewardConfigDTO dto = redisComponent.getMemberLevelRewardConfig();
        if (dto != null) {
            if (levelCode == 2 && !StringTools.isEmpty(dto.getLevel2CouponId())) {
                return dto.getLevel2CouponId().trim();
            }
            if (levelCode == 3 && !StringTools.isEmpty(dto.getLevel3CouponId())) {
                return dto.getLevel3CouponId().trim();
            }
        }
        if (levelCode == 2) {
            return trimOrNull(appConfig.getMemberLevel2CouponId());
        }
        if (levelCode == 3) {
            return trimOrNull(appConfig.getMemberLevel3CouponId());
        }
        return null;
    }

    private String resolveCouponIdForSave(String raw, String levelLabel) {
        if (StringTools.isEmpty(raw)) {
            return null;
        }
        String couponId = raw.trim();
        DiscountCouponVO coupon = couponFeignSupport.getCoupon(couponId);
        if (coupon == null) {
            throw new BusinessException(levelLabel + "升级礼券不存在");
        }
        return couponId;
    }

    private MemberLevelRewardConfigDTO defaultFromYml() {
        MemberLevelRewardConfigDTO dto = new MemberLevelRewardConfigDTO();
        dto.setLevel2CouponId(trimOrNull(appConfig.getMemberLevel2CouponId()));
        dto.setLevel3CouponId(trimOrNull(appConfig.getMemberLevel3CouponId()));
        return dto;
    }

    private void enrichCouponNames(MemberLevelRewardConfigDTO dto) {
        try {
            if (!StringTools.isEmpty(dto.getLevel2CouponId())) {
                DiscountCouponVO c = couponFeignSupport.getCoupon(dto.getLevel2CouponId());
                if (c != null) {
                    dto.setLevel2CouponName(c.getCouponName());
                }
            }
            if (!StringTools.isEmpty(dto.getLevel3CouponId())) {
                DiscountCouponVO c = couponFeignSupport.getCoupon(dto.getLevel3CouponId());
                if (c != null) {
                    dto.setLevel3CouponName(c.getCouponName());
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    private String trimOrNull(String s) {
        if (StringTools.isEmpty(s)) {
            return null;
        }
        return s.trim();
    }
}
