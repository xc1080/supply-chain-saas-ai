// Run with: node scripts/verify-campaign-polling.mjs
// Real Vue lifecycles, deterministic clocks and isolated HTTP/storage boundaries.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import ts from 'typescript';
import * as Vue from 'vue';

function harness() {
  let instant = Date.parse('2026-10-08T10:00:00');
  class Clock extends Date {
    constructor(...args) { super(...(args.length ? args : [instant])); }
    static now() { return instant; }
  }
  let nextId = 0;
  const timers = new Map();
  const listeners = new Set();
  const saved = new Map();
  const later = (fn, delay, repeat = 0) => {
    const id = ++nextId;
    timers.set(id, { fn, at: instant + delay, repeat });
    return id;
  };
  const document = {
    visibilityState: 'visible',
    addEventListener: (_, callback) => listeners.add(callback),
    removeEventListener: (_, callback) => listeners.delete(callback)
  };
  const sessionStorage = {
    get length() { return saved.size; },
    key: index => [...saved.keys()][index],
    getItem: key => saved.get(key) ?? null,
    setItem: (key, value) => saved.set(key, value)
  };
  const math = Object.create(Math);
  math.random = () => 0.5;
  const globals = {
    Date: Clock, Math: math, document, sessionStorage, console,
    crypto: { randomUUID: () => 'request-1' },
    setTimeout: (fn, delay) => later(fn, delay),
    clearTimeout: id => timers.delete(id),
    setInterval: (fn, delay) => later(fn, delay, delay),
    clearInterval: id => timers.delete(id)
  };
  const host = Vue.createRenderer({
    patchProp() {}, insert(child, parent) { (parent.children ||= []).push(child); }, remove() {},
    createElement: tag => ({ tag }), createText: text => ({ text }), createComment: text => ({ text }),
    setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null
  });
  async function flush() {
    for (let turn = 0; turn < 20; turn++) await Promise.resolve();
    await Vue.nextTick();
  }
  async function advance(duration) {
    const end = instant + duration;
    for (;;) {
      const due = [...timers.entries()].filter(([, timer]) => timer.at <= end)
        .sort((left, right) => left[1].at - right[1].at)[0];
      if (!due) break;
      const [id, timer] = due;
      instant = timer.at;
      if (timer.repeat) timer.at += timer.repeat;
      else timers.delete(id);
      timer.fn();
      await flush();
    }
    instant = end;
    await flush();
  }
  async function visible(value) {
    document.visibilityState = value ? 'visible' : 'hidden';
    for (const callback of [...listeners]) callback();
    await flush();
  }
  return { Clock, globals, host, timers, listeners, saved, flush, advance, visible };
}

function compile(source) {
  return ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS }
  }).outputText;
}

