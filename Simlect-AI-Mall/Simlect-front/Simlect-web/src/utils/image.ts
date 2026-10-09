
import { demoProductCover } from './demoMedia';

export const IMAGE_BASE = '/api/file/getResource?sourceName=';

const THUMB_SUFFIX = '_thumbnail';

const INVALID_IMAGE_PATH = /^\[object Object\]$/i;

export function extractUploadPath(value: unknown): string {
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed || INVALID_IMAGE_PATH.test(trimmed)) return '';
    return trimmed;
  }
  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    if (typeof record.path === 'string') {
      return extractUploadPath(record.path);
    }
  }
  return '';
}

export function joinImagePaths(paths: unknown[]): string {
  return paths.map(extractUploadPath).filter(Boolean).join(',');
}

export function splitImagePaths(raw?: string | null): string[] {
  if (!raw) return [];
  return String(raw)
    .split(',')
    .map((item) => extractUploadPath(item))
    .filter(Boolean);
}

export function resolveImageUrl(
  source?: string | null,
  options?: { useThumbnail?: boolean; index?: number }
): string {
  if (!source) return '';
  const raw = String(source).trim();
  if (!raw) return '';
  const parts = raw.split(',').map((s) => s.trim()).filter(Boolean);
  let path = parts[options?.index ?? 0] ?? '';
  if (!path) return '';
  if (/^https?:\/\//i.test(path)) return path;
  // Static demo files belong to the front end (or its /media proxy), not the upload API.
  if (/^\/(?:demo-media|media|assets|simlect-origin|pwa)\//.test(path)) return path;
  if (/^\/(?:favicon\.svg|icons\.svg|apple-touch-icon\.png)(?:\?|$)/.test(path)) return path;
  if (path.startsWith('/api/file/getResource?')) return path;
  // Reject malformed values rather than requesting them as upload paths.
  if (/^(?:data|javascript|vbscript|file):/i.test(path) || path.startsWith('//') || INVALID_IMAGE_PATH.test(path)) return '';

  const useThumbnail = options?.useThumbnail !== false;
  if (!useThumbnail && path.includes(THUMB_SUFFIX)) {
    path = path.replace(THUMB_SUFFIX, '');
  }

  return `${IMAGE_BASE}${encodeURIComponent(path)}`;
}

export function resolveAvatarUrl(avatar?: string | null): string {
  if (!avatar || !String(avatar).trim()) return '';
  const av = String(avatar).trim();
  if (av === 'avatar.png') return '';
  return resolveImageUrl(av);
}

export function pickProductCover(product: Record<string, any>): string {
  const source = product?.cover || product?.productCover || product?.image || product?.imageUrl;
  if (!source || ['/media/demo/fallback.svg', '/demo-media/fallback.svg', '/media/demo/products/fallback.svg', '/demo-media/products/fallback.svg'].includes(source)) return demoProductCover(product) || '/demo-media/fallback.svg';
  return source;
}
