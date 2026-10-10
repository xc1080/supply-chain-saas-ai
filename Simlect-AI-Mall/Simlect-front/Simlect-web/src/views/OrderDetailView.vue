<!-- [zh] SFC 区块开始（单文件组件 template/script/style 三段） -->
<template>
  <!-- [zh] 开始标签 `<div>` -->
  <div class="order-detail-page" v-loading="loading">
    <!-- [zh] SFC 区块开始（单文件组件 template/script/style 三段） -->
    <template v-if="order">
      <!-- [zh] 开始标签 `<section>` -->
      <section class="status-card card">
        <!-- [zh] 开始标签 `<span>` -->
        <span class="status-text" :class="statusClass">{{ displayStatus }}</span>
        <!-- [zh] 开始标签 `<p>` -->
        <p v-if="DEMO_MODE" class="status-sub">{{ demoOrderNote(order) }}</p>
        <el-steps v-if="DEMO_MODE && !order.legacy && Number(order.orderStatus) !== 4" :active="Number(order.orderStatus)" finish-status="success" align-center style="margin-top: 20px"><el-step title="沙箱支付" /><el-step title="发货" /><el-step title="收货" /></el-steps>
        <p v-if="order.subject" class="status-sub">{{ order.subject }}</p>
        <el-button v-if="DEMO_MODE" size="small" text :loading="loading" @click="load">刷新订单</el-button>
      <!-- [zh] 闭合标签 `</section>` -->
      </section>

      <!-- [zh] 开始标签 `<section>` -->
      <section class="info-card card">
        <!-- [zh] 开始标签 `<h3>` -->
        <h3 class="section-title">订单信息</h3>
        <!-- [zh] 开始标签 `<dl>` -->
        <dl class="info-list">
          <!-- [zh] 开始标签 `<div>` -->
          <div class="info-row">
            <!-- [zh] 开始标签 `<dt>` -->
            <dt>订单编号</dt>
            <!-- [zh] 开始标签 `<dd>` -->
            <dd>{{ order.orderId }}</dd>
          <!-- [zh] 闭合标签 `</div>` -->
          </div>
          <!-- [zh] 开始标签 `<div>` -->
          <div class="info-row">
            <!-- [zh] 开始标签 `<dt>` -->
            <dt>下单时间</dt>
            <!-- [zh] 开始标签 `<dd>` -->
            <dd>{{ formatTime(order.orderTime) }}</dd>
          <!-- [zh] 闭合标签 `</div>` -->
          </div>
          <!-- [zh] 开始标签 `<div>` -->
          <div v-if="order.payOrderId && !DEMO_MODE" class="info-row">
            <!-- [zh] 开始标签 `<dt>` -->
            <dt>支付单号</dt>
            <!-- [zh] 开始标签 `<dd>` -->
            <dd>{{ order.payOrderId }}</dd>
          <!-- [zh] 闭合标签 `</div>` -->
          </div>
          <!-- [zh] 开始标签 `<div>` -->
          <div v-if="order.payChannel" class="info-row">
            <!-- [zh] 开始标签 `<dt>` -->
            <dt>支付方式</dt>
            <!-- [zh] 开始标签 `<dd>` -->
            <dd>{{ order.payChannel }}</dd>
          <!-- [zh] 闭合标签 `</div>` -->
          </div>
          <template v-if="DEMO_MODE && !order.legacy">
            <div class="info-row"><dt>付款渠道</dt><dd>本地支付沙箱（不扣真钱）</dd></div>
            <div v-if="order.orderStatus === 0 && order.expiresAt" class="info-row"><dt>付款剩余</dt><dd role="timer">{{ paymentCountdown }}</dd></div>
            <div v-if="order.dispatchPromise?.dispatchDate" class="info-row"><dt>计划发货日期</dt><dd>{{ order.dispatchPromise.dispatchDate }}<el-tag v-if="order.dispatchPromise.status === 'OVERDUE'" type="warning" size="small">已逾期</el-tag></dd></div>
            <div v-if="order.paidTime" class="info-row"><dt>付款时间</dt><dd>{{ formatTime(order.paidTime) }}</dd></div>
            <div v-if="order.shippedTime" class="info-row"><dt>发货时间</dt><dd>{{ formatTime(order.shippedTime) }}</dd></div>
            <div v-if="order.trackingNo" class="info-row"><dt>测试运单</dt><dd>{{ order.carrier || '模拟物流' }} · {{ order.trackingNo }}</dd></div>
            <div v-if="order.receivedTime" class="info-row"><dt>收货时间</dt><dd>{{ formatTime(order.receivedTime) }}</dd></div>
          </template>
        <!-- [zh] 闭合标签 `</dl>` -->
        </dl>
        <details v-if="DEMO_MODE && !order.legacy && (order.transactionId || order.receiptId)" style="margin-top: 16px; overflow-wrap: anywhere">
          <summary>交易与出库记录</summary>
          <dl class="info-list">
            <div v-if="order.transactionId" class="info-row"><dt>沙箱交易号</dt><dd>{{ order.transactionId }}</dd></div>
            <div v-if="order.receiptId" class="info-row"><dt>出库单号</dt><dd>{{ order.receiptId }}</dd></div>
          </dl>
        </details>
      <!-- [zh] 闭合标签 `</section>` -->
      </section>

      <!-- [zh] 开始标签 `<section>` -->
      <section class="items-card card">
        <!-- [zh] 开始标签 `<h3>` -->
        <h3 class="section-title">{{ isCouponOrder ? '优惠券信息' : '商品明细' }}</h3>
        <!-- [zh] 开始标签 `<div>` -->
        <div v-if="itemList.length" class="goods-list">
          <!-- [zh] 开始标签 `<component>` -->
          <component
            :is="isCouponOrder ? 'div' : 'button'"
            v-for="item in itemList"
            :key="item.orderItemId"
            :type="isCouponOrder ? undefined : 'button'"
            class="goods-row"
            @click="!isCouponOrder && goProduct(item.productId)"
          >
            <!-- [zh] 开始标签 `<div>` -->
            <div class="goods-cover-col" :class="{ 'is-coupon': isCouponOrder }">
              <!-- [zh] 开始标签 `<el-icon>` -->
              <el-icon v-if="isCouponOrder" class="coupon-icon"><Ticket /></el-icon>
              <!-- [zh] 开始标签 `<ProductImage>` -->
              <ProductImage v-else :source="item.cover" class="goods-cover" />
            <!-- [zh] 闭合标签 `</div>` -->
            </div>
            <!-- [zh] 开始标签 `<div>` -->
            <div class="goods-info">
              <!-- [zh] 开始标签 `<p>` -->
              <p class="goods-name">{{ item.productName }}</p>
              <!-- [zh] 开始标签 `<p>` -->
              <p v-if="item.propertyInfo && !isCouponOrder" class="goods-sku">{{ item.propertyInfo }}</p>
              <p v-if="DEMO_MODE && item.orderedQuantity != null" class="goods-sku">已发 {{ item.shippedQuantity ?? 0 }} · 待发 {{ item.unshippedQuantity ?? 0 }} · 已退 {{ item.returnedQuantity ?? 0 }} · 已取消 {{ item.cancelledQuantity ?? 0 }}</p>
              <!-- [zh] 开始标签 `<p>` -->
              <p v-if="!DEMO_MODE" class="goods-remark">买家备注：{{ item.remark?.trim() || '暂无' }}</p>
              <!-- [zh] 开始标签 `<OrderItemIdText>` -->
              <OrderItemIdText v-if="!DEMO_MODE" :id="item.orderItemId" />
            <!-- [zh] 闭合标签 `</div>` -->
            </div>
            <!-- [zh] 开始标签 `<div>` -->
            <div class="goods-price">
              <!-- [zh] 开始标签 `<span>` -->
              <span class="price"><template v-if="DEMO_MODE && item.amountBreakdown">原价 </template>¥{{ formatMoney(item.itemAmount) }}</span>
              <!-- [zh] 开始标签 `<span>` -->
              <span class="qty">×{{ item.buyCount }}</span>
            <!-- [zh] 闭合标签 `</div>` -->
            </div>
            <!-- [zh] 开始标签 `<div>` -->
            <div
              v-if="canRefundItem(item)"
              class="goods-action"
            >
              <!-- [zh] 开始标签 `<el-button>` -->
              <el-button size="small" text type="danger" @click.stop="refundItem(item.orderItemId)">
                <!-- [zh] 模板内容：`退款` -->
                退款
              <!-- [zh] 闭合标签 `</el-button>` -->
              </el-button>
            <!-- [zh] 闭合标签 `</div>` -->
            </div>
          <!-- [zh] 闭合标签 `</component>` -->
          </component>
        <!-- [zh] 闭合标签 `</div>` -->
        </div>
        <!-- [zh] 开始标签 `<el-empty>` -->
        <el-empty v-else :description="isCouponOrder ? '暂无优惠券信息' : '暂无商品明细'" :image-size="64" />
      <!-- [zh] 闭合标签 `</section>` -->
      </section>

      <!-- [zh] 开始标签 `<section>` -->
      <section v-if="!isCouponOrder && hasOrderCouponDiscount(order)" class="coupon-card card">
        <!-- [zh] 开始标签 `<h3>` -->
        <h3 class="section-title">优惠券</h3>
        <!-- [zh] 开始标签 `<dl>` -->
        <dl class="info-list">
          <!-- [zh] 开始标签 `<div>` -->
          <div class="info-row">
            <!-- [zh] 开始标签 `<dt>` -->
            <dt>使用优惠券</dt>
            <!-- [zh] 开始标签 `<dd>` -->
            <dd>{{ orderCouponSummaryText(order) }}</dd>
          <!-- [zh] 闭合标签 `</div>` -->
          </div>
        <!-- [zh] 闭合标签 `</dl>` -->
        </dl>
      <!-- [zh] 闭合标签 `</section>` -->
      </section>

      <!-- [zh] 开始标签 `<section>` -->
      <section class="amount-card card">
        <!-- [zh] 开始标签 `<OrderAmountSummary>` -->
        <OrderAmountSummary :order="order" />
      <!-- [zh] 闭合标签 `</section>` -->
      </section>

      <section v-if="DEMO_MODE && order.shipments?.length" class="info-card card">
        <h3 class="section-title">分次发货</h3>
        <ShipmentTracking v-for="shipment in order.shipments" :key="shipment.shipmentId" :shipment="shipment" :items="itemList" />
      </section>

      <section v-if="DEMO_MODE && order.shippingAddress" class="info-card card">
        <h3 class="section-title">收货信息</h3>
        <p>{{ order.shippingAddress.addressee }} · {{ order.shippingAddress.phone }}</p>
        <p>{{ order.shippingAddress.address }}</p>
      </section>
      <OrderAfterSales v-if="DEMO_MODE" :order="order" @updated="load" />

      <!-- [zh] 开始标签 `<div>` -->
      <div v-if="showPayBtn || showLogisticsBtn || showSandboxPayBtn || showReceiveBtn || queryablePayment || (DEMO_MODE && order.orderStatus === 0)" class="detail-actions">
        <el-button v-if="DEMO_MODE && order.orderStatus === 0" round :disabled="actionLoading" @click="cancelDemoOrder">取消订单</el-button>
        <el-button v-if="showSandboxPayBtn" type="primary" round :loading="actionLoading" @click="sandboxPay">沙箱支付 ¥{{ formatMoney(order.amount) }}</el-button>
        <el-button v-if="queryablePayment" type="primary" round :loading="actionLoading" @click="queryPayment">查询支付结果</el-button>
        <el-button v-if="showReceiveBtn" type="primary" round :loading="actionLoading" @click="confirmReceive">确认收货</el-button>
        <!-- [zh] 开始标签 `<el-button>` -->
        <el-button v-if="showPayBtn" type="primary" round @click="goPay">去支付</el-button>
        <!-- [zh] 开始标签 `<el-button>` -->
        <el-button v-if="showLogisticsBtn" type="primary" plain round @click="goLogistics">查看物流</el-button>
      <!-- [zh] 闭合标签 `</div>` -->
      </div>
    </template>

    <el-empty v-else-if="!loading" description="订单不存在或无权查看" />
  </div>
