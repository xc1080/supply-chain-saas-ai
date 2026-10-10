<template>
  <dl class="order-money">
    <div v-for="field in fields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ money(amounts[field.key]) }}</dd></div>
  </dl>
</template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ order: { type: Object, required: true } })
const fields = [{ key: 'originalAmount', label: '商品原价' }, { key: 'discountAmount', label: '优惠' }, { key: 'shippingAmount', label: '运费' }, { key: 'payableAmount', label: '应付' }, { key: 'paidAmount', label: '实付' }, { key: 'refundedAmount', label: '已退' }, { key: 'refundableAmount', label: '可退' }]
const amounts = computed(() => ({ originalAmount: props.order.originalAmount ?? props.order.totalAmount, discountAmount: 0, shippingAmount: 0, payableAmount: props.order.totalAmount, ...props.order, ...props.order.amountBreakdown }))
const money = value => value == null ? '—' : new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(Number(value))
</script>
<style scoped>
.order-money{display:flex;flex-wrap:wrap;gap:18px 26px;margin:20px 0}.order-money div{min-width:90px}.order-money dt{font-size:12px;color:var(--sc-muted);margin-bottom:6px}.order-money dd{margin:0;font-weight:600;font-size:15px}
</style>
