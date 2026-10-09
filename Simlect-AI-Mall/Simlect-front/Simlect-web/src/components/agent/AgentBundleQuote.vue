<template>
  <section class="bundle-quote" aria-label="设备组合报价草稿">
    <header><div><strong>设备组合方案</strong><span>{{ plan.units || 1 }} 套 · {{ totalQuantity }} 件设备</span></div><el-tag :type="tone">{{ statusText }}</el-tag></header>
    <div v-for="item in plan.items || []" :key="item.productId" class="bundle-item">
      <ProductImage :product="item" :width="56" :height="56" fit="contain" dense />
      <div class="bundle-item-name"><strong>{{ item.name || item.productName || '待选设备' }}</strong><span>每套 {{ item.quantity }} 件<template v-if="Number(plan.units) > 1"> · 本批 {{ item.quantity * plan.units }} 件</template></span><small>可售 {{ item.availableStock ?? '待核对' }} 件</small></div>
      <div class="bundle-price"><strong>{{ money(item.lineTotal) }}</strong><small>{{ money(item.unitPrice) }} / 件</small></div>
    </div>
    <div class="bundle-total"><span>设备合计 <strong>{{ money(plan.budget?.total) }}</strong></span><span v-if="plan.budget?.limit != null" :class="{ 'over-budget': plan.budget.withinBudget === false }">总预算 {{ money(plan.budget.limit) }}</span></div>
    <small class="bundle-cost-scope">安装与配送费用待确认。</small>
    <div class="bundle-facts"><span>{{ compatibilityText }}</span><span>库存支持 {{ plan.inventory?.inventoryPromisableUnits ?? plan.inventory?.promisableUnits ?? '待核对' }} 套</span><span v-if="plan.delivery?.capacity != null">发货余量 {{ plan.delivery.capacity }} 套</span><span>{{ plan.delivery?.date ? `到货参考 ${plan.delivery.date}` : '到货时间未确认' }}</span></div>
    <ul v-if="plan.missing?.length || plan.inventory?.shortages?.length || plan.budget?.withinBudget === false || capacityShortage" class="bundle-gaps">
      <li v-for="gap in plan.missing || []" :key="gap.field">{{ gap.question }}</li>
      <li v-for="shortage in plan.inventory?.shortages || []" :key="shortage.productId">{{ shortage.name }} 缺 {{ shortage.quantity }} 件</li>
      <li v-if="plan.budget?.withinBudget === false">整套金额超过预算，请调整设备或数量。</li>
      <li v-if="capacityShortage">发货处理余量不足，请减少套数或与商家确认发货安排。</li>
    </ul>
    <details v-if="plan.alternatives?.length" class="bundle-alternatives"><summary>替代候选（{{ plan.alternatives.length }}）</summary><div v-for="candidate in plan.alternatives" :key="candidate.productId"><strong>{{ candidate.name }}</strong><p>{{ candidate.reason }}</p><ul><li v-for="difference in candidate.differences || []" :key="String(difference)">{{ difference }}</li></ul><small>确认替代后需重新生成报价。</small></div></details>
    <p v-if="plan.confirmationStatus === 'REPRICE_REQUIRED'" class="bundle-notice" role="status">价格已更新，请核对新合计后再次确认。</p>
    <p v-if="plan.confirmationStatus === 'EVIDENCE_REVIEW_REQUIRED'" class="bundle-notice" role="status">选型资料已更新，请核对型号配套后再次确认。</p>
    <p v-if="error" class="bundle-error" role="alert">{{ error }}</p>
    <footer>
      <template v-if="plan.confirmationStatus === 'ADDED'"><span class="bundle-added">整套已加入购物车</span><el-button type="primary" size="small" @click="router.push('/cart')">查看购物车</el-button></template>
      <template v-else><small>确认加购会重核选型、价格与库存，尚未下单。</small><el-button type="primary" :disabled="!canConfirm" :loading="busy" @click="confirm">{{ plan.confirmationStatus === 'REPRICE_REQUIRED' ? '按更新报价加入购物车' : plan.confirmationStatus === 'EVIDENCE_REVIEW_REQUIRED' ? '按更新方案加入购物车' : '确认整套加入购物车' }}</el-button></template>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { agentApi } from '@/api/modules';
import { useCartStore } from '@/stores/cart';
import ProductImage from '@/components/common/ProductImage.vue';

