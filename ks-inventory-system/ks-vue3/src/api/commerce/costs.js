import request from '@/utils/request'
const scoped = (shopId, config) => request({ ...config, headers: { 'X-Shop-ID': shopId } })
export const getCostLedger = shopId => scoped(shopId, { url: '/commerce/costs/ledger', method: 'get', params: { limit: 100 } })
export const listMoneyReconciliations = shopId => scoped(shopId, { url: '/commerce/costs/reconciliations', method: 'get' })
export const previewOrderMoney = (shopId, orderId) => scoped(shopId, { url: `/commerce/costs/orders/${encodeURIComponent(orderId)}`, method: 'get' })
export const importSandboxObservation = (shopId, data) => scoped(shopId, { url: '/commerce/costs/observations', method: 'post', data })
export const createMoneyReconciliation = (shopId, data) => scoped(shopId, { url: '/commerce/costs/reconciliations', method: 'post', data })
export const resolveMoneyReconciliation = (shopId, id, data) => scoped(shopId, { url: `/commerce/costs/reconciliations/${encodeURIComponent(id)}/resolve`, method: 'post', data })
