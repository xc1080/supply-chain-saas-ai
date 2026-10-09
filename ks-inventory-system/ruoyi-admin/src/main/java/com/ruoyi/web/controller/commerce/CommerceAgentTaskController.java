package com.ruoyi.web.controller.commerce;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.CommerceAgentTaskService;
import com.ruoyi.system.service.CommerceCapability;
import com.ruoyi.system.service.CommercePermission;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile({"local","commerce"})
@RequestMapping("/commerce/agent-tasks")
@PreAuthorize("isAuthenticated()")
public class CommerceAgentTaskController {
    private final CommerceAgentTaskService tasks;
    public CommerceAgentTaskController(CommerceAgentTaskService tasks){this.tasks=tasks;}
    @GetMapping @CommercePermission public AjaxResult list(){return AjaxResult.success(tasks.list(SecurityUtils.getUserId()));}
    @GetMapping("/request/{key}") @CommercePermission(CommerceCapability.SUPPLY_DRAFT) public AjaxResult request(@PathVariable String key){return AjaxResult.success(tasks.byRequest(key,SecurityUtils.getUserId()));}
    @GetMapping("/{id}") @CommercePermission public AjaxResult get(@PathVariable String id){return AjaxResult.success(tasks.get(id,SecurityUtils.getUserId()));}
    @PostMapping @CommercePermission(CommerceCapability.SUPPLY_DRAFT) public AjaxResult create(@RequestBody Map<String,Object> body){return AjaxResult.success(tasks.create(body,SecurityUtils.getUserId()));}
    @PostMapping("/{id}/refresh") @CommercePermission(CommerceCapability.SUPPLY_DRAFT) public AjaxResult refresh(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(tasks.refresh(id,body,SecurityUtils.getUserId()));}
    @PostMapping("/{id}/review") @CommercePermission(CommerceCapability.SUPPLY_REVIEW) public AjaxResult review(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(tasks.review(id,body,SecurityUtils.getUserId()));}
    @PostMapping("/{id}/execute") @CommercePermission(CommerceCapability.SUPPLY_DRAFT) public AjaxResult execute(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(tasks.execute(id,body,SecurityUtils.getUserId()));}
    @PostMapping("/{id}/cancel") @CommercePermission(CommerceCapability.SUPPLY_DRAFT) public AjaxResult cancel(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(tasks.cancel(id,body,SecurityUtils.getUserId()));}
}
