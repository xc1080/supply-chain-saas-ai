export const orderStatusOptions = [
  { value: 0, label: '待支付' }, { value: 1, label: '待发货' },
  { value: 2, label: '已发货' }, { value: 3, label: '已收货' }, { value: 4, label: '已取消' }
]

const orderStates = {
  0: ['待支付', 'warning'], 1: ['已支付', 'primary'],
  2: ['待收货', 'primary'], 3: ['已收货', 'success'], 4: ['已取消', 'info']
}
const fulfillmentStates = {
  UNSHIPPED: ['未发货', 'info'], PARTIALLY_SHIPPED: ['部分发货', 'warning'],
  SHIPPED: ['已发货', 'success'], RETURNED: ['已退回', 'info'], SETTLED: ['无需发货', 'info']
}
const afterSalesStates = {
  REQUESTED: ['售后待审核', 'warning'], APPROVED: ['待退款', 'warning'],
  AWAITING_RETURN: ['待退货验收', 'warning'], RETURN_RECEIVED: ['退货已验收', 'primary'],
  REFUND_FAILED: ['退款失败', 'danger'], PARTIALLY_REFUNDED: ['部分退款', 'warning'],
  REFUNDED: ['已退款', 'success'], REJECTED: ['售后已拒绝', 'info']
}

export function commerceOrderStatus(order = {}) { return Number(order.orderStatus ?? order.status) }

function state(axis, value, definitions) {
  const [label, tone] = definitions[value] || ['状态待核对', 'info']
  return { axis, value, label, tone }
}

// The three business axes can coexist; fulfilment never replaces an after-sales state.
export function commerceOrderStates(order = {}) {
  const states = [state('order', commerceOrderStatus(order), orderStates)]
  if (order.fulfillmentStatus) states.push(state('fulfillment', order.fulfillmentStatus, fulfillmentStates))
  if (order.afterSalesStatus) states.push(state('afterSales', order.afterSalesStatus, afterSalesStates))
  else if (Number(order.refundedAmount) > 0) {
    const refundState = Number(order.refundedAmount) >= Number(order.totalAmount) ? 'REFUNDED' : 'PARTIALLY_REFUNDED'
    states.push(state('afterSales', refundState, afterSalesStates))
  }
  return states
}

export function shippableQuantity(item, order) {
  return Math.max(0, Number(item.shippableQuantity ?? item.unshippedRefundAvailableQuantity ?? item.unshippedQuantity
    ?? (commerceOrderStatus(order) === 1 ? item.quantity : 0)) || 0)
}

export function canShipCommerceOrder(order, capabilities = []) {
  if (!capabilities.includes('FULFILMENT')) return false
  if (Array.isArray(order.availableActions)) return order.availableActions.includes('SHIP')
  return commerceOrderStatus(order) === 1 && (order.items || []).some(item => shippableQuantity(item, order) > 0)
}
