package com.ruoyi.web.controller.commerce;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.CommerceCapability;
import com.ruoyi.system.service.CommercePermission;
import com.ruoyi.system.service.CommerceSkuCatalogService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile({"local","commerce"})
@RequestMapping("/commerce/spus")
@PreAuthorize("isAuthenticated()")
public class CommerceSkuCatalogController {
    private final CommerceSkuCatalogService service;
    public CommerceSkuCatalogController(CommerceSkuCatalogService service) {this.service=service;}
    @GetMapping
    @CommercePermission(CommerceCapability.READ)
    public AjaxResult list() {return AjaxResult.success(service.list(SecurityUtils.getUserId()));}
    @PostMapping
    @CommercePermission(CommerceCapability.CATALOG)
    public AjaxResult create(@RequestBody Map<String,Object> body) {return AjaxResult.success(service.create(body,SecurityUtils.getUserId()));}
    @PostMapping("/{spuId}/skus")
    @CommercePermission(CommerceCapability.CATALOG)
    public AjaxResult append(@PathVariable String spuId,@RequestBody Map<String,Object> body) {return AjaxResult.success(service.append(spuId,body,SecurityUtils.getUserId()));}
}
