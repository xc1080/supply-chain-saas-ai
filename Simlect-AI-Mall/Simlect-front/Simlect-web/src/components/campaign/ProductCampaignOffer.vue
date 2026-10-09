<template>
  <section class="campaign-offer" :class="`is-${currentStatus}`" aria-label="商品限时活动">
    <div class="campaign-offer__head">
      <span class="campaign-offer__tag">{{ statusLabel }}</span>
      <RouterLink :to="{ path: '/activities', query: { product: String(campaign.productId), activityId: campaign.activityId } }">
        活动会场 <span aria-hidden="true">›</span>
      </RouterLink>
    </div>
    <h2>{{ campaign.title }}</h2>
    <div class="campaign-offer__price">
      <span class="campaign-offer__price-label">活动价</span>
      <strong><span>¥</span>{{ money(campaign.price) }}</strong>
      <span v-if="normalPrice !== undefined" class="campaign-offer__ordinary">普通售价 ¥{{ money(normalPrice) }}</span>
    </div>
    <div class="campaign-offer__details">
      <span>限量 {{ campaign.capacity }} 件</span>
      <span v-if="currentStatus !== 'ended'">{{ currentStatus === 'soldout' ? '本轮已抢完' : `剩余 ${campaign.remaining} 件` }}</span>
      <span>每人限购 {{ campaign.perOwnerLimit }} 件</span>
    </div>
    <div class="campaign-offer__foot">
      <p v-if="currentStatus === 'live' || currentStatus === 'upcoming'" class="campaign-offer__time">
        {{ currentStatus === 'upcoming' ? '距开始' : '距结束' }}
        <time role="timer">{{ countdown(campaign) }}</time>
      </p>
      <p v-else class="campaign-offer__time">{{ currentStatus === 'ended' ? '本场活动已结束' : '看看会场里的其他好物' }}</p>
      <button
        v-if="showBuy"
        type="button"
        class="campaign-offer__buy"
        :disabled="(!hasOwnOrder && !queued(campaign) && (currentStatus !== 'live' || !!error)) || !!buying"
        :aria-label="`${buyLabel}，${campaign.productName}${hasOwnOrder ? '' : '，每单 1 件'}`"
        @click="buy(campaign)"
      >{{ buyLabel }}</button>
    </div>
    <p v-if="error" class="campaign-offer__error" role="status">{{ error }}<button type="button" :disabled="loading" @click="refresh">{{ loading ? '更新中…' : '重试' }}</button></p>
    <p v-if="checkoutNotice" class="campaign-offer__time" role="status">{{ checkoutNotice }}</p>
    <span v-if="showBuy && currentStatus === 'live' && !hasOwnOrder" class="campaign-offer__quantity">每单 1 件</span>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import { useCampaigns, type Campaign } from '@/composables/useCampaigns';

const props = withDefaults(defineProps<{
  campaign: Campaign;
  ordinaryPrice?: string | number;
  showBuy?: boolean;
}>(), { showBuy: true });

const { status, countdown, buying, checkoutNotice, queued, buy, error, loading, refresh } = useCampaigns();
const currentStatus = computed(() => status(props.campaign));
const hasOwnOrder = computed(() => !!props.campaign.myOrderId && (props.campaign.myOrderStatus === 0 || (props.campaign.participationCount ?? 0) >= props.campaign.perOwnerLimit));
const normalPrice = computed(() => {
  const value = Number(props.ordinaryPrice ?? props.campaign.originalPrice);
  return Number.isFinite(value) && value > 0 ? value : undefined;
});
const statusLabel = computed(() => ({ live: '限时抢购', upcoming: '即将开抢', soldout: '已抢完', ended: '活动结束' })[currentStatus.value]);
const buyLabel = computed(() => {
  if (buying.value === props.campaign.activityId) return '正在排队…';
  if (queued(props.campaign)) return '查看排队结果';
  if (hasOwnOrder.value) return props.campaign.myOrderStatus === 0 ? '继续付款' : '查看订单';
  return ({
    live: `活动价抢购 ¥${money(props.campaign.price)}`,
    upcoming: '尚未开抢', soldout: '本轮已抢完', ended: '活动已结束'
  })[currentStatus.value];
});
const money = (value: number) => Number(value).toFixed(2);
</script>

<style scoped lang="scss">
.campaign-offer {
  padding: 18px 20px 14px;
  color: #252525;
  background: #fff2ef;
  border: 1px solid #efd8d3;
  border-radius: 8px;

  &__head { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
  &__tag { color: #a42e28; font-size: 12px; font-weight: 600; }
  &__head a { color: #555; font-size: 12px; text-decoration: none; }
  &__head a:hover { color: #a42e28; text-decoration: underline; }
  h2 { margin: 10px 0 14px; font-size: 17px; font-weight: 600; line-height: 1.4; overflow-wrap: anywhere; }
  &__price { display: flex; align-items: baseline; flex-wrap: wrap; gap: 7px 10px; }
  &__price-label { color: #a42e28; font-size: 12px; }
  &__price strong { color: #c83e36; font-size: 34px; font-weight: 700; line-height: 1.1; letter-spacing: -.7px; font-variant-numeric: tabular-nums; }
  &__price strong > span { margin-right: 3px; font-size: 19px; }
  &__ordinary { color: #666; font-size: 12px; }
  &__details { display: flex; flex-wrap: wrap; gap: 5px 16px; margin-top: 13px; color: #5c514e; font-size: 12px; line-height: 1.6; }
  &__foot { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px 16px; margin-top: 14px; padding-top: 14px; border-top: 1px solid #ecd8d3; }
  &__time { margin: 0; color: #655854; font-size: 12px; line-height: 1.5; }
  &__time time { display: inline-block; margin-left: 5px; color: #252525; font-size: 14px; font-weight: 600; font-variant-numeric: tabular-nums; }
  &__buy { min-height: 42px; padding: 9px 18px; color: #fff; background: #c83e36; border: 1px solid #c83e36; border-radius: 5px; font: inherit; font-size: 14px; font-weight: 600; cursor: pointer; }
  &__buy:hover:not(:disabled) { background: #b5352e; border-color: #b5352e; }
  &__buy:disabled { color: #756764; background: #eadbd7; border-color: #eadbd7; cursor: not-allowed; }
  &__quantity { display: block; margin-top: 6px; color: #75645f; font-size: 11px; text-align: right; }
  &__error { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 10px 0 0; color: #8c3b34; font-size: 12px; line-height: 1.5; }
  &__error button { padding: 3px 6px; color: inherit; background: transparent; border: 0; font: inherit; text-decoration: underline; text-underline-offset: 3px; cursor: pointer; }
  &__error button:disabled { opacity: .6; cursor: not-allowed; }
  a:focus-visible, button:focus-visible { outline: 2px solid #252525; outline-offset: 4px; }

  &.is-ended, &.is-soldout { background: #f7f6f5; border-color: #e4dfdc; }
}

@media (max-width: 600px) {
  .campaign-offer {
    padding: 15px;
    h2 { font-size: 16px; }
    &__price strong { font-size: 30px; }
    &__foot { margin-top: 11px; padding-top: 11px; }
  }
}
</style>