const props = defineProps<{ data: Record<string, any> }>();
const plan = ref<Record<string, any>>(props.data);
watch(() => props.data, value => { plan.value = value; });
const router = useRouter();
const cart = useCartStore();
const busy = ref(false);
const error = ref('');
const money = (value: unknown) => value == null ? '待核价' : new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(Number(value));
const totalQuantity = computed(() => (plan.value.items || []).reduce((sum: number, item: any) => sum + Number(item.quantity || 0) * Number(plan.value.units || 1), 0));
const capacityShortage = computed(() => plan.value.delivery?.capacity != null && Number(plan.value.delivery.capacity) < Number(plan.value.units || 1));
const canConfirm = computed(() => plan.value.status === 'READY_FOR_REVIEW' && !!plan.value.quoteToken && !['ADDED', 'EXPIRED'].includes(plan.value.confirmationStatus));
const statusText = computed(() => plan.value.confirmationStatus === 'ADDED' ? '已加购' : plan.value.confirmationStatus === 'EXPIRED' ? '报价已过期' : plan.value.status === 'READY_FOR_REVIEW' ? '待你确认' : plan.value.status === 'NEEDS_INPUT' ? '待补充条件' : '需要调整');
const tone = computed(() => plan.value.confirmationStatus === 'ADDED' ? 'success' : canConfirm.value ? 'primary' : 'warning');
const compatibilityText = computed(() => plan.value.compatibility?.status === 'COMPATIBLE' ? '已核对型号配对' : '型号配对待核对');
async function confirm() {
  if (busy.value || !canConfirm.value) return;
  busy.value = true; error.value = '';
  try {
    const result = await agentApi.confirmQuote(plan.value.quoteToken, plan.value.revision);
    plan.value = result.businessPlan;
    if (plan.value.confirmationStatus === 'ADDED') await cart.fetchCartCount();
  } catch (failure: any) { error.value = failure?.message || '加购结果暂未确认，请重试原报价。'; }
  finally { busy.value = false; }
}
onMounted(async () => {
  if (!plan.value.quoteToken) return;
  try { plan.value = (await agentApi.quoteDetail(plan.value.quoteToken)).businessPlan; }
  catch (failure: any) { error.value = failure?.message || '报价状态暂不可用，请重新查询。'; }
});
</script>

<style scoped>
.bundle-cost-scope{display:block;color:#65758a;font-size:11px;margin-top:5px}
.bundle-quote{width:min(560px,100%);font-size:13px;color:#26384c}.bundle-quote header{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:16px}.bundle-quote header strong{display:block;font-size:17px}.bundle-quote header span,.bundle-item-name span,.bundle-item-name small,.bundle-price small{display:block;color:#65758a;font-size:12px;margin-top:4px}.bundle-item{display:flex;align-items:center;gap:12px;padding:12px 0;border-top:1px solid #e4e9ef}.bundle-item-name{flex:1;min-width:0}.bundle-item-name strong{overflow-wrap:anywhere}.bundle-price{text-align:right;flex-shrink:0}.bundle-total{display:flex;justify-content:space-between;align-items:baseline;gap:12px;border-top:1px solid #d8e2eb;padding-top:15px}.bundle-total strong{font-size:20px;margin-left:6px}.bundle-facts{display:flex;gap:8px 18px;flex-wrap:wrap;color:#65758a;font-size:12px;padding:13px 0}.bundle-gaps{padding:12px 12px 12px 28px;margin:0;background:#fff7e8;color:#815c1c;line-height:1.8;border-radius:6px}.bundle-alternatives{border-top:1px solid #e4e9ef;padding-top:12px;margin-top:12px;color:#65758a}.bundle-alternatives summary{cursor:pointer}.bundle-alternatives>div{padding:12px 0}.bundle-alternatives p{margin:4px 0}.bundle-alternatives ul{padding-left:20px}.bundle-notice,.bundle-error{margin:12px 0;color:#a65024}.bundle-quote footer{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;border-top:1px solid #e4e9ef;padding-top:14px;margin-top:4px}.bundle-quote footer small{color:#65758a;font-size:11px}.bundle-added{color:#348262}.over-budget{color:#b5473b}@media(max-width:520px){.bundle-quote header{gap:8px}.bundle-price strong{font-size:12px}.bundle-total{align-items:flex-start;flex-direction:column;gap:5px}.bundle-quote footer .el-button{width:100%}}
</style>