</template>

<script setup lang="ts">
import { DEMO_MODE } from '@/integrations/demo';
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Ticket } from '@element-plus/icons-vue';
import ProductImage from '@/components/common/ProductImage.vue';
import OrderAmountSummary from '@/components/business/OrderAmountSummary.vue';
import OrderItemIdText from '@/components/business/OrderItemIdText.vue';
import { orderApi } from '@/api/modules';
import { displayOrderStatusText } from '@/constants/backendEnums';
import { usePageRefresh } from '@/composables/pullRefresh';
import { confirmAction } from '@/utils/confirm';
import { toast } from '@/utils/toast';
import { hasOrderCouponDiscount, orderCouponSummaryText } from '@/utils/orderAmount';
import { demoOrderNote, paymentNeedsQuery, paymentState, paymentStatusLabel } from '@/utils/demoOrder';
import OrderAfterSales from '@/components/business/OrderAfterSales.vue';
import ShipmentTracking from '@/components/business/ShipmentTracking.vue';

const route = useRoute();
const router = useRouter();
const loading = ref(true);
const order = ref<Record<string, any> | null>(null);
const actionLoading = ref(false);
const clock = ref(Date.now());
let orderTimer: ReturnType<typeof setTimeout> | undefined;
let clockTimer: ReturnType<typeof setInterval> | undefined;
let loadFlight: Promise<void> | undefined;
let active = false;
let refreshFailures = 0;
let expiryRefreshedFor = '';
const paymentSeconds = computed(() => {
  const deadline = order.value?.expiresAt;
  if (!deadline) return null;
  return Math.max(0, Math.ceil((new Date(String(deadline).replace(' ', 'T')).getTime() - clock.value) / 1000));
});
const paymentCountdown = computed(() => paymentSeconds.value === null ? '' : `${Math.floor(paymentSeconds.value / 60)}:${String(paymentSeconds.value % 60).padStart(2, '0')}`);

