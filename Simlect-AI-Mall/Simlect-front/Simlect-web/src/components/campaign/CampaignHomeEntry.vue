<template>
  <section v-if="featured.length" class="campaign-home-entry" :class="{ 'is-mobile': mobile, 'is-single': featured.length === 1 }" aria-labelledby="campaign-entry-title">
    <div class="entry-heading">
      <h2 id="campaign-entry-title">限时特惠</h2>
      <p>智能好物，趁现在</p>
      <RouterLink class="entry-all" to="/activities">进入会场 <span aria-hidden="true">›</span></RouterLink>
    </div>
    <div class="entry-products">
      <RouterLink
        v-for="campaign in featured"
        :key="campaign.activityId"
        class="entry-product"
        :to="{ path: `/product/${campaign.productId}`, query: { activityId: campaign.activityId } }"
      >
        <ProductImage :source="campaign.cover" :product="campaign" fit="contain" :lazy="false" class="entry-product-image" />
        <div class="entry-product-info">
          <h3>{{ campaign.productName }}</h3>
          <div class="entry-price"><strong><small>¥</small>{{ money(campaign.price) }}</strong><del v-if="hasDiscount(campaign)">¥{{ money(campaign.originalPrice) }}</del></div>
          <p class="entry-time"><span>{{ status(campaign) === 'upcoming' ? '距开始' : '距结束' }}</span><b>{{ countdown(campaign) }}</b></p>
        </div>
        <span class="entry-stock">{{ status(campaign) === 'upcoming' ? '即将开抢' : `剩 ${campaign.remaining} 件` }}</span>
      </RouterLink>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import ProductImage from '@/components/common/ProductImage.vue';
import { useCampaigns, type Campaign } from '@/composables/useCampaigns';

defineProps<{ mobile?: boolean }>();
const { activities, status, countdown } = useCampaigns();
const featured = computed(() => activities.value
  .filter(campaign => status(campaign) === 'live' || status(campaign) === 'upcoming')
  .sort((a, b) => Number(status(a) === 'upcoming') - Number(status(b) === 'upcoming'))
  .slice(0, 2));
const money = (value?: number) => Number(value).toFixed(2);
const hasDiscount = (campaign: Campaign) => Number(campaign.originalPrice) > Number(campaign.price);
</script>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.campaign-home-entry {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  width: min($content-max-width, 100%);
  box-sizing: border-box;
  margin: 16px auto 0;
  border: 1px solid #ebe5e0;
  border-top: 2px solid #c83e36;
  border-radius: 5px;
  background: #fff;
  color: #242322;
}
.entry-heading { padding: 21px 24px; border-right: 1px solid #eee9e5; }
.entry-heading h2 { margin: 0; font-size: 22px; line-height: 1.3; font-weight: 750; }
.entry-heading p { margin: 6px 0 15px; color: #76716b; font-size: 12px; }
.entry-all { display: inline-flex; align-items: center; gap: 12px; color: #b7322b; font-size: 12px; font-weight: 600; text-decoration: none; }
.entry-all span { font-size: 20px; line-height: 12px; }
.entry-products { display: flex; min-width: 0; }
.entry-product {
  display: flex;
  align-items: center;
  flex: 1 1 0;
  gap: 16px;
  min-width: 0;
  padding: 16px 22px;
  color: inherit;
  text-decoration: none;
}
.entry-product + .entry-product { border-left: 1px solid #eee9e5; }
.entry-product:hover { background: #fdfbfa; }
.entry-product-image { width: 102px !important; height: 102px !important; flex: 0 0 102px; background: #f7f6f4; }
.entry-product-info { flex: 1; min-width: 0; }
.entry-product-info h3 { margin: 0 0 7px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; line-height: 1.5; font-weight: 600; }
.entry-price { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; }
.entry-price strong { color: #c83e36; font-size: 24px; line-height: 1.2; font-weight: 750; font-variant-numeric: tabular-nums; }
.entry-price small { margin-right: 2px; font-size: 13px; }
.entry-price del { color: #8b8580; font-size: 11px; }
.entry-time { display: flex; flex-wrap: wrap; gap: 5px; margin: 9px 0 0; color: #77716a; font-size: 11px; line-height: 1.4; }
.entry-time b { color: #403a35; font-weight: 500; font-variant-numeric: tabular-nums; }
.entry-stock { align-self: flex-end; flex-shrink: 0; color: #b7322b; font-size: 11px; }
.entry-all:focus-visible, .entry-product:focus-visible { outline: 2px solid #c83e36; outline-offset: -3px; }
.is-single:not(.is-mobile) {
  .entry-product { gap: 24px; }
  .entry-product-info { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; column-gap: 24px; }
  .entry-product-info h3 { margin: 0; font-size: 17px; }
  .entry-price { grid-column: 2; grid-row: 1 / 3; }
  .entry-price strong { font-size: 30px; }
  .entry-time { margin-top: 7px; }
}

.is-mobile {
  display: block;
  width: auto;
  margin: 12px $app-page-gutter 0;
  .entry-heading { display: flex; align-items: center; gap: 10px; padding: 13px 14px 5px; border-right: 0; }
  .entry-heading h2 { font-size: 17px; }
  .entry-heading p { display: none; }
  .entry-all { margin-left: auto; }
  .entry-products { flex-wrap: wrap; }
  .entry-product { flex-basis: 100%; gap: 12px; padding: 12px 14px; }
  .entry-product + .entry-product { border-left: 0; border-top: 1px solid #eee9e5; }
  .entry-product-image { width: 76px !important; height: 76px !important; flex-basis: 76px; }
  .entry-price strong { font-size: 23px; }
  .entry-time { margin-top: 5px; }
}

@media (max-width: 980px) {
  .campaign-home-entry:not(.is-mobile) { grid-template-columns: 150px minmax(0, 1fr); }
  .campaign-home-entry:not(.is-mobile) .entry-heading { padding: 20px 16px; }
  .campaign-home-entry:not(.is-mobile) .entry-product { padding: 16px 12px; gap: 10px; }
  .campaign-home-entry:not(.is-mobile) .entry-product-image { width: 76px !important; height: 76px !important; flex-basis: 76px; }
  .campaign-home-entry:not(.is-mobile) .entry-stock { display: none; }
}
</style>
