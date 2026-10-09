<template>
  <RouterLink
    v-if="campaign"
    class="campaign-product-tag"
    :class="{ 'is-unavailable': campaignStatus === 'soldout' }"
    :to="{ path: `/product/${campaign.productId}`, query: { activityId: campaign.activityId } }"
    :aria-label="`${campaign.productName}，${campaignStatus === 'upcoming' ? '活动预告' : '限时特惠'}，活动价 ${price} 元`"
    @click.stop
    @keydown.stop
  >
    <span>{{ campaignStatus === 'upcoming' ? '活动预告' : '限时特惠' }}</span>
    <strong>¥{{ price }}</strong>
    <span v-if="campaignStatus === 'soldout'" class="tag-state">已抢完</span>
    <svg v-else viewBox="0 0 16 16" width="12" height="12" aria-hidden="true"><path d="m6 3 5 5-5 5" fill="none" stroke="currentColor" stroke-width="1.5" /></svg>
  </RouterLink>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import { useCampaigns } from '@/composables/useCampaigns';

const props = defineProps<{ productId?: string | number }>();
const { campaignFor, status } = useCampaigns();
const campaign = computed(() => {
  const item = props.productId == null ? undefined : campaignFor(props.productId);
  return item && status(item) !== 'ended' ? item : undefined;
});
const campaignStatus = computed(() => campaign.value ? status(campaign.value) : undefined);
const price = computed(() => Number(campaign.value?.price ?? 0).toFixed(2));
</script>

<style scoped>
.campaign-product-tag {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  max-width: 100%;
  margin-top: 7px;
  padding: 3px 6px;
  border: 1px solid #efd3cf;
  border-radius: 3px;
  background: #fff8f7;
  color: #b7322b;
  font-size: 11px;
  line-height: 1.4;
  text-decoration: none;
}
.campaign-product-tag strong { font-size: 12px; font-weight: 700; }
.campaign-product-tag:hover { border-color: #c83e36; background: #fff1ef; }
.campaign-product-tag:focus-visible { outline: 2px solid #c83e36; outline-offset: 3px; }
.campaign-product-tag.is-unavailable { color: #77736f; background: #f7f6f5; border-color: #e5e2de; }
.tag-state { border-left: 1px solid #d8d3cd; padding-left: 4px; }
</style>
