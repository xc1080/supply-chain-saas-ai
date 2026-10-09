
export function isCouponOrder(order) {
  return String(order?.payScene) === '2'
}

export function isCouponOrderItem(item) {
  return item?.propertyValueIdHash === 'coupon_rush'
}
