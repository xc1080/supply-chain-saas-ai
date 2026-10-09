package com.ruoyi.web.controller.commerce;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Authenticated sandbox operations. These are not public real-channel callback endpoints. */
@RestController
@Profile({"local","commerce"})
@RequestMapping("/commerce")
@PreAuthorize("isAuthenticated()")
public class CommercePaymentController {
    private final CommercePaymentService payments;
    private final CommerceService commerce;
    private final CommerceAfterSalesService afterSales;
    public CommercePaymentController(CommercePaymentService payments,CommerceService commerce,CommerceAfterSalesService afterSales){this.payments=payments;this.commerce=commerce;this.afterSales=afterSales;}
    @GetMapping("/payments/reconciliation")
    @CommercePermission
    public AjaxResult reconciliation(){return AjaxResult.success(payments.reconciliation());}
    @GetMapping("/payments/operations/{id}")
    @CommercePermission
    public AjaxResult detail(@PathVariable String id){return AjaxResult.success(payments.detail(id));}
    @PostMapping("/payments/operations/{id}/query")
    @CommercePermission(CommerceCapability.REFUND_EXECUTE)
    public AjaxResult query(@PathVariable String id){return AjaxResult.success(payments.query(id,commerce::expireOrder,afterSales::providerRefundSucceeded));}
    @PostMapping("/orders/{orderId}/payments/{id}/query")
    @CommercePermission(customer=true,ownerScoped=true)
    public AjaxResult customerQuery(@PathVariable String orderId,@PathVariable String id,@RequestBody Map<String,Object> body){
        String owner=body.get("ownerId") instanceof String?(String)body.get("ownerId"):null;
        CommerceAccessPolicy.requireCustomerOwner(owner);if(owner==null||!owner.matches("[a-f0-9]{64}"))throw new ServiceException("访客标识错误",400);
        commerce.detail(orderId,owner);
        Map<String,Object> operation=payments.detail(id);if(!orderId.equals(operation.get("orderId"))||!"PAYMENT".equals(operation.get("kind")))throw new ServiceException("支付请求不存在",404);
        Map<String,Object> queried=payments.query(id,commerce::expireOrder,null),result=commerce.detail(orderId,owner);
        result.put("paymentOperation",queried);result.put("paymentOutcome",queried.get("outcome"));return AjaxResult.success(result);
    }
    @PostMapping("/payments/operations/{id}/sandbox-result")
    @CommercePermission(CommerceCapability.REFUND_EXECUTE)
    public AjaxResult advance(@PathVariable String id,@RequestBody Map<String,Object> body){return AjaxResult.success(payments.advance(id,String.valueOf(body.get("status")),commerce::expireOrder,afterSales::providerRefundSucceeded));}
    @PostMapping("/payments/provider-events")
    @CommercePermission(CommerceCapability.REFUND_EXECUTE)
    public AjaxResult receive(@RequestBody String raw,@RequestHeader("X-Payment-Timestamp") String timestamp,@RequestHeader("X-Payment-Signature") String signature){return AjaxResult.success(payments.receive(raw,timestamp,signature,commerce::expireOrder,afterSales::providerRefundSucceeded));}
    @PostMapping("/payments/provider-events/{eventId}/replay")
    @CommercePermission(CommerceCapability.REFUND_EXECUTE)
    public AjaxResult replay(@PathVariable String eventId){return AjaxResult.success(payments.replay(eventId,commerce::expireOrder,afterSales::providerRefundSucceeded));}
}
