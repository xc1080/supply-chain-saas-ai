export function demoOrderNote(order: Record<string, any>): string {
  if (order.fulfillmentStatus === 'PARTIALLY_SHIPPED') return '商品正在分次发货，已发和待发数量见商品明细。';
  if (Number(order.refundedAmount) > 0 && Number(order.refundedAmount) < Number(order.amount)) return '部分商品已退款，其余商品继续履约。';
  if (order.afterSalesStatus && order.afterSalesStatus !== 'REJECTED') return ({REQUESTED:'售后申请已提交，等待商家审核。',APPROVED:'审核通过，等待退款处理。',AWAITING_RETURN:'等待商家验收申请退回的商品。',RETURN_RECEIVED:'退货已验收，等待退款。',REFUNDED:'退款已完成，详情见售后记录。'} as Record<string,string>)[order.afterSalesStatus] || '售后处理中。';
  if (order.legacy) return Number(order.orderStatus) === 4
    ? '历史演示订单已取消。该订单未接入库存预留和支付。'
    : '历史演示订单：未接入库存预留与支付，不能继续付款。可重新选购创建新订单。';
  switch (Number(order.orderStatus)) {
    case 0: return '库存已预留，请完成沙箱支付。';
    case 1: return '付款成功，等待发货。';
    case 2: return '已登记模拟物流，可查看测试运单。';
    case 3: return '订单已完成。';
    case 4: return order.closeReason === 'PAYMENT_TIMEOUT' ? '付款超时，订单已关闭，预留库存已释放。' : '订单已取消，预留库存已释放。';
    default: return '本地支付沙箱订单，不扣真钱。';
  }
}
