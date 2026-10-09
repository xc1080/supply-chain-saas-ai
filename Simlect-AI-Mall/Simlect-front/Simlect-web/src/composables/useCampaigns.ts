import { computed, onActivated, onDeactivated, onMounted, onUnmounted, readonly, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import request from '@/api/http';
import { DEMO_MODE } from '@/integrations/demo';
import { toast } from '@/utils/toast';
import { addressApi } from '@/api/modules';
import { loadCheckoutSelectedAddress, saveCheckoutSelectedAddress } from '@/utils/checkout';
import { confirmAction } from '@/utils/confirm';

export interface Campaign {
  activityId: string;
  productId: string | number;
  productName: string;
  title: string;
  price: number;
  originalPrice?: number;
  remaining: number;
  capacity: number;
  perOwnerLimit: number;
  startsAt: string;
  endsAt: string;
  cover: string;
  spec?: string;
  description?: string;
  categoryName?: string;
  participationCount?: number;
  myOrderId?: string;
  myOrderStatus?: number;
  serverTime?: string;
}

interface Participation { activityId: string; participationCount: number; myOrderId: string | null; myOrderStatus: number | null; }
const publicActivities = ref<Campaign[]>([]);
const participation = ref<Record<string, Participation>>({});
// Shared public responses never carry a visitor's order into the catalogue cache.
const activities = computed<Campaign[]>(() => publicActivities.value.map(campaign => {
  const own = participation.value[campaign.activityId];
  return { ...campaign, participationCount: own?.participationCount,
    myOrderId: own?.myOrderId ?? undefined, myOrderStatus: own?.myOrderStatus ?? undefined };
}));
const now = ref(Date.now());
const publicLoading = ref(false);
const participationLoading = ref(false);
const publicError = ref('');
const participationError = ref('');
const loading = computed(() => publicLoading.value || participationLoading.value);
const error = computed(() => publicError.value || participationError.value);
const buying = ref('');
const checkoutNotice = ref('');
type CheckoutState = 'UNCONFIRMED' | 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'REJECTED';
interface CheckoutAttempt { key: string; jobId?: string; state: CheckoutState; orderId?: string; errorMessage?: string; originRoute?: string; addressId?: string; }
interface CheckoutReceipt { jobId: string; state: Exclude<CheckoutState, 'UNCONFIRMED'>; orderId?: string; errorCode?: number; errorMessage?: string; }
const checkouts = ref<Record<string, CheckoutAttempt>>({});
const checkoutPrefix = 'seckill-checkout:';
let consumers = 0;
let clockTimer: ReturnType<typeof setInterval> | undefined;
let publicTimer: ReturnType<typeof setTimeout> | undefined;
let publicFlight: Promise<void> | undefined;
let participationFlight: Promise<void> | undefined;
let checkoutFlight: Promise<void> | undefined;
let checkoutNeedsResume = false;
let activeRun: { owner: symbol; cancel: () => void } | undefined;
const recoveryCallbacks = new Map<symbol, () => void>();
let lastRead = 0;
let lastParticipationRead = 0;
const date = (value: string) => new Date(value.replace(' ', 'T')).getTime();

function remember(activityId: string, attempt: CheckoutAttempt) {
  checkouts.value = { ...checkouts.value, [activityId]: attempt };
  try { sessionStorage.setItem(checkoutPrefix + activityId, JSON.stringify(attempt)); } catch { /* Keep the current page's idempotency key even if storage is unavailable. */ }
}
function restoreCheckouts() {
  try {
  for (let i = 0; i < sessionStorage.length; i++) {
    const storageKey = sessionStorage.key(i);
    if (!storageKey?.startsWith(checkoutPrefix)) continue;
    try {
      const activityId = storageKey.slice(checkoutPrefix.length);
      const attempt = JSON.parse(sessionStorage.getItem(storageKey) || 'null') as CheckoutAttempt | null;
      if (/^[A-Za-z0-9_-]{1,32}$/.test(activityId) && attempt && /^[A-Za-z0-9_-]{1,80}$/.test(attempt.key)
          && (!attempt.jobId || /^[a-f0-9]{32}$/.test(attempt.jobId))
          && ['UNCONFIRMED', 'PENDING', 'PROCESSING', 'SUCCEEDED', 'REJECTED'].includes(attempt.state)) {
        checkouts.value[activityId] = attempt;
      }
    } catch { /* Ignore malformed local presentation state; the server still owns checkout identity and stock. */ }
  }
  } catch { /* Storage may be disabled; the in-memory key still survives visibility changes. */ }
}
function queued(campaign: Campaign) {
  const state = checkouts.value[campaign.activityId]?.state;
  return state === 'UNCONFIRMED' || state === 'PENDING' || state === 'PROCESSING';
}

function status(c: Campaign): 'live' | 'upcoming' | 'soldout' | 'ended' {
  if (now.value >= date(c.endsAt)) return 'ended';
  if (now.value < date(c.startsAt)) return 'upcoming';
  return Number(c.remaining) > 0 ? 'live' : 'soldout';
}
function countdown(c: Campaign) {
  const target = status(c) === 'upcoming' ? c.startsAt : c.endsAt;
  const seconds = Math.max(0, Math.ceil((date(target) - now.value) / 1000));
  const hours = Math.floor(seconds / 3600);
  return [hours, Math.floor(seconds / 60) % 60, seconds % 60].map(n => String(n).padStart(2, '0')).join(':');
}
function campaignFor(productId: string | number) {
  return activities.value.filter(c => String(c.productId) === String(productId) && status(c) !== 'ended')
    .sort((a, b) => {
      const priority = { live: 0, upcoming: 1, soldout: 2, ended: 3 };
      return priority[status(a)] - priority[status(b)] || Number(a.price) - Number(b.price);
    })[0];
}
function schedulePublicRefresh() {
  if (publicTimer) clearTimeout(publicTimer);
  if (!consumers || document.visibilityState !== 'visible') { publicTimer = undefined; return; }
  publicTimer = setTimeout(() => { publicTimer = undefined; void refreshPublic(); }, 30000 + Math.random() * 30000);
}
function stopTimers() {
  if (publicTimer) clearTimeout(publicTimer);
  if (clockTimer) clearInterval(clockTimer);
  publicTimer = undefined; clockTimer = undefined;
}
function startTimers() {
  now.value = Date.now();
  if (!clockTimer && consumers && document.visibilityState === 'visible') {
    clockTimer = setInterval(() => { now.value = Date.now(); }, 1000);
  }
  if (!publicTimer && !publicFlight) schedulePublicRefresh();
}
async function refreshPublic() {
  if (!DEMO_MODE) return;
  if (publicFlight) return publicFlight;
  publicLoading.value = true;
  publicFlight = (async () => {
    try {
      const rows = await request.get<Campaign[]>('/seckill/listActivities', {silentError: true, withCredentials: false});
      if (!Array.isArray(rows)) throw new Error('Invalid activity list');
      publicActivities.value = rows.map(({ participationCount: _count, myOrderId: _order, myOrderStatus: _status, ...campaign }) => campaign);
      // serverTime can be the generation time of a cached public response.
      // Countdown ticks locally; the purchase service validates the real window.
      now.value = Date.now();
      publicError.value = ''; lastRead = Date.now();
    } catch {
      publicError.value = '活动暂时未能更新，请重试';
    } finally {
      publicLoading.value = false; publicFlight = undefined; schedulePublicRefresh();
    }
  })();
  return publicFlight;
}
async function refreshParticipation(force = true) {
  if (!DEMO_MODE) return;
  if (participationFlight) return participationFlight;
  // Many product cards can mount in one render; they share one personal read.
  if (!force && Date.now() - lastParticipationRead < 1000) return;
  participationLoading.value = true;
  participationFlight = (async () => {
    try {
      const rows = await request.get<Participation[]>('/seckill/myParticipation', { silentError: true });
      if (!Array.isArray(rows)) throw new Error('Invalid participation list');
      participation.value = Object.fromEntries(rows.map(own => [own.activityId, own]));
      participationError.value = ''; lastParticipationRead = Date.now();
    } catch {
      participation.value = {};
      participationError.value = '参与状态暂未确认，请刷新后继续';
    } finally { participationLoading.value = false; participationFlight = undefined; }
  })();
  return participationFlight;
}
async function refresh() { await Promise.all([refreshPublic(), refreshParticipation()]); }
function visibilityChanged() {
  if (document.visibilityState !== 'visible') { stopTimers(); activeRun?.cancel(); return; }
  startTimers();
  void Promise.all([Date.now() - lastRead >= 30000 ? refreshPublic() : undefined, refreshParticipation()])
    .then(() => { for (const recover of recoveryCallbacks.values()) recover(); });
}

export function useCampaigns() {
  const router = useRouter();
  const owner = Symbol('campaign-consumer');
  let mounted = false;
  function recover() {
    if (!mounted || document.visibilityState !== 'visible') return;
    if (checkoutFlight) {
      if (checkoutNeedsResume) void checkoutFlight.then(() => { if (checkoutNeedsResume) recover(); });
      return;
    }
    if (buying.value) return;
    const pending = Object.entries(checkouts.value).find(([, a]) => ['UNCONFIRMED', 'PENDING', 'PROCESSING'].includes(a.state)
      && (!a.originRoute || a.originRoute === router.currentRoute.value.fullPath));
    if (!pending) return;
    void runAttempt(pending[0], pending[1]);
  }
  function enter() {
    if (!DEMO_MODE || mounted) return;
    mounted = true;
    consumers++;
    recoveryCallbacks.set(owner, recover);
    if (consumers === 1) document.addEventListener('visibilitychange', visibilityChanged);
    restoreCheckouts();
    if (document.visibilityState === 'visible') {
      startTimers();
      void Promise.all([!lastRead || Date.now() - lastRead >= 30000 ? refreshPublic() : undefined, refreshParticipation(false)]).then(recover);
    }
  }
  function leave() {
    if (!mounted) return;
    mounted = false; consumers--; recoveryCallbacks.delete(owner);
    if (activeRun?.owner === owner) activeRun.cancel();
    if (!consumers) {
      stopTimers(); document.removeEventListener('visibilitychange', visibilityChanged);
    }
  }
  watch(() => router.currentRoute.value.fullPath, () => { if (activeRun?.owner === owner) activeRun.cancel(); });
  onMounted(enter); onActivated(enter);
  onUnmounted(leave); onDeactivated(leave);

  async function runAttempt(activityId: string, saved: CheckoutAttempt) {
    if (checkoutFlight) return checkoutFlight;
    const operation = performAttempt(activityId, saved);
    checkoutFlight = operation;
    try { await operation; } finally { if (checkoutFlight === operation) checkoutFlight = undefined; }
  }
  async function performAttempt(activityId: string, saved: CheckoutAttempt) {
    if (!mounted || document.visibilityState !== 'visible') return;
    let cancelled = false;
    let wake: (() => void) | undefined;
    checkoutNeedsResume = false;
    const run = { owner, cancel: () => { cancelled = true; checkoutNeedsResume = true; wake?.(); } };
    activeRun = run;
    const originRoute = router.currentRoute.value.fullPath;
    const canContinue = () => !cancelled && mounted && document.visibilityState === 'visible' && router.currentRoute.value.fullPath === originRoute;
    const pause = (delay: number) => new Promise<void>(resolve => {
      const timeout = setTimeout(finish, delay);
      function finish() { clearTimeout(timeout); wake = undefined; resolve(); }
      wake = finish;
      if (!canContinue()) finish();
    });
    buying.value = activityId;
    checkoutNotice.value = '正在排队，结果确认后进入付款';
    let attempt = { ...saved, originRoute };
    remember(activityId, attempt);
    const deadline = Date.now() + 45000;
    const accept = (receipt: CheckoutReceipt) => {
      if (!receipt || !/^[a-f0-9]{32}$/.test(receipt.jobId)
          || !['PENDING', 'PROCESSING', 'SUCCEEDED', 'REJECTED'].includes(receipt.state)) throw new Error('Invalid checkout receipt');
      attempt = { ...attempt, jobId: receipt.jobId, state: receipt.state, orderId: receipt.orderId, errorMessage: receipt.errorMessage };
      remember(activityId, attempt);
    };
    try {
      if (!attempt.jobId) {
        if (!attempt.addressId) {
          const rows = await addressApi.loadDataList();
          const address = Array.isArray(rows) ? rows.find(row => row.addressId === loadCheckoutSelectedAddress()) || rows.find(row => row.defaultType === 1) || rows[0] : null;
          if (!address) {checkoutNotice.value = '请先选择收货地址';await router.push({path:'/address',query:{from:'seckill',returnTo:originRoute}});return;}
          attempt.addressId = address.addressId; remember(activityId, attempt);
        }
        accept(await request.post<CheckoutReceipt>('/seckill/submitOrder', { activityId, requestKey: attempt.key, addressId: attempt.addressId }, { silentError: true }));
      }
      for (let poll = 0; poll <= 30; poll++) {
        if (!canContinue()) break;
        if (attempt.state === 'SUCCEEDED') {
          if (!attempt.orderId) throw new Error('Missing final order');
          const order = await request.post<{orderStatus: number}>('/order/getOrderInfo', {payOrderId: attempt.orderId}, {silentError: true});
          if (Number(order.orderStatus) === 4) {
            // A durable key can refer to an order cancelled before the async queue migration.
            // Confirm its final state before permitting a new explicit purchase request.
            attempt.state = 'REJECTED'; attempt.errorMessage = '原订单已关闭，可以重新参与本活动';
            remember(activityId, attempt); checkoutNotice.value = attempt.errorMessage;
            toast.info(checkoutNotice.value); if (canContinue()) await refresh(); return;
          }
          checkoutNotice.value = Number(order.orderStatus) === 0 ? '订单已创建，请在付款倒计时内完成支付' : '订单已确认';
          if (canContinue()) await refresh();
          // A result must not interrupt a shopper who intentionally navigated elsewhere while waiting.
          if (canContinue()) await router.push('/order/' + attempt.orderId);
          return;
        }
        if (attempt.state === 'REJECTED') {
          checkoutNotice.value = attempt.errorMessage || '本次未能下单，可以重新参与';
          toast.warning(checkoutNotice.value);
          if (canContinue()) await refresh();
          return;
        }
        if (poll === 30 || Date.now() >= deadline) break;
        const next = await request.get<CheckoutReceipt>('/seckill/getCheckoutStatus', { params: { jobId: attempt.jobId }, silentError: true });
        accept(next);
        if (next.state === 'SUCCEEDED' || next.state === 'REJECTED') continue;
        const backoff = Math.min(8000, 750 * 1.7 ** poll) * (0.8 + Math.random() * 0.4);
        await pause(Math.max(0, Math.min(backoff, deadline - Date.now())));
      }
      checkoutNotice.value = '排队记录已保存，点击“查看排队结果”继续确认';
    } catch (failure: unknown) {
      const business = failure as { code?: number; info?: string };
      // Only a definitive pre-acceptance business refusal permits a new key on the next explicit click.
      // Timeouts, 5xx responses and failed status reads preserve the original receipt and request key.
      if (!attempt.jobId && Number(business.code) >= 400 && Number(business.code) < 500) {
        attempt.state = 'REJECTED'; attempt.errorMessage = business.info;
        remember(activityId, attempt);
        checkoutNotice.value = business.info || '本次请求未受理，请稍后重新参与';
      } else {
        checkoutNotice.value = '结果暂未确认，排队记录已保存，请继续查看原请求';
      }
      if (canContinue()) toast.warning(checkoutNotice.value);
    } finally {
      wake?.();
      if (activeRun === run) { activeRun = undefined; buying.value = ''; }
    }
  }

  async function buy(c: Campaign) {
    if (buying.value) return;
    const originRoute = router.currentRoute.value.fullPath;
    buying.value = c.activityId;
    try {
      await refresh();
      if (error.value || !mounted || document.visibilityState !== 'visible' || router.currentRoute.value.fullPath !== originRoute) return;
      const fresh = activities.value.find(item => item.activityId === c.activityId);
      if (!fresh) return;
      c = fresh;
      const pending = checkouts.value[c.activityId];
      if (pending && queued(c)) { await runAttempt(c.activityId, pending); return; }
      if (c.myOrderId && (c.myOrderStatus === 0 || (c.participationCount ?? 0) >= c.perOwnerLimit)) {
        await router.push('/order/' + c.myOrderId); return;
      }
      if (status(c) !== 'live') return;
      const addressRows = await addressApi.loadDataList();
      const address = Array.isArray(addressRows) ? addressRows.find(row => row.addressId === loadCheckoutSelectedAddress()) || addressRows.find(row => row.defaultType === 1) || addressRows[0] : null;
      if (!address) {toast.info('请先添加收货地址');await router.push({path:'/address',query:{from:'seckill',returnTo:originRoute}});return;}
      saveCheckoutSelectedAddress(address.addressId);
      if (!await confirmAction(`收货至：${address.addressee} · ${address.phone}\n${address.address}\n按活动价 ¥${Number(c.price).toFixed(2)} 参与抢购？`, {title:'确认抢购',confirmButtonText:'确认地址并参与',cancelButtonText:'更换地址'})) {
        await router.push({path:'/address',query:{from:'seckill',returnTo:originRoute}});return;
      }
      // A fresh authoritative activity read confirmed there is no blocking unpaid/limit order.
      // Completed or rejected attempts get a new key only on this explicit purchase click.
      const prior = checkouts.value[c.activityId];
      let oldKey: string | null = null;
      try { oldKey = !prior ? sessionStorage.getItem('seckill-request:' + c.activityId) : null; } catch { /* Preserve the in-memory key if storage is disabled. */ }
      const attempt: CheckoutAttempt = { key: oldKey || crypto.randomUUID(), state: 'UNCONFIRMED', originRoute, addressId:address.addressId };
      remember(c.activityId, attempt);
      await runAttempt(c.activityId, attempt);
    } catch {
      // request already reports the business error; refresh the remaining allocation.
    } finally { buying.value = ''; }
  }
  return { activities: readonly(activities), now: readonly(now), loading: readonly(loading), error: readonly(error),
    buying: readonly(buying), checkoutNotice: readonly(checkoutNotice), queued, refresh, campaignFor, status, countdown, buy };
}
