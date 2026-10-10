import request from '@/utils/request'
import { parseStrEmpty } from "@/utils/ruoyi";

// 查询采购订单详情
export function getPurchaseOrder(systematicOrderForm) {
  return request({
    url: '/purchase/purchaseOrderProcessing/' + parseStrEmpty(systematicOrderForm),
    method: 'get'
  })
}

// 保存采购订单
export function savePurchaseOrder(data) {
  return request({
    url: '/purchase/purchaseOrderProcessing/save',
    method: 'post',
    data: data
  })
}

// 删除采购订单
export function delPurchaseOrder(data) {
  return request({
    url: '/purchase/purchaseOrderProcessing/delete',
    method: 'post',
    data: data
  })
}

export function getPurchaseProgress(orderId) {
  return request({ url: `/purchase/purchaseOrderProcessing/${encodeURIComponent(orderId)}/progress`, method: 'get' })
}

export function createPurchaseReceipt(orderId, data) {
  return request({
    url: `/purchase/purchaseOrderProcessing/${encodeURIComponent(orderId)}/receipts`,
    method: 'post', data, headers: { repeatSubmit: false }
  })
}

export function getPurchaseReceiptSources(orderId) {
  return request({ url: `/purchase/purchaseOrderProcessing/${encodeURIComponent(orderId)}/receipt-sources`, method: 'get' })
}

export function createPurchaseReturn(orderId, data) {
  return request({
    url: `/purchase/purchaseOrderProcessing/${encodeURIComponent(orderId)}/returns`,
    method: 'post', data, headers: { repeatSubmit: false }
  })
}
