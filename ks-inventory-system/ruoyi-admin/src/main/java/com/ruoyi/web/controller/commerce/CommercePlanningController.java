package com.ruoyi.web.controller.commerce;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.CommercePlanningService;
import com.ruoyi.system.service.CommercePermission;
import com.ruoyi.system.service.CommerceCapability;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile({"local","commerce"})
@RequestMapping("/commerce/planning")
@PreAuthorize("isAuthenticated()")
public class CommercePlanningController {
    private final CommercePlanningService service;
    public CommercePlanningController(CommercePlanningService service){this.service=service;}
    @PostMapping("/quote") @CommercePermission(customer=true) public AjaxResult quote(@RequestBody Map<String,Object> body){return AjaxResult.success(service.quote(body));}
    @GetMapping("/replenishment") @CommercePermission public AjaxResult replenishment(){return AjaxResult.success(service.replenishment(SecurityUtils.getUserId()));}
    @PostMapping("/policy") @CommercePermission(CommerceCapability.SUPPLY_POLICY) public AjaxResult policy(@RequestBody Map<String,Object> body){return AjaxResult.success(service.savePolicy(body,SecurityUtils.getUserId()));}
    @PostMapping("/conditions") @CommercePermission(CommerceCapability.STOCK_ADJUST) public AjaxResult condition(@RequestBody Map<String,Object> body){return AjaxResult.success(service.condition(body,SecurityUtils.getUserId()));}
    @GetMapping("/conditions") @CommercePermission public AjaxResult conditions(){return AjaxResult.success(service.conditions(SecurityUtils.getUserId()));}
    @PostMapping("/incoming") @CommercePermission(CommerceCapability.SUPPLY_DRAFT) public AjaxResult incoming(@RequestBody Map<String,Object> body){return AjaxResult.success(service.registerIncoming(body,SecurityUtils.getUserId()));}
    @GetMapping("/incoming") @CommercePermission public AjaxResult incoming(){return AjaxResult.success(service.incoming(SecurityUtils.getUserId()));}
    @PostMapping("/incoming/{id}/receive") @CommercePermission(CommerceCapability.FULFILMENT) public AjaxResult receive(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.receiveIncoming(id,body,SecurityUtils.getUserId()));}
    @PostMapping("/incoming/{id}/changes") @CommercePermission(CommerceCapability.SUPPLY_DRAFT) public AjaxResult proposeIncomingChange(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.proposeIncomingChange(id,body,SecurityUtils.getUserId()));}
    @GetMapping("/incoming/changes") @CommercePermission public AjaxResult incomingChanges(){return AjaxResult.success(service.incomingChanges(SecurityUtils.getUserId()));}
    @PostMapping("/incoming/changes/{id}/review") @CommercePermission(CommerceCapability.SUPPLY_REVIEW) public AjaxResult reviewIncomingChange(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.reviewIncomingChange(id,body,SecurityUtils.getUserId()));}
    @PostMapping("/drafts") @CommercePermission(CommerceCapability.SUPPLY_DRAFT) public AjaxResult draft(@RequestBody Map<String,Object> body){return AjaxResult.success(service.createDraft(body,SecurityUtils.getUserId()));}
    @GetMapping("/drafts") @CommercePermission public AjaxResult drafts(){return AjaxResult.success(service.drafts(SecurityUtils.getUserId()));}
    @PostMapping("/drafts/{id}/review") @CommercePermission(CommerceCapability.SUPPLY_REVIEW) public AjaxResult review(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.reviewDraft(id,body,SecurityUtils.getUserId()));}
    @PostMapping("/drafts/{id}/cancel") @CommercePermission(CommerceCapability.SUPPLY_DRAFT) public AjaxResult cancel(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.cancelDraft(id,body,SecurityUtils.getUserId()));}
    @PostMapping("/drafts/{id}/execute") @CommercePermission(CommerceCapability.SUPPLY_DRAFT) public AjaxResult execute(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.executeDraft(id,body,SecurityUtils.getUserId()));}
    @GetMapping("/policy/history") @CommercePermission public AjaxResult policyHistory(){return AjaxResult.success(service.policyHistory(SecurityUtils.getUserId()));}
}
