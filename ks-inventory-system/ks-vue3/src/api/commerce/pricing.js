import request from '@/utils/request'
const scoped = (shopId, config) => request({ ...config, headers: { 'X-Shop-ID': shopId } })
export const getPricingPolicy = shopId => scoped(shopId, { url: '/commerce/pricing/policy', method: 'get' })
export const savePricingPolicy = (shopId, data) => scoped(shopId, { url: '/commerce/pricing/policy', method: 'put', data })
export const listPricingPromotions = shopId => scoped(shopId, { url: '/commerce/pricing/promotions/manage', method: 'get' })
export const savePricingPromotion = (shopId, id, data) => scoped(shopId, { url: id ? `/commerce/pricing/promotions/${encodeURIComponent(id)}` : '/commerce/pricing/promotions', method: id ? 'put' : 'post', data })
