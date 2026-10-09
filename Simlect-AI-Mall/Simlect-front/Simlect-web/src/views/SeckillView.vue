<template>
  <div class="campaign-page ignore">
    <nav class="campaign-breadcrumb" aria-label="当前位置"><RouterLink to="/">首页</RouterLink><span>/</span><span>活动会场</span></nav>
    <header class="campaign-heading">
      <div><p class="campaign-kicker"><span aria-hidden="true">ϟ</span> 简选限时特惠</p><h1>智能好物，限时好价</h1></div>
      <RouterLink to="/orders" class="order-link">我的订单 <span aria-hidden="true">↗</span></RouterLink>
    </header>
    <div v-if="error" class="campaign-error" role="status"><span>{{ error }}</span><button type="button" @click="refresh">重新加载</button></div>
    <p v-if="checkoutNotice" class="campaign-queue-note" role="status">{{ checkoutNotice }}</p>
    <div class="campaign-controls">
      <nav class="campaign-tabs" aria-label="活动状态">
        <button v-for="tab in tabs" :key="tab.key" type="button" :aria-pressed="selected === tab.key" :class="{active: selected === tab.key}" @click="selected = tab.key">{{ tab.label }}<span>{{ count(tab.key) }}</span></button>
      </nav>
      <button class="refresh-link" type="button" :disabled="loading" @click="refresh">{{ loading ? '更新中…' : '刷新活动' }}</button>
    </div>
    <div v-if="loading && !activities.length" class="campaign-skeleton" aria-label="正在加载活动" role="status"><div></div><div></div></div>
    <template v-else-if="featured">
      <article class="campaign-feature" :aria-label="featured.title">
        <RouterLink class="campaign-visual" :to="productLink(featured)" :aria-label="`查看${featured.productName}商品详情`">
          <span class="visual-label">{{ featured.categoryName || '智能家居' }}</span>
          <ProductImage :product="featured" :lazy="false" fit="contain" width="100%" height="100%" />
          <span v-if="featured.spec" class="visual-spec">{{ featured.spec }}</span>
        </RouterLink>
        <div class="campaign-offer">
          <div class="campaign-session"><span class="session-state"><i aria-hidden="true"></i>{{ stateLabel(featured) }}</span><span>{{ scheduleLabel(featured) }}</span></div>
          <p class="offer-title">{{ featured.title }}</p>
          <RouterLink :to="productLink(featured)" class="offer-name"><h2>{{ featured.productName }}</h2></RouterLink>
          <p v-if="featured.description" class="offer-description">{{ description(featured) }}</p>
          <div class="offer-price"><span class="price-caption">活动价</span><strong><small>¥</small>{{ money(featured.price) }}</strong><span v-if="discount(featured)" class="price-saving">省 ¥{{ money(discount(featured)) }}</span></div>
          <p v-if="featured.originalPrice" class="regular-price">普通售价 <del>¥{{ money(featured.originalPrice) }}</del></p>
          <div class="offer-countdown"><span>{{ status(featured) === 'upcoming' ? '距开始' : '距结束' }}</span><time :aria-label="`${status(featured) === 'upcoming' ? '距开始' : '距结束'} ${countdown(featured)}`"><b v-for="(part, index) in countdown(featured).split(':')" :key="index">{{ part }}</b></time></div>
          <div class="offer-allocation"><span>活动剩余 <strong>{{ featured.remaining }}</strong> 件</span><span>每人限 {{ featured.perOwnerLimit }} 件</span></div>
          <div class="allocation-track" role="meter" aria-label="剩余活动配额" :aria-valuenow="Number(featured.remaining)" :aria-valuemin="0" :aria-valuemax="Number(featured.capacity)"><span :style="{width: `${quota(featured)}%`}"></span></div>
          <button class="campaign-buy" type="button" :disabled="disabled(featured)" @click="buy(featured)">{{ buttonLabel(featured) }}<span v-if="status(featured) === 'live' && !participated(featured) && !buying" aria-hidden="true"> →</span></button>
          <div class="offer-links"><RouterLink :to="productLink(featured)">规格与商品详情</RouterLink><RouterLink to="/orders">查看我的订单</RouterLink></div>
        </div>
      </article>
      <div v-if="remaining.length" class="campaign-more">
        <article v-for="item in remaining" :key="item.activityId" class="campaign-card">
          <RouterLink :to="productLink(item)" class="card-image"><ProductImage :product="item" fit="contain" width="100%" height="100%" /><span>{{ stateLabel(item) }}</span></RouterLink>
          <div class="card-body"><p>{{ item.title }}</p><RouterLink :to="productLink(item)"><h2>{{ item.productName }}</h2></RouterLink><div class="card-price"><strong>¥{{ money(item.price) }}</strong><del v-if="item.originalPrice && discount(item)">¥{{ money(item.originalPrice) }}</del></div><p class="card-stock">剩余 {{ item.remaining }} 件 <span>{{ status(item) === 'upcoming' ? '距开始' : '距结束' }} {{ countdown(item) }}</span></p><button type="button" class="campaign-buy" :disabled="disabled(item)" @click="buy(item)">{{ buttonLabel(item) }}</button></div>
        </article>
      </div>
    </template>
    <section v-else-if="!error" class="campaign-empty">
      <h2>{{ selected === 'upcoming' ? '暂无即将开始的活动' : selected === 'soldout' ? '暂无已抢完的活动' : '新活动准备中' }}</h2>
      <p>{{ selected === 'all' ? '先逛逛商城，发现适合家里的智能好物。' : '可以切换其他场次，或去商城看看。' }}</p><RouterLink to="/">逛逛商城</RouterLink>
    </section>
    <details v-if="activities.length" class="campaign-rules"><summary>活动规则</summary><ul><li>活动价仅通过活动入口下单生效，每次购买 1 件；购物车商品按普通售价结算。</li><li>活动数量与每人限购以商品展示为准，取消或付款超时后释放配额。</li><li>下单后请在 15 分钟内付款。本地演示使用支付沙箱，不收取真实款项。</li></ul></details>
  </div>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import ProductImage from '@/components/common/ProductImage.vue';
