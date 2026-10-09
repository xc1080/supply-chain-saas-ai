import baseRequest from '@/utils/request'
let activeShop = 'default'
export const setCommerceShop = id => { activeShop = id || 'default' }
const request = config => baseRequest({ ...config, headers: { ...config.headers, 'X-Shop-ID': activeShop } })

export function listCommerceOrders(query) {
  return request({ url: '/commerce/orders', method: 'get', params: query })
}

export function getCommerceOrder(orderId) {
  return request({ url: `/commerce/orders/${encodeURIComponent(orderId)}`, method: 'get' })
}

export function listCommerceInventory() {
  return request({ url: '/commerce/inventory', method: 'get' })
}

export function shipCommerceOrder(orderId, data) {
  return request({ url: `/commerce/orders/${encodeURIComponent(orderId)}/ship`, method: 'post', data })
}

export const getCommerceContext = () => request({ url: '/commerce/context', method: 'get' })
export const listCommerceActivities = () => request({ url: '/commerce/activities', method: 'get' })
export const createCommerceActivity = data => request({ url: '/commerce/activities', method: 'post', data })

export const listCommerceShops = () => request({ url: '/commerce/shops', method: 'get' })
export const createCommerceShop = shopName => request({ url: '/commerce/shops', method: 'post', data: { shopName } })
export const listStockLedger = productId => request({ url: `/commerce/inventory/${productId}/ledger`, method: 'get' })
export const reconcileStock = () => request({ url: '/commerce/inventory/reconciliation', method: 'get' })
export const getQueueMetrics = () => request({ url: '/commerce/queue/metrics', method: 'get' })
export const setProductListing = (productId, listed) => request({ url: `/commerce/products/${productId}/listing`, method: 'post', data: { listed } })

export const listWarehouseLedger = productId => request({ url: `/commerce/inventory/${productId}/warehouse-ledger`, method: 'get' })
export const listCommerceAfterSales = query => request({ url: '/commerce/after-sales', method: 'get', params: query })
export const getCommerceAfterSales = id => request({ url: `/commerce/after-sales/${encodeURIComponent(id)}`, method: 'get' })
export const reviewCommerceAfterSales = (id, data) => request({ url: `/commerce/after-sales/${encodeURIComponent(id)}/review`, method: 'post', data })
export const acceptCommerceReturn = (id, data) => request({ url: `/commerce/after-sales/${encodeURIComponent(id)}/accept-return`, method: 'post', data })
export const refundCommerceAfterSales = (id, data) => request({ url: `/commerce/after-sales/${encodeURIComponent(id)}/sandbox-refund`, method: 'post', data })
export const getCommerceReplenishment = () => request({ url: '/commerce/planning/replenishment', method: 'get' })
export const listReplenishmentDrafts = () => request({ url: '/commerce/planning/drafts', method: 'get' })
export const createReplenishmentDraft = data => request({ url: '/commerce/planning/drafts', method: 'post', data })
export const reviewReplenishmentDraft = (id, data) => request({ url: `/commerce/planning/drafts/${encodeURIComponent(id)}/review`, method: 'post', data })
export const listCommerceStockConditions = () => request({ url: '/commerce/planning/conditions', method: 'get' })
