package com.ruoyi.web.controller.commerce;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.CommercePlanningService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile("local")
@RequestMapping("/commerce/planning")
@PreAuthorize("isAuthenticated()")
public class CommercePlanningController {
    private final CommercePlanningService service;
    public CommercePlanningController(CommercePlanningService service){this.service=service;}
    @PostMapping("/quote") public AjaxResult quote(@RequestBody Map<String,Object> body){return AjaxResult.success(service.quote(body));}
    @GetMapping("/replenishment") public AjaxResult replenishment(){return AjaxResult.success(service.replenishment(SecurityUtils.getUserId()));}
    @PostMapping("/policy") public AjaxResult policy(@RequestBody Map<String,Object> body){return AjaxResult.success(service.savePolicy(body,SecurityUtils.getUserId()));}
    @PostMapping("/conditions") public AjaxResult condition(@RequestBody Map<String,Object> body){return AjaxResult.success(service.condition(body,SecurityUtils.getUserId()));}
    @GetMapping("/conditions") public AjaxResult conditions(){return AjaxResult.success(service.conditions(SecurityUtils.getUserId()));}
    @PostMapping("/incoming") public AjaxResult incoming(@RequestBody Map<String,Object> body){return AjaxResult.success(service.registerIncoming(body,SecurityUtils.getUserId()));}
    @GetMapping("/incoming") public AjaxResult incoming(){return AjaxResult.success(service.incoming(SecurityUtils.getUserId()));}
    @PostMapping("/incoming/{id}/receive") public AjaxResult receive(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.receiveIncoming(id,body,SecurityUtils.getUserId()));}
    @PostMapping("/drafts") public AjaxResult draft(@RequestBody Map<String,Object> body){return AjaxResult.success(service.createDraft(body,SecurityUtils.getUserId()));}
    @GetMapping("/drafts") public AjaxResult drafts(){return AjaxResult.success(service.drafts(SecurityUtils.getUserId()));}
    @PostMapping("/drafts/{id}/review") public AjaxResult review(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.reviewDraft(id,body,SecurityUtils.getUserId()));}
}
