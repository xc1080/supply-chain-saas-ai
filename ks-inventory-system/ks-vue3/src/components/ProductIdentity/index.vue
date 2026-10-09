<template>
  <div class="product-identity">
    <img v-if="identified" :key="image" :src="image" :alt="`${name || '商品'}缩略图`" width="40" height="40" loading="lazy" @error="handleProductImageError($event, productData)" />
    <div class="product-identity-content"><slot>{{ name || '—' }}</slot></div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { getProductImage, handleProductImageError } from '@/utils/productMedia'

const props = defineProps({ product: { type: Object, default: () => ({}) } })
const productData = computed(() => {
  const data = { ...props.product, ...props.product.product }
  return { ...data, productCode: data.productCode || data.commodityCode || data.code,
    productName: data.productName || data.commodityName || data.name }
})
const name = computed(() => productData.value.productName)
const identified = computed(() => Boolean(name.value || productData.value.productCode))
const image = computed(() => getProductImage(productData.value))
</script>

<style scoped>
.product-identity { display: flex; align-items: center; gap: 10px; min-width: 0; text-align: left; }
.product-identity img { flex: 0 0 40px; width: 40px; height: 40px; object-fit: contain; border: 1px solid var(--sc-border); border-radius: 6px; background: var(--sc-surface-soft); }
.product-identity-content { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.product-identity-content :deep(.el-select) { width: 100%; }
</style>