import { useCampaigns, type Campaign } from '@/composables/useCampaigns';
const route = useRoute();
const { activities, loading, error, buying, checkoutNotice, queued, refresh, status, countdown, buy } = useCampaigns();
type Filter = 'all' | 'live' | 'upcoming' | 'soldout';
const selected = ref<Filter>('all');
const tabs: {key: Filter; label: string}[] = [{key:'all',label:'全部活动'},{key:'live',label:'正在进行'},{key:'upcoming',label:'即将开始'},{key:'soldout',label:'已抢完'}];
const current = computed(() => activities.value.filter(c => status(c) !== 'ended'));
const count = (key: Filter) => key === 'all' ? current.value.length : current.value.filter(c => status(c) === key).length;
const filtered = computed(() => current.value.filter(c => selected.value === 'all' || status(c) === selected.value).slice().sort((a,b) => {
  if (route.query.activityId) {
    const target = String(route.query.activityId);
    if (a.activityId === target && b.activityId !== target) return -1;
    if (b.activityId === target && a.activityId !== target) return 1;
  }
  if (route.query.product) {
    const target = String(route.query.product);
    if (String(a.productId) === target && String(b.productId) !== target) return -1;
    if (String(b.productId) === target && String(a.productId) !== target) return 1;
  }
  const rank = { live:0, upcoming:1, soldout:2, ended:3 };
  return rank[status(a)] - rank[status(b)] || new Date(a.startsAt).getTime() - new Date(b.startsAt).getTime();
}));
const featured = computed(() => filtered.value[0]);
const remaining = computed(() => filtered.value.slice(1));
const money = (n: number) => Number(n).toFixed(2);
const discount = (c: Campaign) => Math.max(0, Number(c.originalPrice || 0) - Number(c.price));
const quota = (c: Campaign) => c.capacity > 0 ? Math.min(100,Math.max(0, c.remaining / c.capacity * 100)) : 0;
const participated = (c: Campaign) => !!c.myOrderId && (c.myOrderStatus === 0 || (c.participationCount || 0) >= c.perOwnerLimit);
const stateLabel = (c: Campaign) => ({live:'限时抢购中',upcoming:'即将开始',soldout:'本场已抢完',ended:'活动已结束'})[status(c)];
const disabled = (c: Campaign) => !!buying.value || (!queued(c) && (!!error.value || (!participated(c) && status(c) !== 'live')));
const buttonLabel = (c: Campaign) => buying.value === c.activityId ? '正在排队…' : queued(c) ? '查看排队结果' : participated(c) ? c.myOrderStatus === 0 ? '继续付款' : '查看我的订单' : status(c) === 'live' ? '活动价抢购' : stateLabel(c);
const productLink = (c: Campaign) => ({path:'/product/'+c.productId,query:{activityId:c.activityId}});
const description = (c: Campaign) => String(c.description || '').replace(/^演示商品[；;，,]?\s*/, '').replace(/演示商品/g,'').replace(/[；;]$/,'');
const scheduleLabel = (c: Campaign) => {
  const value = status(c) === 'upcoming' ? c.startsAt : c.endsAt;
  const date = new Date(value.replace(' ','T'));
  return `${date.getMonth()+1}月${date.getDate()}日 ${String(date.getHours()).padStart(2,'0')}:${String(date.getMinutes()).padStart(2,'0')} ${status(c) === 'upcoming' ? '开抢' : '截止'}`;
};
</script>
<style scoped>
.campaign-page{--rush:#c83e36;--rush-soft:#fff2ef;max-width:1200px;margin:0 auto;padding:8px 16px 36px;color:#252729;font-family:"PingFang SC","Microsoft YaHei",sans-serif}
.campaign-queue-note{padding:12px 16px;margin:0 0 18px;color:#755043;background:#fff5ef;border-radius:6px;font-size:13px;line-height:1.6}
.campaign-page a{text-decoration:none;color:inherit}.campaign-page button{font:inherit;cursor:pointer}.campaign-page button:disabled{cursor:default}.campaign-page :is(a,button,summary):focus-visible{outline:3px solid #c83e36;outline-offset:4px}
.campaign-breadcrumb{display:flex;gap:12px;font-size:12px;color:#74777b;margin:10px 0 28px}.campaign-breadcrumb a:hover{color:var(--rush)}
.campaign-heading{display:flex;justify-content:space-between;align-items:end;margin-bottom:32px;gap:20px}.campaign-kicker{display:flex;align-items:center;gap:7px;color:var(--rush);font-weight:600;font-size:15px;margin:0 0 10px}.campaign-kicker>span{font-size:23px;line-height:1}.campaign-heading h1{font-size:36px;letter-spacing:-1px;line-height:1.3;margin:0;font-weight:750}.order-link{font-size:13px;color:#666!important;white-space:nowrap;padding-bottom:5px}
.campaign-controls{display:flex;justify-content:space-between;gap:12px;align-items:center;border-bottom:1px solid #dedfe1;margin-bottom:24px}.campaign-tabs{display:flex;gap:30px;overflow:auto}.campaign-tabs button{border:0;border-bottom:3px solid transparent;background:none;white-space:nowrap;color:#777;padding:14px 0;font-size:15px;font-weight:600}.campaign-tabs button>span{font-size:11px;background:#e8e9eb;padding:2px 6px;border-radius:4px;margin-left:6px;font-weight:400}.campaign-tabs button.active{color:var(--rush);border-bottom-color:var(--rush)}.campaign-tabs button.active>span{background:#f9e1dc;color:var(--rush)}.refresh-link{border:0;background:none;font-size:12px!important;color:#74777b;white-space:nowrap}
.campaign-feature{display:grid;grid-template-columns:1.05fr 1fr;background:#fff;border-radius:16px;border:1px solid #e5e5e6;overflow:hidden}.campaign-visual{position:relative;display:block;min-height:480px;background:#edf1f5;padding:32px}.campaign-visual :deep(.product-image){background:transparent;border-radius:0;max-height:480px}.visual-label,.visual-spec{position:absolute;z-index:1;font-size:12px;color:#56616a}.visual-label{left:24px;top:24px;background:rgba(255,255,255,.85);padding:7px 12px;border-radius:4px}.visual-spec{bottom:24px;left:24px;letter-spacing:1px}
.campaign-offer{padding:32px 36px;align-self:center;min-width:0}.campaign-session{display:flex;justify-content:space-between;align-items:center;gap:10px;font-size:12px;color:#777;margin-bottom:28px}.session-state{color:var(--rush);font-weight:600;display:flex;align-items:center;gap:6px}.session-state i{width:6px;height:6px;background:currentColor;border-radius:50%}.offer-title{font-size:13px;color:#75787b;margin:0 0 9px}.offer-name h2{font-size:28px;margin:0 0 12px;line-height:1.4;font-weight:650}.offer-description{font-size:13px;line-height:1.8;color:#6e7378;margin:0 0 22px;max-width:38em}.offer-price{display:flex;align-items:baseline;gap:10px;flex-wrap:wrap;color:var(--rush)}.price-caption{font-size:12px}.offer-price>strong{font-size:49px;font-weight:700;letter-spacing:-1.5px;font-variant-numeric:tabular-nums;line-height:1.25}.offer-price small{font-size:24px;margin-right:3px;letter-spacing:0}.price-saving{background:var(--rush-soft);font-size:12px;padding:3px 7px;border-radius:3px}.regular-price{font-size:12px;color:#8c8e91;margin:5px 0 24px}.regular-price del{margin-left:6px}
.offer-countdown{display:flex;align-items:center;gap:12px;margin:0 0 18px;font-size:12px;color:#71767a}.offer-countdown time{display:flex;gap:7px;font-variant-numeric:tabular-nums}.offer-countdown b{position:relative;display:inline-grid;place-items:center;min-width:30px;height:28px;padding:0 4px;border-radius:4px;background:#292b2f;color:#fff;font-size:13px}.offer-countdown b+b:before{content:':';position:absolute;left:-5px;color:#6a6b6e}
.offer-allocation{display:flex;justify-content:space-between;font-size:12px;color:#74777b;gap:10px}.offer-allocation strong{font-weight:650;color:var(--rush)}.allocation-track{height:4px;border-radius:5px;background:#f3e8e5;margin:9px 0 20px;overflow:hidden}.allocation-track>span{display:block;height:100%;background:var(--rush);border-radius:5px}.campaign-buy{width:100%;border:0;background:var(--rush);color:#fff;border-radius:7px;min-height:47px;padding:12px 18px;font-size:15px!important;font-weight:600!important;transition:background .15s}.campaign-buy:not(:disabled):hover{background:#ad2d27}.campaign-buy:disabled{background:#eee;color:#8b8d91}.campaign-buy>span{float:right}.offer-links{display:flex;justify-content:center;gap:24px;margin-top:14px;font-size:12px;color:#73787c}.offer-links a:hover{text-decoration:underline}
.campaign-more{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:20px;margin-top:24px}.campaign-card{border:1px solid #e5e5e6;border-radius:12px;overflow:hidden;background:#fff}.card-image{display:block;position:relative;height:230px;background:#edf1f5;padding:20px}.card-image :deep(.product-image){background:transparent}.card-image>span{position:absolute;top:14px;left:14px;background:#fff;padding:6px 8px;color:var(--rush);font-size:11px;border-radius:3px}.card-body{padding:22px}.card-body>p:first-child{font-size:12px;color:#777;margin:0 0 8px}.card-body h2{font-size:18px;margin:0 0 13px}.card-price{display:flex;align-items:baseline;gap:10px}.card-price strong{font-size:26px;color:var(--rush)}.card-price del{font-size:12px;color:#929292}.card-stock{display:flex;justify-content:space-between;gap:6px;font-size:11px;color:#74777b;margin:12px 0 20px}.campaign-rules{margin-top:26px;color:#777;font-size:12px}.campaign-rules summary{width:fit-content;cursor:pointer;padding:8px 0}.campaign-rules ul{padding-left:18px;line-height:2;max-width:70em}
.campaign-error{display:flex;justify-content:space-between;align-items:center;gap:12px;background:#fff2ef;border:1px solid #f2d2cc;padding:14px 18px;border-radius:6px;font-size:13px;margin-bottom:18px}.campaign-error button{background:none;border:0;color:var(--rush);text-decoration:underline}.campaign-empty{text-align:center;background:#fff;border-radius:12px;padding:80px 20px}.campaign-empty h2{font-size:22px;font-weight:600}.campaign-empty p{font-size:14px;color:#777;margin-bottom:28px}.campaign-empty a{display:inline-block;background:#292b2f;color:#fff;padding:11px 24px;border-radius:6px;font-size:13px}.campaign-skeleton{display:grid;grid-template-columns:1fr 1fr;gap:30px;background:#fff;padding:30px;border-radius:12px}.campaign-skeleton>div{height:400px;background:#eef0f2;border-radius:8px}
@media(max-width:800px){.campaign-page{padding:0 8px 28px}.campaign-heading h1{font-size:28px}.campaign-heading{align-items:start;margin-bottom:18px}.campaign-feature{grid-template-columns:1fr 1fr}.campaign-visual{min-height:420px;padding:15px}.campaign-offer{padding:24px 20px}.campaign-session{flex-wrap:wrap;margin-bottom:20px}.offer-name h2{font-size:23px}.offer-price>strong{font-size:39px}.campaign-more{grid-template-columns:repeat(2,minmax(0,1fr))}.campaign-tabs{gap:20px}}
@media(max-width:560px){.campaign-breadcrumb{display:none}.campaign-page{padding:14px 12px 24px}.campaign-kicker{font-size:12px;margin-bottom:8px}.campaign-heading h1{font-size:25px}.order-link{font-size:11px;margin-top:5px}.campaign-controls{gap:6px;margin-bottom:16px}.campaign-tabs{gap:18px}.campaign-tabs button{font-size:12px;padding:13px 0}.campaign-tabs button>span{font-size:10px;margin-left:4px;padding:1px 4px}.refresh-link{display:none}.campaign-feature{grid-template-columns:1fr;border-radius:12px}.campaign-visual{min-height:0;height:270px;padding:10px 30px}.visual-label{left:16px;top:16px;font-size:11px}.visual-spec{bottom:14px;left:16px;font-size:11px}.campaign-offer{padding:24px}.campaign-session{margin-bottom:20px}.offer-name h2{font-size:24px}.offer-description{margin-bottom:14px}.offer-price>strong{font-size:44px}.regular-price{margin-bottom:20px}.campaign-more{grid-template-columns:1fr;gap:16px}.campaign-card{display:grid;grid-template-columns:36% 1fr}.card-image{height:auto;padding:8px}.card-image>span{font-size:10px;padding:4px;left:8px;top:8px}.card-body{padding:16px}.card-body h2{font-size:16px}.card-stock{display:block;line-height:1.8;margin:10px 0}.card-stock>span{display:block}.card-price strong{font-size:24px}.campaign-buy{font-size:14px!important}.campaign-empty{padding:60px 16px}.campaign-skeleton{grid-template-columns:1fr}.campaign-skeleton>div{height:240px}}
@media(prefers-reduced-motion:reduce){.campaign-buy{transition:none}}
</style>