function campaignFixture({ restored = false } = {}) {
  const test = harness();
  const calls = [];
  const pollStates = restored ? [] : ['PENDING', 'PROCESSING', 'SUCCEEDED'];
  const receipt = state => ({ jobId: 'a'.repeat(32), state,
    ...(state === 'SUCCEEDED' ? { orderId: 'SC-CHECK-1' } : {}) });
  const campaign = {
    activityId: 'act1', productId: '1', productName: 'Lamp', title: 'Offer', price: 99,
    remaining: 5, capacity: 5, perOwnerLimit: 1, startsAt: '2026-10-08 09:00:00',
    endsAt: '2026-10-08 14:00:00', cover: '/demo.svg'
  };
  const request = {
    async get(path, config) {
      calls.push({ method: 'GET', path, config, at: test.Clock.now() });
      if (path === '/seckill/listActivities') return [{ ...campaign }];
      if (path === '/seckill/myParticipation') return [];
      if (path === '/seckill/getCheckoutStatus') return receipt(pollStates.shift() || 'PROCESSING');
      throw new Error(`Unexpected GET ${path}`);
    },
    async post(path, body) {
      calls.push({ method: 'POST', path, body, at: test.Clock.now() });
      if (path === '/seckill/submitOrder') return receipt('PENDING');
      if (path === '/order/getOrderInfo') return { orderStatus: 0 };
      throw new Error(`Unexpected POST ${path}`);
    }
  };
  const router = { currentRoute: Vue.ref({ fullPath: '/activities' }),
    async push(path) { this.currentRoute.value = { fullPath: path }; } };
  const module = { exports: {} };
  const source = fs.readFileSync(new URL('../src/composables/useCampaigns.ts', import.meta.url), 'utf8');
  vm.runInNewContext(compile(source), { ...test.globals, exports: module.exports, module,
    require: id => {
      if (id === 'vue') return Vue;
      if (id === 'vue-router') return { useRouter: () => router };
      if (id === '@/api/http') return { __esModule: true, default: request };
      if (id === '@/integrations/demo') return { DEMO_MODE: true };
      if (id === '@/utils/toast') return { toast: { info() {}, warning() {} } };
      throw new Error(`Unexpected dependency ${id}`);
    }
  });
  if (restored) test.saved.set('seckill-checkout:act1', JSON.stringify({
    key: 'original-key', jobId: 'a'.repeat(32), state: 'PENDING', originRoute: '/activities'
  }));
  let state;
  const Consumer = { setup() {
    const current = module.exports.useCampaigns();
    state ||= current;
    return () => Vue.h('div'); } };
  const app = test.host.createApp({ render: () => Vue.h('main', [Vue.h(Consumer), Vue.h(Consumer)]) });
  app.mount({});
  return { ...test, app, router, calls, get state() { return state; },
    count: path => calls.filter(call => call.path === path).length };
}

async function verifyCampaignSharingAndRecovery() {
  const test = campaignFixture();
  await test.flush();
  assert.equal(test.count('/seckill/listActivities'), 1, 'Mounted cards share one public request');
  assert.equal(test.count('/seckill/myParticipation'), 1, 'Mounted cards share one personal request');
  await test.advance(45000);
  assert.equal(test.count('/seckill/listActivities'), 2);
  assert.equal(test.count('/seckill/myParticipation'), 1, 'Public polling must not poll personal state');
  await test.visible(false);
  const hiddenCount = test.calls.length;
  await test.advance(90000);
  assert.equal(test.calls.length, hiddenCount, 'Hidden pages must stop requests');
  await test.visible(true);
  assert.equal(test.count('/seckill/myParticipation'), 2);
  assert.equal(test.count('/seckill/listActivities'), 3);
  const buying = test.state.buy(test.state.activities.value[0]);
  await test.flush();
  assert.equal(test.count('/seckill/submitOrder'), 1);
  assert.equal(test.count('/seckill/getCheckoutStatus'), 1);
  const original = JSON.parse(test.saved.get('seckill-checkout:act1'));
  await test.advance(750);
  assert.equal(test.count('/seckill/getCheckoutStatus'), 2);
  await test.visible(false);
  await buying;
  const paused = JSON.parse(test.saved.get('seckill-checkout:act1'));
  assert.equal(paused.key, original.key);
  assert.equal(paused.jobId, 'a'.repeat(32));
  const pausedCount = test.calls.length;
  await test.advance(60000);
  assert.equal(test.calls.length, pausedCount);
  await test.visible(true);
  await test.flush();
  assert.equal(test.count('/seckill/submitOrder'), 1, 'Resuming a known job must not submit a new order');
  assert.equal(test.router.currentRoute.value.fullPath, '/order/SC-CHECK-1');
  test.app.unmount();
  await test.flush();
  const stoppedCount = test.calls.length;
  await test.advance(120000);
  assert.equal(test.calls.length, stoppedCount);
  assert.equal(test.timers.size, 0);
  console.log('PASS campaigns: shared public/personal requests, public-only 45s refresh, visibility pause, durable key/job recovery without resubmit.');
}

