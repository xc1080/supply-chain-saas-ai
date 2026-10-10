import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { commentApi, favoriteApi } from '@/api/modules';
import { usePageRefresh } from '@/composables/pullRefresh';
import { openImagePreview } from '@/composables/imagePreview';
import { useProductSkuSheet } from '@/composables/useProductSkuSheet';
import { useAuthStore } from '@/stores/auth';
import { useProductSku } from '@/composables/useProductSku';
import { resolveImageUrl } from '@/utils/image';
import { saveCheckoutSession } from '@/utils/checkout';
import { toast } from '@/utils/toast';
import { DEMO_MODE } from '@/integrations/demo';

export function useProductDetailPage() {
  const route = useRoute();
  const router = useRouter();
  const authStore = useAuthStore();
  const { open: openSkuSheet } = useProductSkuSheet();

  const productId = computed(() => String(route.params.productId || ''));
  const { loading, productInfo, productPropertyList, quantity, selectedSku, selectedProperty,
    displayPrice, maxBuy, canBuy, isPropertyDisabled, propertyUnavailableReason, selectProperty, load: loadSku, cancelLoad, validateSku
  } = useProductSku(() => productId.value);
  let loadVersion = 0;
  const loadError = ref(false);
  const comments = ref<any[]>([]);
  const commentTotal = ref(0);
  const commentGoodRate = ref(100);
  const commentImageCount = ref(0);
  const activeImageIndex = ref(0);
  const favorited = ref(false);
  const favoriteLoading = ref(false);
  const detailTab = ref<'comments' | 'desc'>('desc');

  const PREVIEW_COMMENT_COUNT = 2;

  const thumbList = computed(() => {
    const cover = productInfo.value?.cover;
    if (!cover) return [];
    return String(cover)
      .split(',')
      .map((s: string) => s.trim())
      .filter(Boolean);
  });

  const galleryImages = computed(() => thumbList.value);

  const agentConsultProduct = computed(() => {
    const p = productInfo.value;
    if (!p?.productId) return null;
    return {
      productId: String(p.productId),
      productName: String(p.productName || '商品'),
      cover: thumbList.value[0] || '',
      minPrice: displayPrice.value,
      productCode: String(p.productCode || p.code || ''),
      spec: String(p.spec || '')
    };
  });

  const previewComments = computed(() => comments.value.slice(0, PREVIEW_COMMENT_COUNT));

  const touchStartX = ref(0);
  const touchDeltaX = ref(0);
  const isDragging = ref(false);

  const onTouchStart = (e: TouchEvent) => {
    touchStartX.value = e.touches[0].clientX;
  };

  const onTouchMove = (e: TouchEvent) => {
    touchDeltaX.value = e.touches[0].clientX - touchStartX.value;
  };

  const onTouchEnd = () => {
    applySwipe();
    touchDeltaX.value = 0;
  };

  const onMouseDown = (e: MouseEvent) => {
    isDragging.value = true;
    touchStartX.value = e.clientX;
    e.preventDefault();
  };

  const onMouseMove = (e: MouseEvent) => {
    if (!isDragging.value) return;
    touchDeltaX.value = e.clientX - touchStartX.value;
  };

  const onMouseUp = () => {
    if (!isDragging.value) return;
    applySwipe();
    touchDeltaX.value = 0;
    isDragging.value = false;
  };

  const applySwipe = () => {
    if (Math.abs(touchDeltaX.value) > 50) {
      if (touchDeltaX.value > 0 && activeImageIndex.value > 0) {
        activeImageIndex.value--;
      } else if (touchDeltaX.value < 0 && activeImageIndex.value < galleryImages.value.length - 1) {
        activeImageIndex.value++;
      }
      return;
    }
    if (Math.abs(touchDeltaX.value) <= 10 && galleryImages.value.length) {
      openGalleryPreview(activeImageIndex.value);
    }
  };

  const openGalleryPreview = (index: number) => {
    const urls = galleryImages.value
      .map((img) => resolveImageUrl(img, { useThumbnail: false }) || img)
      .filter(Boolean);
    if (!urls.length) return;
    openImagePreview(urls, index);
  };

  const selectGalleryIndex = (index: number) => {
    if (index < 0 || index >= galleryImages.value.length) return;
    activeImageIndex.value = index;
  };

  const loadFavoriteStatus = async () => {
    const id = productId.value, version = loadVersion;
    if (DEMO_MODE || !authStore.isLoggedIn || !productId.value) {
      favorited.value = false;
      return;
    }
    try {
      const result = Boolean(await favoriteApi.isFavorite(id));
      if (id === productId.value && version === loadVersion) favorited.value = result;
    } catch {
      if (id === productId.value && version === loadVersion) favorited.value = false;
    }
  };

  const toggleFavorite = async () => {
    if (!productId.value) return;
    if (!authStore.isLoggedIn) {
      router.push({ path: '/login', query: { redirect: route.fullPath } });
      return;
    }
    if (favoriteLoading.value) return;
    favoriteLoading.value = true;
    try {
      favorited.value = Boolean(await favoriteApi.toggleFavorite(productId.value));
      toast.success(favorited.value ? '已加入收藏' : '已取消收藏');
    } finally {
      favoriteLoading.value = false;
    }
  };

  const load = async () => {
    const version = ++loadVersion;
    const id = productId.value;
    loadError.value = false; activeImageIndex.value = 0;
    comments.value = []; commentTotal.value = 0; commentGoodRate.value = 100; commentImageCount.value = 0;
    favorited.value = false;
    try {
      await loadSku();
      if (version !== loadVersion || id !== productId.value || !productInfo.value || DEMO_MODE) return;
      const commentRes = await commentApi.loadComment({ pageNo: 1, productId: id });
      if (version !== loadVersion || id !== productId.value) return;
      comments.value = commentRes?.list || [];
      commentTotal.value = commentRes?.totalCount ?? comments.value.length;
      try {
        const stats = await commentApi.getProductCommentStats(id);
        if (version !== loadVersion || id !== productId.value) return;
        if (stats) {
          commentGoodRate.value = stats.goodRatePercent ?? 100;
          commentImageCount.value = stats.imageCount ?? 0;
          if (stats.totalCount != null) commentTotal.value = stats.totalCount;
        }
      } catch { /* Comments do not block a valid product. */ }
      await loadFavoriteStatus();
    } catch {
      if (version === loadVersion && id === productId.value) { loadError.value = true; cancelLoad(); }
    }
  };

  const goAllComments = () => {
    router.push(`/product/${route.params.productId}/comments`);
  };

  const openAddCartSheet = () => {
    const id = productInfo.value?.productId ?? String(route.params.productId);
    if (!id) return;
    openSkuSheet(id);
  };

  const buildPropertyData = () =>
    productPropertyList.value
      .map((prop) => {
        const valId = selectedProperty[prop.propertyId];
        const val = prop.propertyValues?.find((v: any) => v.propertyValueId === valId);
        return val ? { propertyName: prop.propertyName, propertyValue: val.propertyValue } : null;
      })
      .filter(Boolean) as { propertyName: string; propertyValue: string }[];

  const buyNow = () => {
    if (!validateSku() || !productInfo.value) return;
    const cover = thumbList.value[0] || productInfo.value?.cover?.split(',')[0];
    const checkoutItems = [
      {
        productId: String(selectedSku.value.skuId),
        productName: productInfo.value.productName,
        productCover: cover,
        propertyValueIds: selectedSku.value.propertyValueIds,
        propertyValueIdHash: selectedSku.value.propertyValueIdHash,
        propertyData: buildPropertyData(),
        price: Number(selectedSku.value.price ?? productInfo.value?.minPrice ?? 0),
        buyCount: quantity.value
      }
    ];
    saveCheckoutSession(checkoutItems, 0);
    if (!authStore.isLoggedIn) {
      router.push({ path: '/login', query: { redirect: '/checkout' } });
      return;
    }
    router.push('/checkout');
  };

  watch(
    () => route.params.productId,
    (id) => {
      if (id) load();
    }
  );

  watch(
    () => authStore.isLoggedIn,
    () => {
      loadFavoriteStatus();
    }
  );

  watch(() => selectedSku.value.skuId, () => { activeImageIndex.value = 0; });
  onBeforeUnmount(() => { ++loadVersion; cancelLoad(); });
  onMounted(load);
  usePageRefresh(load);

  return {
    loading,
    loadError,
    load,
    productInfo,
    productPropertyList,
    comments,
    commentTotal,
    commentGoodRate,
    commentImageCount,
    quantity,
    selectedSku,
    selectedProperty,
    activeImageIndex,
    favorited,
    favoriteLoading,
    detailTab,
    galleryImages,
    displayPrice,
    agentConsultProduct,
    previewComments,
    maxBuy,
    canBuy,
    isPropertyDisabled,
    propertyUnavailableReason,
    onTouchStart,
    onTouchMove,
    onTouchEnd,
    onMouseDown,
    onMouseMove,
    onMouseUp,
    openGalleryPreview,
    selectGalleryIndex,
    selectProperty,
    toggleFavorite,
    goAllComments,
    openAddCartSheet,
    buyNow
  };
}