const itemList = computed(() => {
  const list = order.value?.orderItemList;
  return Array.isArray(list) ? list : [];
});

const isCouponOrder = computed(() => String(order.value?.payScene) === '2');

const showPayBtn = computed(() => !DEMO_MODE && order.value?.orderStatus === 0 && order.value?.payOrderId);
const queryablePayment = computed(() => DEMO_MODE && !!order.value && paymentNeedsQuery(order.value));
const showSandboxPayBtn = computed(() => DEMO_MODE && order.value?.orderStatus === 0 && !order.value?.legacy && !queryablePayment.value && (paymentSeconds.value === null || paymentSeconds.value > 0));
const showReceiveBtn = computed(() => DEMO_MODE && order.value?.orderStatus === 2 && !order.value?.legacy && (Array.isArray(order.value?.availableActions) ? order.value.availableActions.includes('RECEIVE') : !order.value?.afterSalesId || order.value?.afterSalesStatus === 'REJECTED'));

const displayStatus = computed(() => {
  if (!order.value) return '';
  if (DEMO_MODE && paymentStatusLabel(order.value)) return paymentStatusLabel(order.value);
  if (isCouponOrder.value && order.value.orderStatus === 3) return '已完成';
  return displayOrderStatusText(order.value);
});

const statusClass = computed(() => {
  const o = order.value;
  if (!o) return '';
  if (o.orderStatus === 0) return 'is-wait-pay';
  if (o.orderStatus === 2) return 'is-shipped';
  if (o.orderStatus === 4 || o.orderStatus === 5) return 'is-cancel';
  return '';
});

