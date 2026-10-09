<template>
  <div class="product-stock-summary">
    <span>账面库存 <strong>{{ product?.bookStock ?? '--' }}</strong></span>
    <span>已预留 <strong>{{ product?.reservedStock ?? '--' }}</strong></span>
  </div>
</template>

<script setup lang="ts">
defineProps<{ product?: Record<string, any> | null }>();
</script>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.product-stock-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  margin-top: 10px;
  color: $color-text-secondary;
  font-size: 12px;
  line-height: 1.6;

  strong { color: $color-text-body; font-weight: 500; }
}
</style>
