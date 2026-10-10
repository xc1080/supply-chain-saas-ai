package com.ruoyi.web.controller.commerce;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.CommerceCapability;
import com.ruoyi.system.service.CommerceOrderAmountService;
import com.ruoyi.system.service.CommercePermission;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile({"local","commerce"})
@RequestMapping("/commerce/pricing")
@PreAuthorize("isAuthenticated()")
public class CommercePricingController {
    private final CommerceOrderAmountService service;
    public CommercePricingController(CommerceOrderAmountService service){this.service=service;}
    @GetMapping("/policy")
    @CommercePermission(customer=true)
    public AjaxResult policy(){return AjaxResult.success(service.policy());}
    @PutMapping("/policy")
    @CommercePermission(CommerceCapability.CATALOG)
    public AjaxResult savePolicy(@RequestBody Map<String,Object> body){return AjaxResult.success(service.savePolicy(body,SecurityUtils.getUserId()));}
    @GetMapping("/promotions")
    @CommercePermission(customer=true)
    public AjaxResult promotions(){return AjaxResult.success(service.promotions());}
    @GetMapping("/promotions/manage")
    @CommercePermission(CommerceCapability.READ)
    public AjaxResult managePromotions(){return AjaxResult.success(service.managePromotions(SecurityUtils.getUserId()));}
    @PostMapping("/promotions")
    @CommercePermission(CommerceCapability.CATALOG)
    public AjaxResult createPromotion(@RequestBody Map<String,Object> body){return AjaxResult.success(service.savePromotion(null,body,SecurityUtils.getUserId()));}
    @PutMapping("/promotions/{promotionId}")
    @CommercePermission(CommerceCapability.CATALOG)
    public AjaxResult savePromotion(@PathVariable String promotionId,@RequestBody Map<String,Object> body){return AjaxResult.success(service.savePromotion(promotionId,body,SecurityUtils.getUserId()));}
    @PostMapping("/quote")
    @CommercePermission(customer=true)
    public AjaxResult quote(@RequestBody Map<String,Object> body){return AjaxResult.success(service.quote(body));}
}