const showLogisticsBtn = computed(() => {
  if (DEMO_MODE || isCouponOrder.value) return false;
  const s = order.value?.orderStatus;
  return s === 2 || s === 3 || s === 7;
});

const itemOrderStatus = (item: Record<string, any>) => Number(item.orderItemStatus ?? 1);

const canRefundItem = (item: Record<string, any>) => {
  if (DEMO_MODE || isCouponOrder.value) return false;
  const s = order.value?.orderStatus;
  return (s === 1 || s === 2) && itemOrderStatus(item) === 1;
};

const refundItem = async (orderItemId: string) => {
  const ok = await confirmAction('确定要申请退款吗？退款将按原支付方式退回。', {
    title: '申请退款',
    confirmButtonText: '申请退款'
  });
  if (!ok) return;
  await orderApi.refundOrder(orderItemId);
  toast.success('退款申请已提交');
  await load(true);
};

const formatMoney = (val: unknown) => Number(val ?? 0).toFixed(2);

const formatTime = (val: unknown) => {
  if (!val) return '--';
  if (typeof val === 'string') return val;
  const d = new Date(val as string | number);
  if (Number.isNaN(d.getTime())) return String(val);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
};

const goProduct = (productId: string) => {
  if (productId) router.push(`/product/${productId}`);
};

const goLogistics = () => {
  router.push(`/order/${route.params.orderId}/logistics`);
};

const goPay = () => {
  const payOrderId = order.value?.payOrderId;
  if (payOrderId) router.push(`/payment/${payOrderId}`);
};

const cancelDemoOrder = async () => {
  if (!order.value || actionLoading.value) return;
  const note = order.value.legacy ? '确定取消这个历史演示订单吗？' : '取消未付款订单后，将释放预留库存。确定取消吗？';
  if (!await confirmAction(note, { title: '取消订单', confirmButtonText: '取消订单' })) return;
  actionLoading.value = true;
  try {
    await orderApi.cancelOrder(String(order.value.orderId));
    toast.success('订单已取消');
    await load(true);
  } finally { actionLoading.value = false; }
};

const sandboxPay = async () => {
  if (!order.value || actionLoading.value) return;
  if (!await confirmAction(`使用本地支付沙箱模拟支付 ¥${formatMoney(order.value.amount)}，不会扣真钱。付款成功后可在供应链工作台发货。`, {
    title: '本地沙箱支付', confirmButtonText: '模拟支付成功'
  })) return;
  actionLoading.value = true;
  try {
    const attempt = paymentState(order.value) === 'FAILED' ? `retry-${order.value.paymentOperation?.operationId}` : undefined;
    await orderApi.sandboxPay(String(order.value.orderId), attempt);
    await load(true);
    if (order.value?.orderStatus === 1) toast.success('沙箱付款成功，等待后台发货');
    else if (order.value?.closeReason === 'PAYMENT_TIMEOUT') toast.warning('付款超时，订单已关闭');
  } finally { actionLoading.value = false; }
};

