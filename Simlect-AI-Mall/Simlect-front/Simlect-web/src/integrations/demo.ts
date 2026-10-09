export const DEMO_MODE = import.meta.env.VITE_DEMO_MODE === 'true';
export const SUPPLY_WORKSPACE_URL = 'http://127.0.0.1:5173/index';

const enabledRoutes = [
  '/', '/seckill', '/activities', '/recommend', '/search', '/search-portal', '/search-result', '/category/', '/product/',
  '/cart', '/checkout', '/address', '/after-sale', '/orders', '/order/', '/ai-assistant', '/login'
];

export function isDemoRouteSupported(path: string): boolean {
  if (!DEMO_MODE) return true;
  if (path.endsWith('/comments') || path.endsWith('/logistics')) return false;
  return enabledRoutes.some((route) => route.endsWith('/') && route !== '/'
    ? path.startsWith(route)
    : path === route);
}
