package com.ruoyi.web.controller.purchase;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.DetailOrderForm;
import com.ruoyi.common.core.domain.entity.HeadOrderForm;
import com.ruoyi.common.core.domain.entity.OrderFrom;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.service.PurchaseOrderProcessingService;
import com.ruoyi.system.service.CommerceProcurementService;
import com.ruoyi.system.service.CommerceProcurementCommandService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 销售订单查询模块
 *
 * @author KrityCat
 */
@RestController
@RequestMapping("/purchase/purchaseOrderProcessing")
public class PurchaseOrderProcessingController extends BaseController {

    @Autowired
    private PurchaseOrderProcessingService purchaseOrderProcessingService;
    @Autowired(required = false)
    private CommerceProcurementService procurement;
    @Autowired(required = false)
    private CommerceProcurementCommandService commands;

    @PreAuthorize("@ss.hasPermi('purchase:purchaseOrderProcessing:systematicOrderForm')")
    @GetMapping("/{orderId}/progress")
    public AjaxResult progress(@PathVariable String orderId) {
        return AjaxResult.success(requireProcurement().progress(orderId));
    }

    @PreAuthorize("@ss.hasPermi('purchase:purchaseOrderProcessing:systematicOrderForm') and @ss.hasPermi('purchase:purchaseReceiptProcessing:systematicReceipt')")
    @GetMapping("/{orderId}/receipt-sources")
    public AjaxResult receiptSources(@PathVariable String orderId) {
        return AjaxResult.success(requireProcurement().receiptSources(orderId));
    }

    @PreAuthorize("@ss.hasPermi('purchase:purchaseOrderProcessing:systematicOrderForm') and @ss.hasPermi('purchase:purchaseReceiptProcessing:save')")
    @Log(title = "采购订单生成收货草稿", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/receipts")
    public AjaxResult receipt(@PathVariable String orderId,@RequestBody Map<String,Object> body) {
        requireProcurement();
        return AjaxResult.success(commands.createReceipt(orderId,body,getUserId(),getUsername(),false));
    }

    @PreAuthorize("@ss.hasPermi('purchase:purchaseOrderProcessing:systematicOrderForm') and @ss.hasPermi('purchase:purchaseReceiptProcessing:save')")
    @Log(title = "采购订单生成退供草稿", businessType = BusinessType.INSERT)
    @PostMapping("/{orderId}/returns")
    public AjaxResult returned(@PathVariable String orderId,@RequestBody Map<String,Object> body) {
        requireProcurement();
        return AjaxResult.success(commands.createReceipt(orderId,body,getUserId(),getUsername(),true));
    }

    private CommerceProcurementService requireProcurement() {
        if(procurement==null||commands==null)throw new com.ruoyi.common.exception.ServiceException("当前环境未启用采购来源闭环",409);
        return procurement;
    }

    /**
     * @param systematicOrderForm 系统单号
     * @return 订单详情
     */
    @PreAuthorize("@ss.hasPermi('purchase:purchaseOrderProcessing:systematicOrderForm')")
    @GetMapping(value = {"/", "/{systematicOrderForm}"})
    public AjaxResult getInfo(@PathVariable(value = "systematicOrderForm", required = false) String systematicOrderForm) {
        AjaxResult ajax = AjaxResult.success();
        if (StringUtils.isNotNull(systematicOrderForm)) {
            HeadOrderForm headOrderForm = purchaseOrderProcessingService.selectPurchaseOrderFormById(systematicOrderForm);
            ajax.put(AjaxResult.DATA_TAG, headOrderForm);
        }
        return ajax;
    }

    /**
     * @param bo 订单信息
     * @return 保存销售出库订单信息
     */
    @PreAuthorize("@ss.hasPermi('purchase:purchaseOrderProcessing:save')")
    @Log(title = "保存销售订单", businessType = BusinessType.INSERT)
    @PostMapping("/save")
    public AjaxResult save(@RequestBody OrderFrom bo) throws Exception {
        bo.setCreateBy(getUsername());
        bo.setUpdateBy(getUsername());
        return toAjax(purchaseOrderProcessingService.savePurchaseOrderForm(bo));
    }

    /**
     * 删除采购订单接口
     */
    @PreAuthorize("@ss.hasPermi('purchase:purchaseOrderProcessing:delete')")
    @Log(title = "删除采购订单", businessType = BusinessType.INSERT)
    @PostMapping("/delete")
    public AjaxResult remove(@RequestBody List<DetailOrderForm> bo) {
        return toAjax(purchaseOrderProcessingService.delPurchaseOrderForm(bo));
    }
}
