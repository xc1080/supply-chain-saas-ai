import request from '@/utils/request'

const scoped = (shopId, config) => request({ ...config, headers: { 'X-Shop-ID': shopId } })
export const pageReplenishmentDrafts = (shopId, params) => scoped(shopId, { url: '/commerce/planning/drafts/page', method: 'get', params })
export const pageCommerceIncoming = (shopId, params) => scoped(shopId, { url: '/commerce/planning/incoming/page', method: 'get', params })
export const pageIncomingChanges = (shopId, params) => scoped(shopId, { url: '/commerce/planning/incoming/changes/page', method: 'get', params })