async function verifyPendingRecoveryIsBounded() {
  const test = campaignFixture({ restored: true });
  await test.flush();
  await test.advance(60000);
  const polls = test.count('/seckill/getCheckoutStatus');
  assert.ok(polls > 1 && polls < 15, 'Pending jobs must use backoff');
  assert.equal(test.count('/seckill/submitOrder'), 0);
  await test.advance(60000);
  assert.equal(test.count('/seckill/getCheckoutStatus'), polls, 'Multiple consumers must not silently restart a timed-out recovery');
  assert.equal(JSON.parse(test.saved.get('seckill-checkout:act1')).key, 'original-key');
  test.app.unmount();
  await test.flush();
  assert.equal(test.timers.size, 0);
  console.log('PASS pending recovery: bounded polling, no automatic retry storm, original key retained.');
}

async function verifyOrderLifecycle() {
  const test = harness();
  let reads = 0;
  const stored = { orderId: 'SC1', orderStatus: 0, amount: 129, payOrderId: 'SC1',
    legacy: false, expiresAt: new test.Clock(test.Clock.now() + 900000).toISOString() };
  const orderApi = {
    async getMyOrderDetail() { reads++; return { ...stored }; },
    async sandboxPay() { stored.orderStatus = 1; },
    async receiveOrder() { stored.orderStatus = 3; },
    async cancelOrder() { stored.orderStatus = 4; }
  };
  const route = Vue.reactive({ params: { orderId: 'SC1' } });
  const module = { exports: {} };
  const source = fs.readFileSync(new URL('../src/views/OrderDetailView.vue', import.meta.url), 'utf8')
    .split('<script setup lang="ts">')[1].split('</script>')[0];
  const setup = vm.runInNewContext(`(function(){${compile(source)};return {order,load,sandboxPay,confirmReceive};})`, {
    ...test.globals, module, exports: module.exports,
    require: id => {
      if (id === 'vue') return Vue;
      if (id === 'vue-router') return { useRoute: () => route, useRouter: () => ({ push() {} }) };
      if (id === '@/integrations/demo') return { DEMO_MODE: true };
      if (id === '@/api/modules') return { orderApi };
      if (id === '@/composables/pullRefresh') return { usePageRefresh() {} };
      if (id === '@/utils/confirm') return { confirmAction: async () => true };
      if (id === '@/utils/toast') return { toast: { success() {}, warning() {} } };
      if (id === '@/constants/backendEnums') return { displayOrderStatusText: () => '' };
      if (id === '@/utils/orderAmount') return { hasOrderCouponDiscount: () => false, orderCouponSummaryText: () => '' };
      if (id === '@/utils/demoOrder') return { demoOrderNote: () => '' };
      if (id.endsWith('.vue') || id === '@element-plus/icons-vue') return { __esModule: true, default: {} };
      throw new Error(`Unexpected dependency ${id}`);
    }
  });
  let state;
  const app = test.host.createApp({ setup() { state = setup(); return () => Vue.h('div'); } });
  app.mount({});
  await test.flush();
  assert.equal(reads, 1);
  await test.advance(40000);
  assert.equal(reads, 2);
  await test.visible(false);
  await test.advance(90000);
  assert.equal(reads, 2);
  await test.visible(true);
  assert.equal(reads, 3);
  stored.expiresAt = new test.Clock(test.Clock.now() + 2000).toISOString();
  await state.load(true);
  const beforeExpiry = reads;
  await test.advance(2000);
  assert.equal(reads, beforeExpiry + 1, 'Verify the local expiry once immediately');
  await test.advance(10000);
  assert.equal(reads, beforeExpiry + 1, 'Expired countdown must not create a 3s polling loop');
  await state.sandboxPay();
  assert.equal(state.order.value.orderStatus, 1);
  stored.orderStatus = 2;
  await test.advance(40000);
  assert.equal(state.order.value.orderStatus, 2);
  await state.confirmReceive();
  assert.equal(state.order.value.orderStatus, 3);
  const completedReads = reads;
  await test.advance(120000);
  assert.equal(reads, completedReads);
  assert.equal(test.timers.size, 0);
  app.unmount();
  await test.flush();
  assert.equal(test.listeners.size, 0);
  console.log('PASS orders: slow status polling, hidden pause, single expiry check, immediate actions, completed orders stop timers.');
}

await verifyCampaignSharingAndRecovery();
await verifyPendingRecoveryIsBounded();
await verifyOrderLifecycle();
