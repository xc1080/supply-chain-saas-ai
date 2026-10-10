package com.ruoyi.web.controller.commerce;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile({"local","commerce"})
@RequestMapping("/commerce/settlements")
@PreAuthorize("isAuthenticated()")
public class CommerceSettlementController {
    private final CommerceSettlementService settlements;
    public CommerceSettlementController(CommerceSettlementService settlements){this.settlements=settlements;}
    @GetMapping("/orders/{id}") @CommercePermission
    public AjaxResult preview(@PathVariable String id){return AjaxResult.success(settlements.preview(id,SecurityUtils.getUserId()));}
    @GetMapping("/orders/{id}/stock-explanation") @CommercePermission
    public AjaxResult stock(@PathVariable String id){return AjaxResult.success(settlements.stockExplanation(id,SecurityUtils.getUserId()));}
    @PostMapping("/orders/{id}/expenses") @CommercePermission(CommerceCapability.REFUND_REVIEW)
    public AjaxResult expense(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(settlements.expense(id,body,SecurityUtils.getUserId()));}
    @PostMapping("/expenses/{id}/review") @CommercePermission(CommerceCapability.REFUND_REVIEW)
    public AjaxResult review(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(settlements.review(id,body,SecurityUtils.getUserId()));}
    @PostMapping("/orders/{id}/payments") @CommercePermission(CommerceCapability.REFUND_EXECUTE)
    public AjaxResult pay(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(settlements.pay(id,body,SecurityUtils.getUserId()));}
    @PostMapping("/orders/{id}/snapshots") @CommercePermission(CommerceCapability.REFUND_REVIEW)
    public AjaxResult snapshot(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(settlements.snapshot(id,body,SecurityUtils.getUserId()));}
}
