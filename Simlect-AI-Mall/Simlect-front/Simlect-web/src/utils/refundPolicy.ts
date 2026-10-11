export const shippingRefundPolicyLabel = (policy: unknown): string => {
  if (policy === 'UNSHIPPED_ONLY') return '未发货退运费，已发货不退';
  if (policy === 'PROPORTIONAL') return '按退货件数退分摊运费';
  if (policy === 'LEGACY_ORIGINAL_PRICE') return '按历史商品原价退款';
  return '未提供政策';
};
