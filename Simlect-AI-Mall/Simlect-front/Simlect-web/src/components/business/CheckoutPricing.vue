<template>
  <div class="checkout-pricing" v-loading="loading">
    <label :for="selectId">优惠活动</label>
    <el-select :id="selectId" :model-value="promotionId" clearable placeholder="不使用优惠" :disabled="disabled" @update:model-value="selectPromotion">
      <el-option v-for="promotion in promotions" :key="promotion.promotionId" :value="String(promotion.promotionId)" :label="promotion.title" />
    </el-select>
    <div v-if="quote" class="pricing-amounts"><p><span>商品原价</span><strong>¥{{ money(quote.originalAmount) }}</strong></p><p><span>优惠</span><strong>-¥{{ money(quote.discountAmount) }}</strong></p><p><span>运费</span><strong>¥{{ money(quote.shippingAmount) }}</strong></p><p><span>应付</span><strong>¥{{ money(quote.payableAmount) }}</strong></p></div>
    <p v-if="error" role="alert">{{ error }} <el-button text type="primary" @click="emit('retry')">重试</el-button></p>
  </div>
</template>
<script setup lang="ts">
import { useId } from 'vue';
withDefaults(defineProps<{ quote: Record<string, any> | null; promotions: Record<string, any>[]; promotionId: string; loading: boolean; error: string; disabled?: boolean }>(), { disabled: false });
const emit = defineEmits<{ 'update:promotionId': [value: string]; retry: [] }>();
const selectPromotion = (value: unknown) => emit('update:promotionId', value == null ? '' : String(value));
const selectId = useId();
const money = (value: unknown) => Number(value || 0).toFixed(2);
</script>
<style scoped>
.checkout-pricing>label{display:block;font-size:14px;margin-bottom:10px}.checkout-pricing>.el-select{width:100%}.pricing-amounts{margin-top:18px}.pricing-amounts p{display:flex;justify-content:space-between;gap:16px;font-size:14px;margin:10px 0}.pricing-amounts strong{font-weight:500}.pricing-amounts p:last-child strong{font-weight:700}
</style>
