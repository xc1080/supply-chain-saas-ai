package com.ruoyi.web.controller.commerce;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.service.CommercePermission;
import com.ruoyi.system.service.CommerceWarehouseAllocationService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"local","commerce"})
@RequestMapping("/commerce")
@PreAuthorize("isAuthenticated()")
public class CommerceWarehouseAllocationController {
    private final CommerceWarehouseAllocationService warehouses;
    public CommerceWarehouseAllocationController(CommerceWarehouseAllocationService warehouses) {this.warehouses=warehouses;}
    @GetMapping("/orders/{orderId}/warehouse-allocations")
    @CommercePermission
    public AjaxResult allocations(@PathVariable String orderId) {return AjaxResult.success(warehouses.allocations(orderId));}
    @GetMapping("/orders/{orderId}/warehouse-allocation-events")
    @CommercePermission
    public AjaxResult allocationEvents(@PathVariable String orderId) {return AjaxResult.success(warehouses.allocationEvents(orderId));}
    @GetMapping("/inventory/{productId}/warehouses")
    @CommercePermission
    public AjaxResult warehouses(@PathVariable long productId) {return AjaxResult.success(warehouses.warehouses(productId));}
}
