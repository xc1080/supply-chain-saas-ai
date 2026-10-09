const DEMO_MEDIA = '/demo-media';

export const DEMO_PRODUCT_IMAGES: Readonly<Record<string, string>> = Object.freeze({
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

export function demoProductCover(product?: Record<string, unknown> | string | null): string {
  const code = typeof product === 'string' ? product : product?.productCode ?? product?.code;
  return DEMO_PRODUCT_IMAGES[String(code ?? '').trim().toUpperCase()] ?? '';
}

export function demoCategoryImage(categoryName?: string | null): string {
  const name = String(categoryName ?? '');
  if (/照明|灯/.test(name)) return `${DEMO_MEDIA}/categories/lighting.svg`;
  if (/传感|感应/.test(name)) return `${DEMO_MEDIA}/categories/sensors.svg`;
  if (/网关|中枢/.test(name)) return `${DEMO_MEDIA}/categories/gateway.svg`;
  if (/安防|门锁|开关/.test(name)) return `${DEMO_MEDIA}/categories/security.svg`;
  return PRODUCT_IMAGE_FALLBACK;
}