const queryPayment = async () => {
  if (!order.value || !queryablePayment.value || actionLoading.value) return;
  actionLoading.value = true;
  try {
    await orderApi.querySandboxPayment(String(order.value.orderId), String(order.value.paymentOperation.operationId));
    await load(true);
    if (order.value && paymentNeedsQuery(order.value)) toast.info('支付渠道仍在处理');
    else if (paymentState(order.value || {}) === 'COMPENSATED') toast.success('付款已退回');
    else if (order.value?.orderStatus === 1) toast.success('付款成功，等待发货');
    else toast.info('支付结果已更新');
  } finally { actionLoading.value = false; }
};

const confirmReceive = async () => {
  if (!order.value || actionLoading.value) return;
  if (!await confirmAction('这是一笔模拟物流订单。确认收货后订单将完成，确定继续吗？', {
    title: '确认收货', confirmButtonText: '确认收货'
  })) return;
  actionLoading.value = true;
  try {
    await orderApi.receiveOrder(String(order.value.orderId));
    await load(true);
    toast.success('已确认收货');
  } finally { actionLoading.value = false; }
};

const needsPolling = () => DEMO_MODE && !order.value?.legacy && ([0, 1, 2].includes(Number(order.value?.orderStatus)) || (order.value?.afterSalesCases || [order.value?.afterSale]).some((record: any) => ['REQUESTED','APPROVED','AWAITING_RETURN','RETURN_RECEIVED'].includes(String(record?.status))));
function stopTimers() {
  if (orderTimer) clearTimeout(orderTimer);
  if (clockTimer) clearInterval(clockTimer);
  orderTimer = undefined; clockTimer = undefined;
}
function tickClock() {
  clock.value = Date.now();
  const expiryKey = `${order.value?.orderId}:${order.value?.expiresAt}`;
  if (active && document.visibilityState === 'visible' && order.value?.orderStatus === 0
      && paymentSeconds.value === 0 && expiryRefreshedFor !== expiryKey && !actionLoading.value) {
    // Verify expiry once at the local deadline; the server owns closing the order.
    expiryRefreshedFor = expiryKey;
    void load(true, true).catch(() => { /* The slower status refresh can retry. */ });
  }
}
function scheduleRefresh() {
  stopTimers();
  if (!active || document.visibilityState !== 'visible' || !needsPolling()) return;
  if (order.value?.orderStatus === 0 && order.value?.expiresAt) {
    clock.value = Date.now();
    clockTimer = setInterval(tickClock, 1000);
  }
  const delay = Math.min(90000, (30000 + Math.random() * 15000) * 1.5 ** refreshFailures);
  orderTimer = setTimeout(() => {
    orderTimer = undefined;
    if (!active || document.visibilityState !== 'visible' || !needsPolling()) return;
    if (actionLoading.value) { scheduleRefresh(); return; }
    void load(false, true).catch(() => { /* Keep the last confirmed order; manual refresh is available. */ });
  }, delay);
}
const load = async (force = false, silent = false): Promise<void> => {
  if (loadFlight) {
    await loadFlight.catch(() => {});
    if (!force) return;
    if (loadFlight) return loadFlight;
  }
  const orderId = String(route.params.orderId);
  if (!silent) loading.value = true;
  const operation = (async () => {
    try {
      const result = await orderApi.getMyOrderDetail(orderId, { silentError: silent });
      if (String(route.params.orderId) === orderId) order.value = result || null;
      refreshFailures = 0;
    } catch (failure) { refreshFailures++; throw failure; }
    finally { loading.value = false; }
  })();
  loadFlight = operation;
  try { await operation; }
  finally { if (loadFlight === operation) loadFlight = undefined; scheduleRefresh(); }
};
function visibilityChanged() {
  stopTimers();
  if (!active || document.visibilityState !== 'visible') return;
  clock.value = Date.now();
  if (needsPolling() || !order.value) void load(false, true).catch(() => {});
}
function enter() {
  if (active) return;
  active = true;
  document.addEventListener('visibilitychange', visibilityChanged);
  if (document.visibilityState === 'visible') void load().catch(() => {});
}
function leave() {
  active = false; stopTimers(); document.removeEventListener('visibilitychange', visibilityChanged);
}
watch(() => route.params.orderId, () => {
  order.value = null; expiryRefreshedFor = ''; refreshFailures = 0; stopTimers();
  if (active && document.visibilityState === 'visible') void load(true).catch(() => {});
});
onMounted(enter); onActivated(enter);
onUnmounted(leave); onDeactivated(leave);
usePageRefresh(() => load(true));
</script>

