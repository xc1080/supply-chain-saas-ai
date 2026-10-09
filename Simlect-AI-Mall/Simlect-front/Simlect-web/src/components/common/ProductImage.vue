<template>
  <el-image
    :src="src"
    :alt="imageAlt"
    :fit="fit"
    :lazy="lazy"
    class="product-image"
    :class="{ 'is-dense': dense }"
    :style="sizeStyle"
    @error="advanceFallback"
  >
    <template #placeholder>
      <div class="img-placeholder" aria-hidden="true"><el-icon :size="iconSize"><Picture /></el-icon></div>
    </template>
    <template #error>
      <div class="img-placeholder" role="img" :aria-label="`${imageAlt}，图片暂不可用`">
        <el-icon :size="iconSize" aria-hidden="true"><Picture /></el-icon>
        <span v-if="!dense">暂无图片</span>
      </div>
    </template>
  </el-image>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Picture } from '@element-plus/icons-vue';
import { pickProductCover, resolveImageUrl } from '@/utils/image';
import { demoProductCover, PRODUCT_IMAGE_FALLBACK } from '@/utils/demoMedia';

const props = withDefaults(
  defineProps<{
    source?: string | null;
    product?: Record<string, any>;
    alt?: string;
    width?: number | string;
    height?: number | string;
    fit?: 'cover' | 'contain' | 'fill';
    lazy?: boolean;
    useThumbnail?: boolean;
    dense?: boolean;
  }>(),
  { fit: 'cover', lazy: true, useThumbnail: true, dense: false }
);

const imageChoices = computed(() => {
  const raw = props.source ?? (props.product ? pickProductCover(props.product) : '');
  return [...new Set([
    resolveImageUrl(raw, { useThumbnail: props.useThumbnail }),
    resolveImageUrl(raw, { useThumbnail: false }),
    demoProductCover(props.product),
    PRODUCT_IMAGE_FALLBACK
  ].filter(Boolean))];
});
const attempt = ref(0);
const src = computed(() => imageChoices.value[attempt.value] ?? PRODUCT_IMAGE_FALLBACK);
const imageAlt = computed(() => {
  if (props.alt) return props.alt;
  const name = props.product?.productName ?? props.product?.name ?? '商品';
  return `${name}${/\/(?:demo-media|media\/demo)\//.test(src.value) ? '，商品示意图' : '图片'}`;
});
watch(() => imageChoices.value.join('|'), () => { attempt.value = 0; });
function advanceFallback() {
  if (attempt.value < imageChoices.value.length - 1) attempt.value += 1;
}

const toCssSize = (val?: number | string) => {
  if (val == null || val === '') return undefined;
  if (typeof val === 'number' && Number.isFinite(val)) return `${val}px`;
  const text = String(val).trim();
  if (/^\d+(\.\d+)?$/.test(text)) return `${text}px`;
  return text;
};
const sizeStyle = computed(() => ({
  width: toCssSize(props.width) ?? '100%',
  height: toCssSize(props.height) ?? '100%'
}));
const iconSize = computed(() => {
  const w = Number(props.width);
  return Number.isFinite(w) && w > 0 ? Math.min(32, Math.max(20, Math.floor(w / 3))) : 28;
});
</script>

<style scoped lang="scss">
@use '@/styles/variables' as *;
.product-image {
  display: block;
  border-radius: $radius-sm;
  overflow: hidden;
  background: #edf2f7;
  :deep(.el-image__inner) { width: 100%; height: 100%; max-width: 100%; max-height: 100%; }
}
.img-placeholder {
  width: 100%;
  height: 100%;
  min-height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #6a7f94;
  background: #edf2f7;
  font-size: 12px;
}
.product-image.is-dense .img-placeholder { min-height: 0; }
</style>
