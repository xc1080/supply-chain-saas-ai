import { computed, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { productApi } from '@/api/modules';
import { isProductOnSale } from '@/utils/product';
import { initialSku, isSkuAvailable, matchSkuValue, skuValues } from '@/utils/sku';
import { MAX_CART_QTY } from '@/constants/validation';

export function useProductSku(getProductId: () => string) {
  const loading = ref(true);
  const baseProductInfo = ref<Record<string, any> | null>(null);
  const productPropertyList = ref<any[]>([]);
  const skuList = ref<any[]>([]);
  const quantity = ref(1);
  const selectedSku = ref<Record<string, any>>({});
  const selectedProperty = reactive<Record<string, string>>({});
  let loadVersion = 0;

  const productInfo = computed<Record<string, any> | null>(() => baseProductInfo.value ? {
    ...baseProductInfo.value, ...selectedSku.value,
    productId: selectedSku.value.skuId ?? baseProductInfo.value.productId,
    productName: selectedSku.value.productName ?? selectedSku.value.name ?? baseProductInfo.value.productName,
    productCode: selectedSku.value.productCode ?? selectedSku.value.code ?? baseProductInfo.value.productCode,
    productDesc: selectedSku.value.productDesc ?? selectedSku.value.description ?? baseProductInfo.value.productDesc,
    minPrice: selectedSku.value.skuId ? selectedSku.value.price : baseProductInfo.value.minPrice
  } : null);
  const displayPrice = computed(() => {
    const price = productInfo.value?.minPrice;
    return price != null && price !== '' && Number.isFinite(Number(price)) && Number(price) > 0 ? Number(price).toFixed(2) : '—';
  });
  const canBuy = computed(() => !loading.value && Boolean(selectedSku.value.skuId) && isSkuAvailable(selectedSku.value));
  const maxBuy = computed(() => Math.max(1, Math.min(Math.floor(Math.max(0, Number(selectedSku.value.stock) || 0)), MAX_CART_QTY)));
  const coverImage = computed(() => String(productInfo.value?.cover || '').split(',')[0]?.trim() || '');

  const reset = () => {
    baseProductInfo.value = null; productPropertyList.value = []; skuList.value = [];
    selectedSku.value = {}; quantity.value = 1;
    Object.keys(selectedProperty).forEach(key => delete selectedProperty[key]);
  };
  const chooseSku = (sku: Record<string, any> | undefined) => {
    Object.keys(selectedProperty).forEach(key => delete selectedProperty[key]);
    selectedSku.value = sku || {};
    if (sku) Object.assign(selectedProperty, skuValues(sku, productPropertyList.value));
    quantity.value = Math.max(1, Math.min(quantity.value, maxBuy.value));
  };
  const isPropertyDisabled = (property: Record<string, any>, value: Record<string, any>) =>
    !matchSkuValue(skuList.value, productPropertyList.value, selectedProperty, String(property.propertyId), String(value.propertyValueId));
  const propertyUnavailableReason = (property: Record<string, any>, value: Record<string, any>) => {
    const matching = skuList.value.filter(sku => skuValues(sku, productPropertyList.value)[String(property.propertyId)] === String(value.propertyValueId));
    if (!matching.length) return '暂无此规格';
    if (matching.some(isSkuAvailable)) return '';
    return matching.some(sku => Number(sku.stock) > 0) ? '暂未定价' : '已售罄';
  };
  const selectProperty = (property: Record<string, any>, value: Record<string, any>) => {
    const matched = matchSkuValue(skuList.value, productPropertyList.value, selectedProperty, String(property.propertyId), String(value.propertyValueId));
    if (matched) chooseSku(matched);
  };

  const load = async () => {
    const productId = getProductId();
    const version = ++loadVersion;
    reset(); loading.value = true;
    try {
      if (!productId) return;
      const data = await productApi.getProduct(productId);
      if (version !== loadVersion || productId !== getProductId()) return;
      const product = data?.productInfo || null;
      if (product && !isProductOnSale(product)) { ElMessage.warning('该商品已下架'); return; }
      baseProductInfo.value = product;
      productPropertyList.value = data?.productPropertyList || [];
      skuList.value = data?.skuList || [];
      chooseSku(initialSku(skuList.value, productId));
    } finally {
      if (version === loadVersion) loading.value = false;
    }
  };
  const cancelLoad = () => { ++loadVersion; reset(); loading.value = false; };
  const validateSku = () => {
    if (!canBuy.value) { ElMessage.warning('请选择有可售库存的规格'); return false; }
    if (!Number.isInteger(quantity.value) || quantity.value < 1 || quantity.value > maxBuy.value) {
      ElMessage.warning('购买数量超出可售库存'); return false;
    }
    return true;
  };

  return { loading, productInfo, productPropertyList, skuList, quantity, selectedSku,
    selectedProperty, displayPrice, maxBuy, coverImage, canBuy, isPropertyDisabled, propertyUnavailableReason,
    selectProperty, load, cancelLoad, validateSku };
}
