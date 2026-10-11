<template>
  <section class="living-scene" :class="{ 'is-mobile': mobile }" aria-label="智能家居精选">
    <div class="living-scene__copy">
      <h1>让家，<br />懂你的生活。</h1>
      <p>从一盏灯开始，让日常更自在。</p>
      <div class="living-scene__actions">
        <button type="button" class="living-scene__browse" @click="router.push('/search')">浏览全部商品 <el-icon><ArrowRight /></el-icon></button>
        <button type="button" class="living-scene__ask" @click="openAgent()">帮我选</button>
      </div>
    </div>
    <button v-if="product" type="button" class="living-scene__product" :aria-label="`查看${product.productName}`" @click="$emit('select', product)">
      <div class="living-scene__light" aria-hidden="true" />
      <div class="living-scene__image"><ProductImage :product="product" :lazy="false" fit="contain" width="100%" height="100%" /></div>
      <span class="living-scene__caption"><span>{{ product.productName }}</span><strong>¥{{ displayPrice }}</strong></span>
    </button>
    <div v-else class="living-scene__quiet" aria-hidden="true"><div class="living-scene__light" /></div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowRight } from '@element-plus/icons-vue';
import ProductImage from '@/components/common/ProductImage.vue';
import { useOpenAgent } from '@/composables/useOpenAgent';

const props = defineProps<{ product?: Record<string, any>; mobile?: boolean }>();
defineEmits<{ select: [Record<string, any>] }>();
const router = useRouter();
const { openAgent } = useOpenAgent();
const displayPrice = computed(() => {
  const value = props.product?.minPrice ?? props.product?.price ?? props.product?.salePrice;
  return value != null ? Number(value).toFixed(2) : '--';
});
</script>
