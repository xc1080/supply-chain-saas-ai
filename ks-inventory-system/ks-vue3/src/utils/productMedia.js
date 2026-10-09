const DEMO_MEDIA = '/demo-media';

export const DEMO_PRODUCT_IMAGES = Object.freeze({
  'DEMO-LAMP-ZB': `${DEMO_MEDIA}/products/lamp-zb.svg`,
  'DEMO-LAMP-WIFI': `${DEMO_MEDIA}/products/lamp-wifi.svg`,
  'DEMO-LAMP-PRO': `${DEMO_MEDIA}/products/lamp-pro.svg`,
  'DEMO-SENSOR-DOOR': `${DEMO_MEDIA}/products/sensor-door.svg`,
  'DEMO-SENSOR-MOTION': `${DEMO_MEDIA}/products/sensor-motion.svg`,
  'DEMO-GATEWAY-ZB': `${DEMO_MEDIA}/products/gateway-zb.svg`,
  'DEMO-LOCK-WIFI': `${DEMO_MEDIA}/products/lock-wifi.svg`,
  'DEMO-SWITCH-ZB': `${DEMO_MEDIA}/products/switch-zb.svg`,
  'LAB-TAPO-H100': `${DEMO_MEDIA}/products/gateway-zb.svg`,
  'LAB-TAPO-H200': `${DEMO_MEDIA}/products/gateway-zb.svg`,
  'LAB-TAPO-T100': `${DEMO_MEDIA}/products/sensor-motion.svg`,
  'LAB-TAPO-T110': `${DEMO_MEDIA}/products/sensor-door.svg`,
  'LAB-TAPO-T310': `${DEMO_MEDIA}/products/sensor-door.svg`,
  'LAB-TAPO-T300': `${DEMO_MEDIA}/products/sensor-door.svg`,
  'LAB-TAPO-S210': `${DEMO_MEDIA}/products/switch-zb.svg`,
  'LAB-TAPO-P110': `${DEMO_MEDIA}/products/switch-zb.svg`,
  'LAB-TAPO-L530E': `${DEMO_MEDIA}/products/lamp-wifi.svg`,
  'LAB-TAPO-L510E': `${DEMO_MEDIA}/products/lamp-wifi.svg`,
  'LAB-AQARA-M3': `${DEMO_MEDIA}/products/gateway-zb.svg`,
  'LAB-AQARA-P2': `${DEMO_MEDIA}/products/sensor-door.svg`
});

export const PRODUCT_IMAGE_FALLBACK = `${DEMO_MEDIA}/fallback.svg`;

export function getProductImage(product = {}) {
  const source = product.cover || product.productCover || product.image || product.imageUrl;
  const first = typeof source === 'string' ? source.split(',')[0].trim() : '';
  const code = String(product.productCode || product.code || '').trim().toUpperCase();
  if (!first || ['/media/demo/fallback.svg', '/demo-media/fallback.svg', '/media/demo/products/fallback.svg', '/demo-media/products/fallback.svg'].includes(first)) return DEMO_PRODUCT_IMAGES[code] || PRODUCT_IMAGE_FALLBACK;
  if (first.startsWith('/media/demo/')) return first.replace('/media/demo/', `${DEMO_MEDIA}/`);
  if (/^https?:\/\//i.test(first) || /^\/(?:media|demo-media|assets|profile)\//.test(first)) return first;
  return DEMO_PRODUCT_IMAGES[code] || PRODUCT_IMAGE_FALLBACK;
}

export function handleProductImageError(event, product = {}) {
  const image = event?.target;
  if (!image || image.tagName !== 'IMG') return;
  const code = String(product.productCode || product.code || '').trim().toUpperCase();
  const fallback = DEMO_PRODUCT_IMAGES[code] || PRODUCT_IMAGE_FALLBACK;
  if (image.dataset.mediaFallback === 'generic') {
    image.onerror = null;
    return;
  }
  if (image.dataset.mediaFallback === 'product' || image.getAttribute('src') === fallback) {
    image.dataset.mediaFallback = 'generic';
    image.src = PRODUCT_IMAGE_FALLBACK;
  } else {
    image.dataset.mediaFallback = 'product';
    image.src = fallback;
  }
}
