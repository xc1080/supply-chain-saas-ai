package com.ruoyi.web.controller.commerce;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Owner-scoped reads and separately authorized writes to a clearly labeled test provider. */
@RestController
@Profile({"local","commerce"})
@RequestMapping("/commerce")
@PreAuthorize("isAuthenticated()")
public class CommerceLogisticsController {
    private final CommerceLogisticsService logistics;
    public CommerceLogisticsController(CommerceLogisticsService logistics){this.logistics=logistics;}
    @GetMapping("/shipments/{shipmentId}/tracking")
    @CommercePermission(customer=true,ownerScoped=true)
    public AjaxResult shipmentTracking(@PathVariable String shipmentId,@RequestParam(required=false) String ownerId){return AjaxResult.success(logistics.shipmentTracking(shipmentId,ownerId));}
    @PostMapping("/shipments/{shipmentId}/tracking/sandbox-events")
    @CommercePermission(CommerceCapability.FULFILMENT)
    public AjaxResult shipmentEvent(@PathVariable String shipmentId,@RequestBody Map<String,Object> body){return AjaxResult.success(logistics.recordShipmentEvent(shipmentId,body,SecurityUtils.getUserId()));}
    @GetMapping("/after-sales/{afterSalesId}/return-parcel")
    @CommercePermission(customer=true,ownerScoped=true)
    public AjaxResult returnParcel(@PathVariable String afterSalesId,@RequestParam(required=false) String ownerId){return AjaxResult.success(logistics.returnParcel(afterSalesId,ownerId));}
    @PostMapping("/after-sales/{afterSalesId}/return-parcel/tracking/sandbox-events")
    @CommercePermission(CommerceCapability.FULFILMENT)
    public AjaxResult returnEvent(@PathVariable String afterSalesId,@RequestBody Map<String,Object> body){return AjaxResult.success(logistics.recordReturnEvent(afterSalesId,body,SecurityUtils.getUserId()));}
}
