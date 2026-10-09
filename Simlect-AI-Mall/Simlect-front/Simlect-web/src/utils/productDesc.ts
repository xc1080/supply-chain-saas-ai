import { IMAGE_BASE, resolveImageUrl } from '@/utils/image';

export function normalizeProductDesc(desc?: string | null): string {
  if (!desc) return '';

  let text = String(desc).trim();

  text = text.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, (_match, alt, url) => {
    const raw = String(url).trim();
    if (!raw) return '';

    if (raw.startsWith('http://') || raw.startsWith('https://')) {
      return `![${alt}](${raw})`;
    }

    if (raw.includes('/api/file/getResource')) {
      return `![${alt}](${raw.startsWith('/') ? raw : `/${raw}`})`;
    }

    if (raw.startsWith('sourceName=')) {
      return `![${alt}](${IMAGE_BASE}${raw.replace(/^sourceName=/, '')})`;
    }

    const imageUrl = resolveImageUrl(raw, { useThumbnail: false });
    return imageUrl ? `![${alt}](${imageUrl})` : '';
  });

  text = text.replace(/^(202601\/[^\s\n]+\.(?:png|jpg|jpeg|gif|webp))$/gim, (path) => {
    return `![](${IMAGE_BASE}${path})`;
  });

  return text;
}