/* [zh] 样式声明 */
<style scoped lang="scss">
/* [zh] 样式规则 `@use '@/styles/variables' as *;
.shipment-record { padding: 12px 0; border-bottom: 1px solid #eceef1; }
.shipment-record:last-child { border-bottom: 0; }
.shipment-record p { margin: 5px 0 0; font-size: 12px; color: #667085; }
` */
@use '@/styles/variables' as *;

/* [zh] 样式规则 `.order-detail-page {` */
.order-detail-page {
  /* [zh] 样式规则 `display: flex;` */
  display: flex;
  /* [zh] 样式规则 `flex-direction: column;` */
  flex-direction: column;
  /* [zh] 样式规则 `gap: 10px;` */
  gap: 10px;
  /* [zh] 样式规则 `padding-bottom: 16px;` */
  padding-bottom: 16px;
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.card {` */
.card {
  /* [zh] 样式规则 `padding: 14px 16px;` */
  padding: 14px 16px;
  /* [zh] 样式规则 `background: $color-card;` */
  background: $color-card;
  /* [zh] 样式规则 `border-radius: $radius-card;` */
  border-radius: $radius-card;
  /* [zh] 样式规则 `border: 1px solid $color-border-light;` */
  border: 1px solid $color-border-light;
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.status-card {` */
.status-card {
  /* [zh] 样式规则 `text-align: center;` */
  text-align: center;
  /* [zh] 样式规则 `padding: 20px 16px;` */
  padding: 20px 16px;

  /* [zh] 样式规则 `.status-text {` */
  .status-text {
    /* [zh] 样式规则 `font-size: 18px;` */
    font-size: 18px;
    /* [zh] 样式规则 `font-weight: 700;` */
    font-weight: 700;
    /* [zh] 样式规则 `color: $color-primary;` */
    color: $color-primary;

    /* [zh] 样式规则 `&.is-wait-pay {` */
    &.is-wait-pay {
      /* [zh] 样式规则 `color: $color-price;` */
      color: $color-price;
    /* [zh] 样式规则 `}` */
    }

    /* [zh] 样式规则 `&.is-shipped {` */
    &.is-shipped {
      /* [zh] 样式规则 `color: $color-primary;` */
      color: $color-primary;
    /* [zh] 样式规则 `}` */
    }

    /* [zh] 样式规则 `&.is-cancel {` */
    &.is-cancel {
      /* [zh] 样式规则 `color: $color-text-muted;` */
      color: $color-text-muted;
    /* [zh] 样式规则 `}` */
    }
  /* [zh] 样式规则 `}` */
  }

  /* [zh] 样式规则 `.status-sub {` */
  .status-sub {
    /* [zh] 样式规则 `margin: 8px 0 0;` */
    margin: 8px 0 0;
    /* [zh] 样式规则 `font-size: 13px;` */
    font-size: 13px;
    /* [zh] 样式规则 `color: $color-text-muted;` */
    color: $color-text-muted;
  /* [zh] 样式规则 `}` */
  }
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.section-title {` */
.section-title {
  /* [zh] 样式规则 `margin: 0 0 12px;` */
  margin: 0 0 12px;
  /* [zh] 样式规则 `font-size: 15px;` */
  font-size: 15px;
  /* [zh] 样式规则 `font-weight: 600;` */
  font-weight: 600;
  /* [zh] 样式规则 `color: $color-text-title;` */
  color: $color-text-title;
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.info-list {` */
.info-list {
  /* [zh] 样式规则 `margin: 0;` */
  margin: 0;
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.info-row {` */
.info-row {
  /* [zh] 样式规则 `display: flex;` */
  display: flex;
  /* [zh] 样式规则 `align-items: flex-start;` */
  align-items: flex-start;
  /* [zh] 样式规则 `justify-content: space-between;` */
  justify-content: space-between;
  /* [zh] 样式规则 `gap: 12px;` */
  gap: 12px;
  /* [zh] 样式规则 `padding: 8px 0;` */
  padding: 8px 0;
  /* [zh] 样式规则 `font-size: 13px;` */
  font-size: 13px;
  /* [zh] 样式规则 `border-bottom: 1px solid $color-border-l` */
  border-bottom: 1px solid $color-border-light;

  /* [zh] 样式规则 `&:last-child {` */
  &:last-child {
    /* [zh] 样式规则 `border-bottom: none;` */
    border-bottom: none;
  /* [zh] 样式规则 `}` */
  }

  /* [zh] 样式规则 `dt {` */
  dt {
    /* [zh] 样式规则 `flex-shrink: 0;` */
    flex-shrink: 0;
    /* [zh] 样式规则 `margin: 0;` */
    margin: 0;
    /* [zh] 样式规则 `color: $color-text-muted;` */
    color: $color-text-muted;
    /* [zh] 样式规则 `font-weight: 400;` */
    font-weight: 400;
  /* [zh] 样式规则 `}` */
  }

  /* [zh] 样式规则 `dd {` */
  dd {
    /* [zh] 样式规则 `margin: 0;` */
    margin: 0;
    /* [zh] 样式规则 `text-align: right;` */
    text-align: right;
    /* [zh] 样式规则 `color: $color-text-title;` */
    color: $color-text-title;
    /* [zh] 样式规则 `word-break: break-all;` */
    word-break: break-all;
  /* [zh] 样式规则 `}` */
  }
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.goods-list {` */
.goods-list {
  /* [zh] 样式规则 `display: flex;` */
  display: flex;
  /* [zh] 样式规则 `flex-direction: column;` */
  flex-direction: column;
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.goods-row {` */
.goods-row {
  /* [zh] 样式规则 `display: flex;` */
  display: flex;
  /* [zh] 样式规则 `align-items: flex-start;` */
  align-items: flex-start;
  /* [zh] 样式规则 `gap: 10px;` */
  gap: 10px;
  /* [zh] 样式规则 `width: 100%;` */
  width: 100%;
  /* [zh] 样式规则 `padding: 10px 0;` */
  padding: 10px 0;
  /* [zh] 样式规则 `border: none;` */
  border: none;
  /* [zh] 样式规则 `border-bottom: 1px solid $color-border-l` */
  border-bottom: 1px solid $color-border-light;
  /* [zh] 样式规则 `background: transparent;` */
  background: transparent;
  /* [zh] 样式规则 `text-align: left;` */
  text-align: left;
  /* [zh] 样式规则 `cursor: pointer;` */
  cursor: pointer;

  /* [zh] 样式规则 `&:last-child {` */
  &:last-child {
    /* [zh] 样式规则 `border-bottom: none;` */
    border-bottom: none;
  /* [zh] 样式规则 `}` */
  }

  /* [zh] 样式规则 `&:active {` */
  &:active {
    /* [zh] 样式规则 `background: rgba($color-primary, 0.04);` */
    background: rgba($color-primary, 0.04);
  /* [zh] 样式规则 `}` */
  }
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.goods-cover-col {` */
.goods-cover-col {
  /* [zh] 样式规则 `flex: 0 0 72px;` */
  flex: 0 0 72px;
  /* [zh] 样式规则 `width: 72px;` */
  width: 72px;
  /* [zh] 样式规则 `aspect-ratio: 1;` */
  aspect-ratio: 1;
  /* [zh] 样式规则 `border-radius: $radius-xs;` */
  border-radius: $radius-xs;
  /* [zh] 样式规则 `overflow: hidden;` */
  overflow: hidden;

  /* [zh] 样式规则 `:deep(.product-image) {` */
  :deep(.product-image) {
    /* [zh] 样式规则 `width: 100% !important;` */
    width: 100% !important;
    /* [zh] 样式规则 `height: 100% !important;` */
    height: 100% !important;
    /* [zh] 样式规则 `border-radius: $radius-xs;` */
    border-radius: $radius-xs;
  /* [zh] 样式规则 `}` */
  }

  /* [zh] 样式规则 `&.is-coupon {` */
  &.is-coupon {
    /* [zh] 样式规则 `display: flex;` */
    display: flex;
    /* [zh] 样式规则 `align-items: center;` */
    align-items: center;
    /* [zh] 样式规则 `justify-content: center;` */
    justify-content: center;
    /* [zh] 样式规则 `background: linear-gradient(135deg, rgba` */
    background: linear-gradient(135deg, rgba($color-primary, 0.12), rgba($color-price, 0.1));

    /* [zh] 样式规则 `.coupon-icon {` */
    .coupon-icon {
      /* [zh] 样式规则 `font-size: 32px;` */
      font-size: 32px;
      /* [zh] 样式规则 `color: $color-primary;` */
      color: $color-primary;
    /* [zh] 样式规则 `}` */
    }
  /* [zh] 样式规则 `}` */
  }
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.goods-info {` */
.goods-info {
  /* [zh] 样式规则 `flex: 1;` */
  flex: 1;
  /* [zh] 样式规则 `min-width: 0;` */
  min-width: 0;

  /* [zh] 样式规则 `.goods-name {` */
  .goods-name {
    /* [zh] 样式规则 `margin: 0 0 4px;` */
    margin: 0 0 4px;
    /* [zh] 样式规则 `font-size: 13px;` */
    font-size: 13px;
    /* [zh] 样式规则 `line-height: 1.4;` */
    line-height: 1.4;
    /* [zh] 样式规则 `color: $color-text-title;` */
    color: $color-text-title;
    /* [zh] 样式规则 `display: -webkit-box;` */
    display: -webkit-box;
    /* [zh] 样式规则 `-webkit-line-clamp: 2;` */
    -webkit-line-clamp: 2;
    /* [zh] 样式规则 `-webkit-box-orient: vertical;` */
    -webkit-box-orient: vertical;
    /* [zh] 样式规则 `overflow: hidden;` */
    overflow: hidden;
  /* [zh] 样式规则 `}` */
  }

  /* [zh] 样式规则 `.goods-sku {` */
  .goods-sku {
    /* [zh] 样式规则 `margin: 0;` */
    margin: 0;
    /* [zh] 样式规则 `font-size: 11px;` */
    font-size: 11px;
    /* [zh] 样式规则 `color: $color-text-muted;` */
    color: $color-text-muted;
  /* [zh] 样式规则 `}` */
  }

  /* [zh] 样式规则 `.goods-remark {` */
  .goods-remark {
    /* [zh] 样式规则 `margin: 4px 0 0;` */
    margin: 4px 0 0;
    /* [zh] 样式规则 `font-size: 11px;` */
    font-size: 11px;
    /* [zh] 样式规则 `line-height: 1.4;` */
    line-height: 1.4;
    /* [zh] 样式规则 `color: #000;` */
    color: #000;
    /* [zh] 样式规则 `word-break: break-all;` */
    word-break: break-all;
  /* [zh] 样式规则 `}` */
  }
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.goods-price {` */
.goods-price {
  /* [zh] 样式规则 `flex-shrink: 0;` */
  flex-shrink: 0;
  /* [zh] 样式规则 `display: flex;` */
  display: flex;
  /* [zh] 样式规则 `flex-direction: column;` */
  flex-direction: column;
  /* [zh] 样式规则 `align-items: flex-end;` */
  align-items: flex-end;
  /* [zh] 样式规则 `gap: 4px;` */
  gap: 4px;

  /* [zh] 样式规则 `.price {` */
  .price {
    /* [zh] 样式规则 `font-size: 14px;` */
    font-size: 14px;
    /* [zh] 样式规则 `font-weight: 700;` */
    font-weight: 700;
    /* [zh] 样式规则 `color: $color-price;` */
    color: $color-price;
  /* [zh] 样式规则 `}` */
  }

  /* [zh] 样式规则 `.qty {` */
  .qty {
    /* [zh] 样式规则 `font-size: 12px;` */
    font-size: 12px;
    /* [zh] 样式规则 `color: $color-text-muted;` */
    color: $color-text-muted;
  /* [zh] 样式规则 `}` */
  }
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.goods-action {` */
.goods-action {
  /* [zh] 样式规则 `flex-shrink: 0;` */
  flex-shrink: 0;
  /* [zh] 样式规则 `display: flex;` */
  display: flex;
  /* [zh] 样式规则 `align-items: flex-end;` */
  align-items: flex-end;
  /* [zh] 样式规则 `padding-left: 8px;` */
  padding-left: 8px;
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.amount-card {` */
.amount-card {
  /* [zh] 样式规则 `.amount-row {` */
  .amount-row {
    /* [zh] 样式规则 `display: flex;` */
    display: flex;
    /* [zh] 样式规则 `align-items: baseline;` */
    align-items: baseline;
    /* [zh] 样式规则 `justify-content: flex-end;` */
    justify-content: flex-end;
    /* [zh] 样式规则 `gap: 8px;` */
    gap: 8px;
    /* [zh] 样式规则 `font-size: 14px;` */
    font-size: 14px;
    /* [zh] 样式规则 `color: $color-text-body;` */
    color: $color-text-body;

    /* [zh] 样式规则 `.amount {` */
    .amount {
      /* [zh] 样式规则 `font-size: 20px;` */
      font-size: 20px;
      /* [zh] 样式规则 `font-weight: 700;` */
      font-weight: 700;
      /* [zh] 样式规则 `color: $color-price;` */
      color: $color-price;
    /* [zh] 样式规则 `}` */
    }
  /* [zh] 样式规则 `}` */
  }
/* [zh] 样式规则 `}` */
}

/* [zh] 样式规则 `.detail-actions {` */
.detail-actions {
  /* [zh] 样式规则 `display: flex;` */
  display: flex;
  /* [zh] 样式规则 `flex-wrap: wrap;` */
  flex-wrap: wrap;
  /* [zh] 样式规则 `justify-content: center;` */
  justify-content: center;
  /* [zh] 样式规则 `gap: 10px;` */
  gap: 10px;
  /* [zh] 样式规则 `padding: 4px 0 8px;` */
  padding: 4px 0 8px;
/* [zh] 样式规则 `}` */
}
</style>
