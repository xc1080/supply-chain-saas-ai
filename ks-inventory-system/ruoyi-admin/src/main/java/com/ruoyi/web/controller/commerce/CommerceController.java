package com.ruoyi.web.controller.commerce;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.CommerceService;
import com.ruoyi.system.service.CommerceMerchantService;
import com.ruoyi.system.service.CommerceQueueService;
import com.ruoyi.system.service.CommerceReceiptInventoryGuard;
import com.ruoyi.system.service.CommerceAfterSalesService;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.ruoyi.framework.datasource.SeckillAdmission;
import com.ruoyi.common.core.tenant.TenantContext;

/** Server-only adapter endpoints plus the administrator fulfillment console. */
@RestController
@Profile("local")
@RequestMapping("/commerce")
@PreAuthorize("isAuthenticated()")
public class CommerceController {
    private final CommerceService service;
    private final SeckillAdmission admission;
    private final CommerceMerchantService merchants;
    private final CommerceQueueService queue;
    private final CommerceReceiptInventoryGuard warehouse;
    private final CommerceAfterSalesService afterSales;
    public CommerceController(CommerceService service, SeckillAdmission admission,CommerceMerchantService merchants,CommerceQueueService queue,CommerceReceiptInventoryGuard warehouse,CommerceAfterSalesService afterSales) { this.service = service; this.admission = admission; this.merchants=merchants; this.queue=queue; this.warehouse=warehouse; this.afterSales=afterSales; }

    @GetMapping("/shops")
    public AjaxResult shops() { return AjaxResult.success(merchants.shops(SecurityUtils.getUserId())); }
    @PostMapping("/shops")
    @PreAuthorize("@ss.hasRole('admin')")
    public AjaxResult createShop(@RequestBody Map<String,Object> body) { return AjaxResult.success(merchants.create((String)body.get("shopName"),SecurityUtils.getUserId())); }
    @PostMapping("/shops/{shopId}/members")
    public AjaxResult member(@PathVariable String shopId,@RequestBody Map<String,Object> body) {
        Object user=body.get("userId");
        if(user==null || !String.valueOf(user).matches("[1-9][0-9]{0,17}"))throw new ServiceException("成员账号格式错误",400);
        merchants.setMember(shopId,SecurityUtils.getUserId(),Long.parseLong(String.valueOf(user)),(String)body.get("role")); return AjaxResult.success();
    }
    @PostMapping("/products/{productId}/listing")
    public AjaxResult listing(@PathVariable long productId,@RequestBody Map<String,Object> body) { if(!(body.get("listed") instanceof Boolean))throw new ServiceException("上架参数错误",400); merchants.listing(productId,Boolean.TRUE.equals(body.get("listed"))); return AjaxResult.success(); }

    @GetMapping("/context")
    public AjaxResult context() { Map<String,Object> result = new java.util.LinkedHashMap<>(); result.put("tenantId",TenantContext.id()); result.put("userId",SecurityUtils.getUserId()); return AjaxResult.success(result); }

