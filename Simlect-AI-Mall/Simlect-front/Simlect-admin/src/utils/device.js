
export const ADMIN_MOBILE_MAX_WIDTH = 768

const FORCE_KEY = 'admin_force_view'

export function resolveAppUrl(path) {
  const routePath = !path || path === '/' ? '/home' : path.startsWith('/') ? path : `/${path}`
  const base = import.meta.env.BASE_URL || '/'
  if (!base || base === '/' || base === './') return routePath
  const prefix = base.endsWith('/') ? base.slice(0, -1) : base
  return `${prefix}${routePath}`
}

export function currentRoutePath() {
  const base = import.meta.env.BASE_URL || '/'
  let pathname = typeof window !== 'undefined' ? window.location.pathname : '/'
  if (base && base !== '/' && base !== './') {
    const prefix = base.endsWith('/') ? base.slice(0, -1) : base
    if (pathname === prefix || pathname === `${prefix}/`) return '/home'
    if (pathname.startsWith(`${prefix}/`)) {
      pathname = pathname.slice(prefix.length)
    }
  }
  return pathname || '/home'
}

function readForced() {
  try {
    const url = new URL(window.location.href)
    const q = url.searchParams.get('view')
    if (q === 'mobile' || q === 'desktop') {
      localStorage.setItem(FORCE_KEY, q)
      return q
    }
    const saved = localStorage.getItem(FORCE_KEY)
    if (saved === 'mobile' || saved === 'desktop') return saved
  } catch (e) {

  }
  return null
}

export function detectAdminPlatform() {
  const forced = readForced()
  if (forced) return forced
  if (typeof window === 'undefined') return 'desktop'
  const width = window.innerWidth || document.documentElement.clientWidth || 1024
  const coarse = window.matchMedia && window.matchMedia('(pointer: coarse)').matches
  const ua = navigator.userAgent || ''
  const mobileUa = /iPhone|Android.*Mobile|Windows Phone|iPod/i.test(ua)
  if (width <= ADMIN_MOBILE_MAX_WIDTH) return 'mobile'
  if (mobileUa && coarse && width <= 900) return 'mobile'
  return 'desktop'
}

export function clearForcedView() {
  try {
    localStorage.removeItem(FORCE_KEY)
  } catch (e) {

  }
}

export function resolveDesktopPath(mobilePath) {
  if (mobilePath.startsWith('/m/product/edit/')) {
    const id = mobilePath.split('/').pop()
    return id ? `/product/updateProduct/${id}` : '/product/addProduct'
  }
  if (mobilePath === '/m/product/edit') return '/product/addProduct'
  const map = {
    '/m/home': '/home',
    '/m/product': '/product',
    '/m/order': '/order/orderList',
    '/m/order/comment': '/order/comment',
    '/m/order/report': '/order/report',
    '/m/more/imageModeration': '/setting/imageModeration',
    '/m/user': '/user/userList',
    '/m/more': '/home',
    '/m/more/address': '/user/address',
    '/m/more/agent': '/setting/agentMessage',
    '/m/more/coupon': '/discountCoupon',
    '/m/more/searchHot': '/marketing/searchHot',
    '/m/more/statistics': '/data/statistics',
    '/m/more/mqLog': '/data/mqCompensationLog',
    '/m/more/tools': '/data/tools',
    '/m/more/sensitiveWord': '/setting/sensitiveWord',
    '/m/more/category': '/product/category',
    '/m/more/productProperty': '/product/ProductProperty',
    '/m/more/logistics': '/setting/logistics',
    '/m/more/prompt': '/setting/prompt',
    '/m/more/rag': '/setting/rag',
    '/m/more/signReward': '/marketing/signReward',
    '/m/more/memberLevelReward': '/marketing/memberLevelReward'
  }
  return map[mobilePath] || '/home'
}

export function switchToDesktopView(path) {
  try {
    localStorage.setItem(FORCE_KEY, 'desktop')
  } catch (e) {

  }
  window.location.assign(resolveAppUrl(path || '/home'))
}

export function switchToMobileView(path = '/m/home') {
  try {
    localStorage.setItem(FORCE_KEY, 'mobile')
  } catch (e) {

  }
  window.location.assign(resolveAppUrl(path))
}
