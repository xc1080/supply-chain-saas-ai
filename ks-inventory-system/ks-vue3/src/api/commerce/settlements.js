import request from '@/utils/request'
const scoped = (shopId, config) => request({ ...config, headers: { 'X-Shop-ID': shopId } })
const orderPath = id => `/commerce/settlements/orders/${encodeURIComponent(id)}`
export const getOrderSettlement = (shopId, id) => scoped(shopId, { url: orderPath(id), method: 'get' })
export const registerOrderExpense = (shopId, id, data) => scoped(shopId, { url: `${orderPath(id)}/expenses`, method: 'post', data })
export const reviewOrderExpense = (shopId, id, data) => scoped(shopId, { url: `/commerce/settlements/expenses/${encodeURIComponent(id)}/review`, method: 'post', data })
export const settleOrderExpenses = (shopId, id, data) => scoped(shopId, { url: `${orderPath(id)}/payments`, method: 'post', data })
export const snapshotOrderSettlement = (shopId, id, data) => scoped(shopId, { url: `${orderPath(id)}/snapshots`, method: 'post', data })
