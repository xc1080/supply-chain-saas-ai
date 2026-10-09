package com.ruoyi.web.controller.commerce;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.CommerceService;
import com.ruoyi.system.service.CommerceMerchantService;
import com.ruoyi.system.service.CommerceQueueService;
import com.ruoyi.system.service.CommerceReceiptInventoryGuard;
import com.ruoyi.system.service.CommerceAfterSalesService;
import com.ruoyi.system.service.CommercePermission;
import com.ruoyi.system.service.CommerceCapability;
import com.ruoyi.system.service.CommerceAccessPolicy;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.ruoyi.framework.datasource.SeckillAdmission;
import com.ruoyi.common.core.tenant.TenantContext;

/** Server-only adapter endpoints plus the administrator fulfillment console. */
@RestController
@Profile({"local","commerce"})
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
    @CommercePermission(shopRequired=false)
    public AjaxResult shops() { return AjaxResult.success(merchants.shops(SecurityUtils.getUserId())); }
    @PostMapping("/shops")
    @CommercePermission(value=CommerceCapability.SHOP_MEMBERS,shopRequired=false)
    @PreAuthorize("@ss.hasAnyRoles('admin,tenant_admin')")
    public AjaxResult createShop(@RequestBody Map<String,Object> body) { return AjaxResult.success(merchants.create((String)body.get("shopName"),SecurityUtils.getUserId())); }
    @PostMapping("/shops/{shopId}/members")
    @CommercePermission(CommerceCapability.SHOP_MEMBERS)
    public AjaxResult member(@PathVariable String shopId,@RequestBody Map<String,Object> body) {
        Object user=body.get("userId");
        if(user==null || !String.valueOf(user).matches("[1-9][0-9]{0,17}"))throw new ServiceException("成员账号格式错误",400);
        merchants.setMember(shopId,SecurityUtils.getUserId(),Long.parseLong(String.valueOf(user)),(String)body.get("role")); return AjaxResult.success();
    }
    @PostMapping("/products/{productId}/listing")
    @CommercePermission(CommerceCapability.CATALOG)
    public AjaxResult listing(@PathVariable long productId,@RequestBody Map<String,Object> body) { if(!(body.get("listed") instanceof Boolean))throw new ServiceException("上架参数错误",400); merchants.listing(productId,Boolean.TRUE.equals(body.get("listed")),SecurityUtils.getUserId()); return AjaxResult.success(); }

    @GetMapping("/context")
    @CommercePermission(customer=true,shopRequired=false)
    public AjaxResult context() { Map<String,Object> result = new java.util.LinkedHashMap<>(); result.put("tenantId",TenantContext.id()); result.put("userId",SecurityUtils.getUserId()); return AjaxResult.success(result); }

    @PostMapping("/orders")
    @CommercePermission(value=CommerceCapability.FULFILMENT,customer=true,ownerScoped=true)
    public AjaxResult create(@RequestBody Map<String, Object> body) { customerOwner(body); return AjaxResult.success(service.create(body)); }

    @GetMapping("/orders")
    @CommercePermission(customer=true,ownerScoped=true)
    public AjaxResult list(@RequestParam(required = false) String ownerId, @RequestParam(required = false) Integer status,
                           @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize) {
        return AjaxResult.success(service.list(ownerId, status, pageNum, pageSize));
    }

    @GetMapping("/orders/{orderId}")
    @CommercePermission(customer=true,ownerScoped=true)
    public AjaxResult detail(@PathVariable String orderId, @RequestParam(required = false) String ownerId) {
        return AjaxResult.success(service.detail(orderId, ownerId));
    }

    @PostMapping("/orders/{orderId}/sandbox-pay")
    @CommercePermission(value=CommerceCapability.FULFILMENT,customer=true,ownerScoped=true)
    public AjaxResult pay(@PathVariable String orderId, @RequestBody Map<String, Object> body) { customerOwner(body); return AjaxResult.success(service.pay(orderId, body)); }

    @PostMapping("/orders/{orderId}/cancel")
    @CommercePermission(value=CommerceCapability.FULFILMENT,customer=true,ownerScoped=true)
    public AjaxResult cancel(@PathVariable String orderId, @RequestBody Map<String, Object> body) { customerOwner(body); return AjaxResult.success(service.cancel(orderId, (String) body.get("ownerId"))); }

    @PostMapping("/orders/{orderId}/ship")
    @CommercePermission(CommerceCapability.FULFILMENT)
    public AjaxResult ship(@PathVariable String orderId, @RequestBody Map<String, Object> body) { return AjaxResult.success(service.ship(orderId, body, SecurityUtils.getUserId())); }

    @PostMapping("/orders/{orderId}/receive")
    @CommercePermission(value=CommerceCapability.FULFILMENT,customer=true,ownerScoped=true)
    public AjaxResult receive(@PathVariable String orderId, @RequestBody Map<String, Object> body) { customerOwner(body); return AjaxResult.success(service.receive(orderId, (String) body.get("ownerId"))); }

    @PostMapping("/orders/{orderId}/after-sales")
    @CommercePermission(value=CommerceCapability.FULFILMENT,customer=true,ownerScoped=true)
    public AjaxResult applyAfterSales(@PathVariable String orderId,@RequestBody Map<String,Object> body) { customerOwner(body); return AjaxResult.success(afterSales.apply(orderId,body)); }
    @GetMapping("/after-sales")
    @CommercePermission(customer=true,ownerScoped=true)
    public AjaxResult afterSalesList(@RequestParam(required=false) String ownerId,@RequestParam(required=false) String status,
                                    @RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return AjaxResult.success(afterSales.list(ownerId,status,pageNum,pageSize));
    }
    @GetMapping("/after-sales/{afterSalesId}")
    @CommercePermission(customer=true,ownerScoped=true)
    public AjaxResult afterSalesDetail(@PathVariable String afterSalesId,@RequestParam(required=false) String ownerId) { return AjaxResult.success(afterSales.detail(afterSalesId,ownerId)); }
    @PostMapping("/after-sales/{afterSalesId}/review")
    @CommercePermission(CommerceCapability.REFUND_REVIEW)
    public AjaxResult reviewAfterSales(@PathVariable String afterSalesId,@RequestBody Map<String,Object> body) { return AjaxResult.success(afterSales.review(afterSalesId,body,SecurityUtils.getUserId())); }
    @PostMapping("/after-sales/{afterSalesId}/accept-return")
    @CommercePermission(CommerceCapability.FULFILMENT)
    public AjaxResult acceptAfterSalesReturn(@PathVariable String afterSalesId,@RequestBody Map<String,Object> body) { return AjaxResult.success(afterSales.acceptReturn(afterSalesId,body,SecurityUtils.getUserId())); }
    @PostMapping("/after-sales/{afterSalesId}/sandbox-refund")
    @CommercePermission(CommerceCapability.REFUND_EXECUTE)
    public AjaxResult refundAfterSales(@PathVariable String afterSalesId,@RequestBody Map<String,Object> body) { return AjaxResult.success(afterSales.refund(afterSalesId,body,SecurityUtils.getUserId())); }

    @GetMapping("/activities")
    @CommercePermission(customer=true)
    public AjaxResult activities() { return AjaxResult.success(service.activities()); }
    @GetMapping("/activities/participation")
    @CommercePermission(customer=true,ownerScoped=true)
    public AjaxResult activityParticipation(@RequestParam String ownerId) { return AjaxResult.success(service.activityParticipation(ownerId)); }
    @PostMapping("/activities")
    @CommercePermission(CommerceCapability.CATALOG)
    public AjaxResult createActivity(@RequestBody Map<String,Object> body) { return AjaxResult.success(service.createActivity(body)); }
    @PostMapping("/activities/{activityId}/orders")
    @CommercePermission(value=CommerceCapability.FULFILMENT,customer=true,ownerScoped=true)
    public AjaxResult seckill(@PathVariable String activityId, @RequestBody Map<String,Object> body) {
        customerOwner(body);
        admission.admit(activityId, body.get("ownerId")); return AjaxResult.success(service.seckill(activityId, body));
    }

    @GetMapping("/inventory")
    @CommercePermission
    public AjaxResult inventory() { return AjaxResult.success(service.inventory()); }
    @GetMapping("/inventory/{productId}/ledger")
    @CommercePermission
    public AjaxResult ledger(@PathVariable long productId,@RequestParam(defaultValue="100") int limit) { return AjaxResult.success(service.stockLedger(productId,limit)); }
    @GetMapping("/inventory/{productId}/warehouse-ledger")
    @CommercePermission
    public AjaxResult warehouseLedger(@PathVariable long productId,@RequestParam(defaultValue="100") int limit) { service.stockLedger(productId,1); return AjaxResult.success(warehouse.warehouseLedger(productId,limit)); }
    @GetMapping("/inventory/reconciliation")
    @CommercePermission
    public AjaxResult reconcile() { return AjaxResult.success(service.reconcile()); }
    @PostMapping("/activities/{activityId}/checkout")
    @CommercePermission(value=CommerceCapability.FULFILMENT,customer=true,ownerScoped=true)
    public AjaxResult checkout(@PathVariable String activityId,@RequestBody Map<String,Object> body) { customerOwner(body); admission.admit(activityId,body.get("ownerId")); return AjaxResult.success(queue.submit(activityId,body)); }
    @GetMapping("/checkouts/{jobId}")
    @CommercePermission(customer=true,ownerScoped=true)
    public AjaxResult checkoutStatus(@PathVariable String jobId,@RequestParam String ownerId) { return AjaxResult.success(queue.status(jobId,ownerId)); }
    @GetMapping("/queue/metrics")
    @CommercePermission
    public AjaxResult metrics() { return AjaxResult.success(queue.metrics()); }

    @GetMapping("/catalog")
    @CommercePermission(customer=true)
    public AjaxResult catalog() {
        java.util.List<Map<String,Object>> rows=new java.util.ArrayList<>();
        for(Map<String,Object> stock:service.inventory()) {
            if(!java.util.Arrays.asList(1,1L,true,"1").contains(stock.get("listed")) || !"0".equals(String.valueOf(stock.get("productStatus"))) || !Boolean.TRUE.equals(stock.get("snapshotReady")))continue;
            Map<String,Object> row=new java.util.LinkedHashMap<>();
            for(String key:java.util.Arrays.asList("productId","productCode","productName","cover","spec","price","description","categoryName","productStatus","listed","snapshotReady","availableStock"))row.put(key,stock.get(key));
            rows.add(row);
        }
        return AjaxResult.success(rows);
    }
    private static void customerOwner(Map<String,Object> body) {
        Object owner=body.get("ownerId");
        CommerceAccessPolicy.requireCustomerOwner(owner instanceof String ? (String)owner : null);
    }
}
