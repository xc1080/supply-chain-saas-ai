<template>
  <section v-if="!order.legacy && (record || cases.length || eligible)" class="after-sales card">
    <header><h3>售后服务</h3><el-button text size="small" :loading="loading" @click="load">刷新</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-select v-if="cases.length > 1" v-model="selectedId" aria-label="售后申请记录" @change="load">
      <el-option v-for="entry in cases" :key="entry.afterSalesId" :value="entry.afterSalesId" :label="`${entry.kind === 'UNSHIPPED_REFUND' ? '未发货退款' : '退货退款'} · ${labels[entry.status] || entry.status} · ¥${money(entry.refundAmount)}`" />
    </el-select>
    <template v-if="record">
      <p class="state">{{ record.statusName || labels[record.status] || record.status }}</p>
      <p>{{ guidance[record.status] }}</p>
      <dl><div><dt>申请原因</dt><dd>{{ record.reason }}</dd></div><div><dt>申请金额</dt><dd>¥{{ money(record.refundAmount) }}</dd></div>
        <div v-if="record.status === 'REFUNDED'"><dt>沙箱退款</dt><dd>¥{{ money(record.refundedAmount) }}</dd></div><div v-if="record.returnCondition"><dt>验收结果</dt><dd>{{ conditionLabels[record.returnCondition] || record.returnCondition }}</dd></div></dl>
      <p v-for="item in record.items || []" :key="item.productId">{{ item.productName }} × {{ item.quantity }}</p>
      <dl v-if="record.returnParcel?.registered"><div><dt>寄回物流</dt><dd>{{ record.returnParcel.carrierCode }} · {{ record.returnParcel.trackingNo }}</dd></div><div><dt>登记时间</dt><dd>{{ record.returnParcel.registeredAt }}</dd></div></dl>
      <el-form v-if="canRegisterReturn" class="return-form" label-position="top" @submit.prevent="registerReturn">
        <el-form-item label="寄回承运商编码"><el-input v-model="returnForm.carrierCode" maxlength="32" placeholder="例如 SF、DEMO" :disabled="submitting" /></el-form-item>
        <el-form-item label="寄回单号"><el-input v-model="returnForm.trackingNo" maxlength="80" :disabled="submitting" /></el-form-item>
        <el-button type="primary" :loading="submitting" :disabled="!returnForm.carrierCode.trim() || !returnForm.trackingNo.trim()" @click="registerReturn">登记寄回单号</el-button>
      </el-form>
      <details><summary>查看处理记录</summary><dl>
        <div><dt>售后编号</dt><dd>{{ record.afterSalesId }}</dd></div>
        <div v-for="entry in timeline" :key="entry.key"><dt>{{ entry.label }}</dt><dd>{{ record[entry.key] }}</dd></div>
        <div v-if="record.reviewNote"><dt>审核说明</dt><dd>{{ record.reviewNote }}</dd></div>
      </dl></details>
    </template>
    <div v-if="eligible" class="application">
      <h4>{{ cases.length ? '申请其他商品售后' : '选择售后商品' }}</h4>
      <el-radio-group v-model="kind" :disabled="submitting" @change="resetSelection">
        <el-radio-button value="UNSHIPPED_REFUND" :disabled="!hasQuantity('UNSHIPPED_REFUND')">未发货退款</el-radio-button>
        <el-radio-button value="RETURN_REFUND" :disabled="!hasQuantity('RETURN_REFUND')">退货退款</el-radio-button>
      </el-radio-group>
      <div v-for="item in selectableItems" :key="item.productId" class="select-line">
        <ProductImage :product="item" :width="48" :height="48" />
        <div class="select-description"><strong>{{ item.productName }}</strong><span>可申请 {{ available(item, kind) }} 件</span></div>
        <el-input-number v-model="quantities[String(item.productId)]" :min="0" :max="available(item, kind)" :precision="0" size="small" :disabled="submitting" :aria-label="`${item.productName}售后数量`" />
      </div>
      <el-input v-model="reason" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="请填写售后原因" aria-label="售后原因" :disabled="submitting" />
      <p class="refund-note">退款金额按优惠和运费分摊计算，以申请结果为准。</p>
      <el-button type="primary" :loading="submitting" :disabled="!reason.trim() || !selectedItems.length" @click="apply">提交售后申请</el-button>
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { afterSalesApi } from '@/api/modules';
import { toast } from '@/utils/toast';
import ProductImage from '@/components/common/ProductImage.vue';
const props = defineProps<{ order: Record<string, any> }>();
const emit = defineEmits<{ updated: [] }>();
const record = ref<Record<string, any> | null>(null);
const reason = ref('');
const loading = ref(false), submitting = ref(false), error = ref('');
type Kind = 'UNSHIPPED_REFUND' | 'RETURN_REFUND';
const kind = ref<Kind>('UNSHIPPED_REFUND');
const quantities = reactive<Record<string, number>>({});
const returnForm = reactive({ carrierCode: '', trackingNo: '' });
const canRegisterReturn = computed(() => record.value?.status === 'AWAITING_RETURN' && !record.value?.returnParcel?.registered && (Array.isArray(record.value?.availableActions) ? record.value.availableActions.includes('REGISTER_RETURN') : true));
let generation = 0, returnFingerprint = '', returnRequestKey = '';
const selectedId = ref('');
const cases = computed<Record<string, any>[]>(() => props.order.afterSalesCases || (props.order.afterSale ? [props.order.afterSale] : []));
const items = computed<Record<string, any>[]>(() => props.order.orderItemList || []);
function available(item: Record<string, any>, type: Kind): number {
  const field = type === 'UNSHIPPED_REFUND' ? 'unshippedRefundAvailableQuantity' : 'returnAvailableQuantity';
  if (item[field] != null) return Math.max(0, Number(item[field]) || 0);
  if (props.order.afterSalesId) return 0;
  return (type === 'UNSHIPPED_REFUND' ? Number(props.order.orderStatus) === 1 : [2, 3].includes(Number(props.order.orderStatus))) ? Number(item.buyCount) || 0 : 0;
}
const hasQuantity = (type: Kind) => items.value.some(item => available(item, type) > 0);
const eligible = computed(() => [1, 2, 3].includes(Number(props.order.orderStatus)) && (hasQuantity('UNSHIPPED_REFUND') || hasQuantity('RETURN_REFUND')));
const selectableItems = computed(() => items.value.filter(item => available(item, kind.value) > 0));
const selectedItems = computed(() => selectableItems.value.map(item => ({productId: String(item.productId), quantity: Math.min(available(item, kind.value), Math.max(0, Math.trunc(Number(quantities[String(item.productId)]) || 0)))})).filter(item => item.quantity > 0));
function resetSelection() { for (const id of Object.keys(quantities)) delete quantities[id]; }
const labels: Record<string, string> = { REQUESTED: '待审核', APPROVED: '待退款', AWAITING_RETURN: '待退货验收', RETURN_RECEIVED: '已验收，待退款', REFUNDED: '已退款', REJECTED: '已拒绝' };
const conditionLabels: Record<string, string> = {SELLABLE:'完好可售',QUALITY_HOLD:'待质检',DAMAGED:'损坏'};
const guidance: Record<string, string> = { REQUESTED: '申请已提交，等待商家审核。', APPROVED: '审核通过，等待商家退款。', AWAITING_RETURN: '审核已通过，寄回后登记物流单号，等待商家验收。', RETURN_RECEIVED: '退货已验收入库，等待商家退款。', REFUNDED: '退款流程已完成，可在处理记录查看凭证。', REJECTED: '商家未通过本次申请，请查看审核说明。' };
const timeline = computed(() => [{key:'createdAt',label:'申请时间'},{key:'reviewedAt',label:'审核时间'},{key:'returnedAt',label:'验收时间'},{key:'refundedAt',label:'退款时间'},{key:'returnReceiptId',label:'退货入库单'},{key:'refundId',label:'退款凭证'}].filter(row => record.value?.[row.key]));
const money = (value: unknown) => Number(value || 0).toFixed(2);
async function load() {
  const id = selectedId.value || props.order.afterSalesId;
  if (!id) return;
  const current = ++generation;
  loading.value = true; error.value = '';
  try { const result = await afterSalesApi.detail(String(id)); if (current === generation) record.value = result; }
  catch (failure: any) { if (current === generation) error.value = failure?.message || failure?.info || '售后记录暂时未能更新'; }
  finally { if (current === generation) loading.value = false; }
}
async function registerReturn() {
  if (!canRegisterReturn.value || submitting.value || !returnForm.carrierCode.trim() || !returnForm.trackingNo.trim()) return;
  const afterSalesId = String(record.value?.afterSalesId), orderId = String(props.order.orderId);
  const body = { afterSalesId, carrierCode: returnForm.carrierCode.trim(), trackingNo: returnForm.trackingNo.trim() };
  if (!/^[A-Za-z0-9_-]{1,32}$/.test(body.carrierCode) || !/^[A-Za-z0-9_-]{1,80}$/.test(body.trackingNo)) { error.value = '承运商编码和单号仅支持字母、数字、连字符和下划线'; return; }
  const fingerprint = JSON.stringify(body); if (fingerprint !== returnFingerprint) { returnFingerprint = fingerprint; returnRequestKey = crypto.randomUUID(); }
  submitting.value = true; error.value = '';
  try { await afterSalesApi.returnParcel({ ...body, requestKey: returnRequestKey }); if (orderId !== String(props.order.orderId)) return; await load(); toast.success('寄回单号已登记'); emit('updated'); }
  catch (failure: any) { if (orderId === String(props.order.orderId)) error.value = failure?.message || failure?.info || '寄回登记结果未确认，请重试原操作'; }
  finally { submitting.value = false; }
}
async function apply() {
  if (!reason.value.trim() || !selectedItems.value.length || submitting.value) return;
  const orderId = String(props.order.orderId);
  const key = 'after-sales:' + props.order.orderId;
  const payload = {kind: kind.value, items: selectedItems.value, reason: reason.value.trim()};
  const fingerprint = JSON.stringify([payload, cases.value.map(entry => entry.afterSalesId)]);
  let requestKey = crypto.randomUUID();
  try { const previous = JSON.parse(sessionStorage.getItem(key) || 'null'); if (previous?.fingerprint === fingerprint) requestKey = previous.requestKey; else sessionStorage.setItem(key, JSON.stringify({fingerprint, requestKey})); } catch {}
  submitting.value = true; error.value = '';
  try {
    const result = await afterSalesApi.apply({orderId, requestKey, ...payload});
    if (orderId !== String(props.order.orderId)) return;
    record.value = result;
    selectedId.value = String(record.value?.afterSalesId || '');
    resetSelection(); reason.value = '';
    toast.success('售后申请已提交'); emit('updated');
  } catch (failure: any) { if (orderId === String(props.order.orderId)) error.value = failure?.message || failure?.info || '申请结果暂未确认，请使用原申请重试'; }
  finally { submitting.value = false; }
}
watch(() => props.order.orderId, () => { generation++; record.value = null; selectedId.value = ''; reason.value = ''; returnForm.carrierCode = ''; returnForm.trackingNo = ''; resetSelection();}, {immediate:true});
watch(() => [props.order.afterSalesId, props.order.afterSalesStatus, cases.value.map(entry => entry.afterSalesId + entry.status).join(',')], () => { if (!selectedId.value) selectedId.value = String(cases.value[0]?.afterSalesId || props.order.afterSalesId || ''); void load(); }, {immediate:true});
watch(() => [hasQuantity('UNSHIPPED_REFUND'), hasQuantity('RETURN_REFUND')], () => { if (!hasQuantity(kind.value)) kind.value = hasQuantity('UNSHIPPED_REFUND') ? 'UNSHIPPED_REFUND' : 'RETURN_REFUND'; }, {immediate:true});
</script>
<style scoped>
.after-sales{padding:20px}.after-sales header{display:flex;align-items:center;justify-content:space-between}.after-sales h3{margin:0;font-size:17px}.after-sales p{font-size:14px;line-height:1.8}.state{font-weight:600}.after-sales dl>div{display:flex;gap:16px;padding:7px 0;font-size:13px}.after-sales dt{flex:0 0 80px;color:#777}.after-sales dd{margin:0;overflow-wrap:anywhere}.after-sales summary{cursor:pointer;font-size:13px}.after-sales .el-button{margin-top:14px}
.application{margin-top:20px;padding-top:16px;border-top:1px solid #e8ebef}.application h4{margin:0 0 14px}.select-line{display:flex;align-items:center;gap:12px;margin:16px 0}.select-description{flex:1;min-width:0}.select-description strong,.select-description span{display:block}.select-description span{font-size:12px;color:#667085;margin-top:4px}.after-sales>.el-select{width:100%;margin:12px 0}@media(max-width:480px){.select-line{flex-wrap:wrap}.select-line>.el-input-number{margin-left:60px}}
.after-sales .refund-note{margin:10px 0 0;font-size:12px;color:#667085}
</style>
