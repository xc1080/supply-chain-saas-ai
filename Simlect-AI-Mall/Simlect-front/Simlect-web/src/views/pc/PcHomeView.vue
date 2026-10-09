<!-- [zh] SFC 区块开始（单文件组件 template/script/style 三段） -->
<template>
  <!-- [zh] 开始标签 `<div>` -->
  <div class="sl-home-page ignore">
    <!-- [zh] 开始标签 `<PcSimlectHomeScreen>` -->
    <PcSimlectHomeScreen :categories="rootCategories" :hot-products="hotProducts" />

    <CampaignHomeEntry v-if="DEMO_MODE" />

    <!-- [zh] 开始标签 `<section>` -->
    <section class="sl-home-feed">
      <!-- [zh] 开始标签 `<h2>` -->
      <h2 class="sl-feed-title">{{ DEMO_MODE ? '智能家居精选' : '猜你喜欢' }}</h2>
      <!-- [zh] 开始标签 `<el-skeleton>` -->
      <el-skeleton :loading="loading" animated :count="1">
        <!-- [zh] SFC 区块开始（单文件组件 template/script/style 三段） -->
        <template #template>
          <!-- [zh] 开始标签 `<div>` -->
          <div class="sl-feed-grid">
            <!-- [zh] 开始标签 `<el-skeleton-item>` -->
            <el-skeleton-item v-for="n in 10" :key="n" variant="image" style="aspect-ratio: 1" />
          <!-- [zh] 闭合标签 `</div>` -->
          </div>
        </template>
        <div v-if="displayProducts.length" class="sl-feed-grid">
          <article
            v-for="item in displayProducts"
            :key="`${item.product.productId}-${item.displayIndex}`"
            class="sl-feed-tile"
            role="button"
            tabindex="0"
            @click="goDetail(item.product)"
            @keydown.enter="goDetail(item.product)"
            @keydown.space.self.prevent="goDetail(item.product)"
          >
            <div class="sl-feed-cover">
              <ProductImage :product="item.product" fit="contain" width="100%" height="100%" />
            </div>
            <p class="sl-feed-name">{{ item.product.productName }}</p>
            <p class="sl-feed-price"><span class="sym">¥</span>{{ formatPrice(item.product) }}</p>
            <CampaignProductTag v-if="DEMO_MODE" :product-id="item.product.productId" />
            <p v-if="DEMO_MODE" class="feed-tip">可售库存 {{ item.product.stock ?? '--' }}</p>
          </article>
        </div>
        <el-empty v-else description="暂无商品，稍后再来看看" />
      </el-skeleton>
      <div ref="feedSentinel" class="sl-feed-sentinel">
        <span v-if="feedLoading" class="feed-tip">加载中…</span>
        <span v-else-if="feedFinished && displayProducts.length >= MAX_PRODUCTS" class="feed-tip">已展示全部推荐商品</span>
        <span v-else-if="feedFinished && displayProducts.length" class="feed-tip">没有更多了</span>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { DEMO_MODE } from '@/integrations/demo';
import { useRouter } from 'vue-router';
import ProductImage from '@/components/common/ProductImage.vue';
import PcSimlectHomeScreen from '@/components/home/PcSimlectHomeScreen.vue';
import CampaignHomeEntry from '@/components/campaign/CampaignHomeEntry.vue';
import CampaignProductTag from '@/components/campaign/CampaignProductTag.vue';
import { productApi } from '@/api/modules';
import { usePageRefresh } from '@/composables/pullRefresh';
import { normalizeCategoryTree } from '@/utils/category';
import { filterOnSaleProducts } from '@/utils/product';

const router = useRouter();
const categories = ref<any[]>([]);
const hotProductsList = ref<any[]>([]);
const products = ref<any[]>([]);
const loading = ref(true);

const feedSentinel = ref<HTMLElement | null>(null);
const feedPageNo = ref(0);
const feedPageTotal = ref(1);
const feedLoading = ref(false);
const feedFinished = ref(false);
const MAX_PRODUCTS = 120;
let feedObserver: IntersectionObserver | null = null;

const displayProducts = computed(() => {
  return products.value.slice(0, MAX_PRODUCTS).map((product, displayIndex) => ({ product, displayIndex }));
});

const rootCategories = computed(() => normalizeCategoryTree(categories.value));
const hotProducts = computed(() => hotProductsList.value.slice(0, 10));

const formatPrice = (p: Record<string, any>) => {
  const val = p.minPrice ?? p.price ?? p.salePrice;
  return val != null ? Number(val).toFixed(2) : '--';
};

const loadFeed = async (reset = false) => {
  if (feedLoading.value) return;

  if (reset) {
    feedPageNo.value = 0;
    feedPageTotal.value = 1;
    feedFinished.value = false;
    products.value = [];
  }

  if (feedFinished.value) return;

  feedLoading.value = true;
  try {
    const next = feedPageNo.value + 1;
    const page = await productApi.loadProduct({ pageNo: next });
    const chunk = filterOnSaleProducts(page?.list);

    if (chunk.length > 0) {
      const existingIds = new Set(products.value.map(p => p.productId));
      const filtered = chunk.filter(p => !existingIds.has(p.productId));
      products.value = products.value.concat(filtered);
    }

    feedPageNo.value = Number(page?.pageNo) || next;
    feedPageTotal.value = Number(page?.pageTotal) || feedPageNo.value;

    const hasMoreData = chunk.length > 0 && feedPageNo.value < feedPageTotal.value;
    const reachedMax = products.value.length >= MAX_PRODUCTS;

    if (!hasMoreData || reachedMax) {
      feedFinished.value = true;
    }
  } catch (error) {
    console.error('PcHomeView: loadFeed error', error);
  } finally {
    feedLoading.value = false;
  }
};

const load = async () => {
  loading.value = true;
  try {
    const [cats, commend] = await Promise.all([
      productApi.loadCategory(),
      productApi.loadCommendProduct()
    ]);
    categories.value = normalizeCategoryTree(cats || []);
    hotProductsList.value = filterOnSaleProducts(Array.isArray(commend) ? commend : commend?.list);
    await loadFeed(true);

    while (!feedFinished.value && products.value.length < MAX_PRODUCTS) {
      await loadFeed();
    }
  } finally {
    loading.value = false;
  }
};

const goDetail = (p: any) => {
  if (p?.productId) router.push(`/product/${p.productId}`);
};

const setupInfiniteScroll = async () => {
  await nextTick();

  if (!feedSentinel.value) {
    console.warn('PcHomeView: feedSentinel not found');
    return;
  }

  feedObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting && !feedLoading.value && !feedFinished.value) {
          loadFeed();
        }
      }
    },
    {
      root: null,
      rootMargin: '300px 0px',
      threshold: 0.01
    }
  );

  feedObserver.observe(feedSentinel.value);
};

onMounted(async () => {
  await load();
  setupInfiniteScroll();
});

onUnmounted(() => {
  if (feedObserver) {
    feedObserver.disconnect();
    feedObserver = null;
  }
});

usePageRefresh(load);
</script>
