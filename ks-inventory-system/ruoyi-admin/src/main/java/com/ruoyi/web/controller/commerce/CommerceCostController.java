package com.ruoyi.web.controller.commerce;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.CommerceCapability;
import com.ruoyi.system.service.CommerceCostService;
import com.ruoyi.system.service.CommercePermission;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile({"local","commerce"})
@RequestMapping("/commerce/costs")
@PreAuthorize("isAuthenticated()")
public class CommerceCostController {
    private final CommerceCostService costs;
    public CommerceCostController(CommerceCostService costs){this.costs=costs;}
    @GetMapping("/ledger") @CommercePermission(CommerceCapability.REFUND_REVIEW)
    public AjaxResult ledger(@RequestParam(defaultValue="100") int limit){return AjaxResult.success(costs.ledger(SecurityUtils.getUserId(),limit));}
    @GetMapping("/orders/{id}") @CommercePermission(CommerceCapability.REFUND_REVIEW)
    public AjaxResult preview(@PathVariable String id){return AjaxResult.success(costs.preview(id,SecurityUtils.getUserId()));}
    @PostMapping("/observations") @CommercePermission(CommerceCapability.REFUND_REVIEW)
    public AjaxResult observe(@RequestBody Map<String,Object> body){return AjaxResult.success(costs.importObservation(body,SecurityUtils.getUserId()));}
    @GetMapping("/reconciliations") @CommercePermission(CommerceCapability.REFUND_REVIEW)
    public AjaxResult list(){return AjaxResult.success(costs.reconciliations(SecurityUtils.getUserId()));}
    @PostMapping("/reconciliations") @CommercePermission(CommerceCapability.REFUND_REVIEW)
    public AjaxResult reconcile(@RequestBody Map<String,Object> body){return AjaxResult.success(costs.reconcile(body,SecurityUtils.getUserId()));}
    @PostMapping("/reconciliations/{id}/resolve") @CommercePermission(CommerceCapability.REFUND_REVIEW)
    public AjaxResult resolve(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(costs.resolve(id,body,SecurityUtils.getUserId()));}
}
