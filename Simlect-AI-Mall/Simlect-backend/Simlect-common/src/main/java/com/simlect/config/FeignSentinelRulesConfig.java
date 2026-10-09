package com.simlect.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
@ConditionalOnClass(DegradeRuleManager.class)
@ConditionalOnProperty(name = "simlect.sentinel.feign-rules-enabled", havingValue = "true", matchIfMissing = true)
public class FeignSentinelRulesConfig {

    @PostConstruct
    public void init() {
        List<DegradeRule> rules = new ArrayList<>();
        for (String resource : new String[]{
                "StockFeignClient#getStock(SkuStockQueryDTO)",
                "StockFeignClient#changeStock(SkuStockChangeDTO)",
                "StockFeignClient#changeStockBatch(SkuStockBatchChangeDTO)",
                "StockFeignClient#lockAndVerify(SkuStockBatchChangeDTO)",
                "StockFeignClient#setStock(SkuStockSetDTO)",
                "StockFeignClient#totalByProduct(ProductIdDTO)",
                "CouponFeignClient#validateAndLock(CouponValidateAndLockDTO)",
                "CouponFeignClient#getCoupon(CouponIdDTO)",
                "CouponFeignClient#getCouponBrief(CouponIdDTO)",
                "CouponFeignClient#getUserCoupon(UserCouponIdDTO)",
                "CouponFeignClient#changeUserCouponStatus(UserCouponStatusChangeDTO)",
                "CouponFeignClient#createUserCoupon(UserCouponCreateDTO)",
                "CouponFeignClient#deductStock(CouponIdDTO)",
                "ProductFeignClient#snapshotBatch(ProductIdListDTO)",
                "ProductFeignClient#defaultSku(ProductIdDTO)",
                "ProductFeignClient#increaseSales(ProductSalesIncreaseDTO)",
                "UserFeignClient#getAddress(UserAddressQueryDTO)",
                "UserFeignClient#addGrowthOnPay(UserGrowthAddDTO)"
        }) {
            DegradeRule rule = new DegradeRule(resource)
                    .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                    .setCount(0.5)
                    .setTimeWindow(10)
                    .setMinRequestAmount(10)
                    .setStatIntervalMs(10000);
            rules.add(rule);
        }
        DegradeRuleManager.loadRules(rules);
        log.info("已加载 Feign Sentinel 熔断规则 {} 条", rules.size());
    }
}
