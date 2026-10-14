<template>
  <span class="order-states">
    <el-tag v-for="state in states" :key="state.axis" :type="state.tone" size="small" :title="axisLabels[state.axis] + '：' + state.label" :aria-label="axisLabels[state.axis] + '：' + state.label">{{ state.label }}</el-tag>
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { commerceOrderStates } from '@/utils/commerceOrderState.mjs'

const props = defineProps({ order: { type: Object, required: true }, axis: { type: String, default: '' } })
const axisLabels = { order: '订单', fulfillment: '履约', afterSales: '售后' }
const states = computed(() => commerceOrderStates(props.order).filter(state => !props.axis || state.axis === props.axis))
</script>

<style scoped>
.order-states { display: inline-flex; flex-wrap: wrap; align-items: center; gap: 5px; }
</style>