    @PostMapping("/orders")
    public AjaxResult create(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.create(body)); }

    @GetMapping("/orders")
    public AjaxResult list(@RequestParam(required = false) String ownerId, @RequestParam(required = false) Integer status,
                           @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize) {
        return AjaxResult.success(service.list(ownerId, status, pageNum, pageSize));
    }

    @GetMapping("/orders/{orderId}")
    public AjaxResult detail(@PathVariable String orderId, @RequestParam(required = false) String ownerId) {
        return AjaxResult.success(service.detail(orderId, ownerId));
    }

    @PostMapping("/orders/{orderId}/sandbox-pay")
    public AjaxResult pay(@PathVariable String orderId, @RequestBody Map<String, Object> body) { return AjaxResult.success(service.pay(orderId, body)); }

    @PostMapping("/orders/{orderId}/cancel")
    public AjaxResult cancel(@PathVariable String orderId, @RequestBody Map<String, Object> body) { return AjaxResult.success(service.cancel(orderId, (String) body.get("ownerId"))); }

    @PostMapping("/orders/{orderId}/ship")
    public AjaxResult ship(@PathVariable String orderId, @RequestBody Map<String, Object> body) { return AjaxResult.success(service.ship(orderId, body, SecurityUtils.getUserId())); }

    @PostMapping("/orders/{orderId}/receive")
    public AjaxResult receive(@PathVariable String orderId, @RequestBody Map<String, Object> body) { return AjaxResult.success(service.receive(orderId, (String) body.get("ownerId"))); }

    @PostMapping("/orders/{orderId}/after-sales")
    public AjaxResult applyAfterSales(@PathVariable String orderId,@RequestBody Map<String,Object> body) { return AjaxResult.success(afterSales.apply(orderId,body)); }
    @GetMapping("/after-sales")
    public AjaxResult afterSalesList(@RequestParam(required=false) String ownerId,@RequestParam(required=false) String status,
                                    @RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return AjaxResult.success(afterSales.list(ownerId,status,pageNum,pageSize));
    }
    @GetMapping("/after-sales/{afterSalesId}")
    public AjaxResult afterSalesDetail(@PathVariable String afterSalesId,@RequestParam(required=false) String ownerId) { return AjaxResult.success(afterSales.detail(afterSalesId,ownerId)); }
    @PostMapping("/after-sales/{afterSalesId}/review")
    public AjaxResult reviewAfterSales(@PathVariable String afterSalesId,@RequestBody Map<String,Object> body) { return AjaxResult.success(afterSales.review(afterSalesId,body,SecurityUtils.getUserId())); }
    @PostMapping("/after-sales/{afterSalesId}/accept-return")
    public AjaxResult acceptAfterSalesReturn(@PathVariable String afterSalesId,@RequestBody Map<String,Object> body) { return AjaxResult.success(afterSales.acceptReturn(afterSalesId,body,SecurityUtils.getUserId())); }
    @PostMapping("/after-sales/{afterSalesId}/sandbox-refund")
    public AjaxResult refundAfterSales(@PathVariable String afterSalesId,@RequestBody Map<String,Object> body) { return AjaxResult.success(afterSales.refund(afterSalesId,body,SecurityUtils.getUserId())); }

    @GetMapping("/activities")
    public AjaxResult activities() { return AjaxResult.success(service.activities()); }
    @GetMapping("/activities/participation")
    public AjaxResult activityParticipation(@RequestParam String ownerId) { return AjaxResult.success(service.activityParticipation(ownerId)); }
    @PostMapping("/activities")
    public AjaxResult createActivity(@RequestBody Map<String,Object> body) { return AjaxResult.success(service.createActivity(body)); }
    @PostMapping("/activities/{activityId}/orders")
    public AjaxResult seckill(@PathVariable String activityId, @RequestBody Map<String,Object> body) {
        admission.admit(activityId, body.get("ownerId")); return AjaxResult.success(service.seckill(activityId, body));
    }

    @GetMapping("/inventory")
    public AjaxResult inventory() { return AjaxResult.success(service.inventory()); }
    @GetMapping("/inventory/{productId}/ledger")
    public AjaxResult ledger(@PathVariable long productId,@RequestParam(defaultValue="100") int limit) { return AjaxResult.success(service.stockLedger(productId,limit)); }
    @GetMapping("/inventory/{productId}/warehouse-ledger")
    public AjaxResult warehouseLedger(@PathVariable long productId,@RequestParam(defaultValue="100") int limit) { service.stockLedger(productId,1); return AjaxResult.success(warehouse.warehouseLedger(productId,limit)); }
    @GetMapping("/inventory/reconciliation")
    public AjaxResult reconcile() { return AjaxResult.success(service.reconcile()); }
    @PostMapping("/activities/{activityId}/checkout")
    public AjaxResult checkout(@PathVariable String activityId,@RequestBody Map<String,Object> body) { admission.admit(activityId,body.get("ownerId")); return AjaxResult.success(queue.submit(activityId,body)); }
    @GetMapping("/checkouts/{jobId}")
    public AjaxResult checkoutStatus(@PathVariable String jobId,@RequestParam String ownerId) { return AjaxResult.success(queue.status(jobId,ownerId)); }
    @GetMapping("/queue/metrics")
    public AjaxResult metrics() { return AjaxResult.success(queue.metrics()); }
}
