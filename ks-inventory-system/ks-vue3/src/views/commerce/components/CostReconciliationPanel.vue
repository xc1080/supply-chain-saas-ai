<template>
  <section v-if="canFinance" class="cost-panel">
    <header><div><h2>成本与资金核对</h2><small>单据成本快照 · 本地支付沙箱</small></div><el-button text :loading="loading" @click="refresh">刷新</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="cost-toolbar"><el-input v-model="orderId" placeholder="输入订单编号" aria-label="待核对订单编号" maxlength="32" clearable /><el-button :disabled="!orderId.trim() || acting" @click="inspect">查看订单凭证</el-button></div>
    <template v-if="preview">
      <h3>订单 {{ preview.orderId }}</h3>
      <div class="money-summary"><span>订单净额 <strong>{{ money(preview.expectedNet) }}</strong></span><span>渠道净额 <strong>{{ money(preview.channelNet) }}</strong></span><span>出库成本 <strong>{{ preview.cost?.complete ? money(preview.cost.outbound) : '记录不完整' }}</strong></span><span>退货成本 <strong>{{ preview.cost?.complete ? money(preview.cost.returned) : '记录不完整' }}</strong></span><el-tag :type="preview.healthy ? 'success' : 'warning'">{{ preview.healthy ? '核对一致' : `${preview.issues?.length || 0} 项待核对` }}</el-tag></div>
      <el-table :data="preview.operations || []" row-key="operationId" empty-text="尚无支付操作记录"><el-table-column label="支付操作" prop="operationId" min-width="225" /><el-table-column label="业务" width="105"><template #default="s">{{ kindNames[s.row.kind] || s.row.kind }}</template></el-table-column><el-table-column label="原账金额" width="110" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column><el-table-column label="渠道 / 观测" min-width="180"><template #default="s">{{ stateNames[s.row.providerStatus] || s.row.providerStatus }} / {{ s.row.observation ? `${stateNames[s.row.observation.status] || s.row.observation.status} ${money(s.row.observation.amount)}` : '未导入' }}</template></el-table-column><el-table-column label="操作" width="130"><template #default="s"><el-button link type="primary" :disabled="acting" @click="openObservation(s.row)">导入沙箱观测</el-button></template></el-table-column></el-table>
      <ul v-if="preview.issues?.length" class="issues"><li v-for="(issue, index) in preview.issues" :key="index">{{ issueNames[issue.type] || issue.type }}<span v-if="issue.difference != null"> · 差额 {{ money(issue.difference) }}</span></li></ul>
      <div class="cost-actions"><el-button type="primary" :loading="acting" @click="saveReconciliation">保存本次核对</el-button></div>
    </template>
    <h3>核对与处理记录</h3>
    <el-table :data="reconciliations" row-key="reconciliationId" v-loading="loading" empty-text="尚无核对记录"><el-table-column type="expand"><template #default="s"><div class="evidence"><p>原始核对：{{ s.row.snapshot?.healthy ? '一致' : `${s.row.snapshot?.issues?.length || 0} 项差异` }} · 操作人 {{ s.row.actorId }}</p><p v-if="s.row.resolution">处理依据 {{ s.row.resolution.evidenceReference }} · {{ s.row.resolution.note }} · 操作人 {{ s.row.resolution.actorId }}</p><ul v-if="s.row.snapshot?.issues?.length"><li v-for="(issue, index) in s.row.snapshot.issues" :key="index">{{ issueNames[issue.type] || issue.type }}<span v-if="issue.difference != null"> · {{ money(issue.difference) }}</span></li></ul></div></template></el-table-column><el-table-column label="订单" prop="orderId" min-width="225" /><el-table-column label="核对时间" prop="createdAt" min-width="170" /><el-table-column label="状态" width="110"><template #default="s"><el-tag :type="s.row.status === 'OPEN' ? 'warning' : 'success'">{{ { OPEN: '待处理', MATCHED: '一致', RESOLVED: '复核完成' }[s.row.status] || s.row.status }}</el-tag></template></el-table-column><el-table-column label="操作" width="130"><template #default="s"><el-button v-if="s.row.status === 'OPEN'" link type="primary" :disabled="acting" @click="openResolution(s.row)">复核并留证</el-button></template></el-table-column></el-table>
    <h3>最近成本流水 <el-tooltip content="采购按单据单价记快照，未分摊折扣、税及运费；出库按发货单成本记快照，退货回转原出库成本。不计算 FIFO 或完整库存估值。"><el-icon aria-label="成本口径"><InfoFilled /></el-icon></el-tooltip></h3>
    <el-table :data="ledger?.entries || []" row-key="entryId" empty-text="尚无成本快照"><el-table-column label="商品" min-width="235"><template #default="s"><ProductIdentity :product="s.row" /></template></el-table-column><el-table-column label="业务" width="125"><template #default="s">{{ eventNames[s.row.eventType] || s.row.eventType }}</template></el-table-column><el-table-column label="仓库" prop="warehouseId" width="80" /><el-table-column label="数量" prop="quantity" width="85" align="right" /><el-table-column label="单位成本" width="120" align="right"><template #default="s">{{ s.row.costStatus === 'KNOWN' ? money(s.row.unitCost) : '未知' }}</template></el-table-column><el-table-column label="成本变动" width="130" align="right"><template #default="s">{{ s.row.costStatus === 'KNOWN' ? money(s.row.amount) : '未知' }}</template></el-table-column><el-table-column label="凭证 / 原流水" min-width="230"><template #default="s">{{ s.row.receiptId }}<small v-if="s.row.originEntryId" class="origin">原流水 #{{ s.row.originEntryId }}</small></template></el-table-column></el-table>
    <p v-if="ledger?.unknownEntries" class="coverage">{{ ledger.unknownEntries }} 条成本记录缺少原始成本依据。</p>
    <el-dialog v-model="observationVisible" title="导入沙箱对账观测" width="min(500px, 95vw)" :close-on-click-modal="!acting" :close-on-press-escape="!acting" :show-close="!acting">
      <el-form label-position="top" @submit.prevent="submitObservation"><el-form-item label="观测状态" required><el-select v-model="observationForm.status" :disabled="acting"><el-option v-for="status in ['SUCCEEDED', 'FAILED', 'PENDING', 'MISSING']" :key="status" :label="stateNames[status]" :value="status" /></el-select></el-form-item><el-form-item label="观测金额（元）" required><el-input v-model="observationForm.amount" inputmode="decimal" :disabled="acting" /></el-form-item><el-form-item label="资料编号" required><el-input v-model="observationForm.sourceReference" maxlength="200" placeholder="沙箱对账资料或更正记录编号" :disabled="acting" /></el-form-item></el-form><el-alert v-if="dialogError" :title="dialogError" type="error" :closable="false" /><template #footer><el-button :disabled="acting" @click="observationVisible = false">返回</el-button><el-button type="primary" :loading="acting" @click="submitObservation">保存观测</el-button></template>
    </el-dialog>
    <el-dialog v-model="resolutionVisible" title="重新核对并保存处理证据" width="min(500px, 95vw)" :close-on-click-modal="!acting" :close-on-press-escape="!acting" :show-close="!acting"><el-form label-position="top" @submit.prevent="submitResolution"><el-form-item label="处理依据" required><el-input v-model="resolutionForm.evidenceReference" maxlength="200" :disabled="acting" /></el-form-item><el-form-item label="处理说明" required><el-input v-model="resolutionForm.note" type="textarea" :rows="3" maxlength="200" :disabled="acting" /></el-form-item></el-form><el-alert v-if="dialogError" :title="dialogError" type="error" :closable="false" /><template #footer><el-button :disabled="acting" @click="resolutionVisible = false">返回</el-button><el-button type="primary" :loading="acting" @click="submitResolution">复核并留证</el-button></template></el-dialog>
  </section>
</template>
<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { InfoFilled } from '@element-plus/icons-vue'
import ProductIdentity from '@/components/ProductIdentity/index.vue'
import { getCostLedger, listMoneyReconciliations, previewOrderMoney, importSandboxObservation, createMoneyReconciliation, resolveMoneyReconciliation } from '@/api/commerce/costs'
const props = defineProps({ shopId: { type: String, required: true }, capabilities: { type: Array, default: () => [] } })
const emit = defineEmits(['updated'])
const canFinance = computed(() => props.capabilities.includes('REFUND_REVIEW'))
const loading = ref(false), acting = ref(false), error = ref(''), orderId = ref(''), preview = ref(null), ledger = ref(null), reconciliations = ref([])
const observationVisible = ref(false), resolutionVisible = ref(false), dialogError = ref(''), observationTarget = ref(null), resolutionTarget = ref(null)
const observationForm = reactive({ status: 'SUCCEEDED', amount: '', sourceReference: '', requestKey: '' }), resolutionForm = reactive({ evidenceReference: '', note: '', requestKey: '' })
const eventNames = { PURCHASE: '采购入库', PURCHASE_REVERSAL: '采购冲销', DISPATCH: '销售出库', RETURN: '退货回转' }, kindNames = { PAYMENT: '收款', REFUND: '售后退款', COMPENSATION: '关单退款' }
const stateNames = { SUCCEEDED: '成功', FAILED: '失败', PENDING: '处理中', MISSING: '缺失', NOT_SUBMITTED: '未提交' }
const issueNames = { STATEMENT_MISSING: '缺少渠道观测', STATEMENT_MISMATCH: '渠道观测与原账不同', OPERATION_PENDING: '支付操作待确认', PAYMENT_EVIDENCE_MISSING: '缺少成功收款凭证', REFUND_EVIDENCE_MISSING: '缺少成功退款凭证', ORDER_CHANNEL_CHARGE: '订单与渠道收款不同', ORDER_CHANNEL_REFUND: '订单与渠道退款不同', ORDER_CHANNEL_NET: '订单与渠道净额不同', CHANNEL_PAYMENT_OPERATIONS: '支付操作与渠道收款不同', CHANNEL_REFUND_OPERATIONS: '退款操作与渠道退款不同', ORDER_AFTER_SALES_REFUND: '订单退款与售后退款不同' }
const money = value => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 4 }).format(Number(value || 0))
const requestKey = prefix => `${prefix}_${crypto.randomUUID().replaceAll('-', '')}`
let generation = 0
async function refresh() {
  if (!canFinance.value) return
  const shop = props.shopId, current = ++generation; loading.value = true; error.value = ''
  try { const [costs, records] = await Promise.all([getCostLedger(shop), listMoneyReconciliations(shop)]); if (current !== generation || shop !== props.shopId) return; ledger.value = costs.data; reconciliations.value = records.data || [] }
  catch (failure) { if (current === generation) error.value = failure?.message || '成本与对账暂不可用' }
  finally { if (current === generation) loading.value = false }
}
async function inspect() {
  if (acting.value || !canFinance.value || !orderId.value.trim()) return
  const shop = props.shopId, requested = orderId.value.trim(); acting.value = true; error.value = ''; preview.value = null
  try { const result = await previewOrderMoney(shop, requested); if (shop === props.shopId) preview.value = result.data }
  catch (failure) { if (shop === props.shopId) error.value = failure?.message || '订单凭证查询失败' }
  finally { acting.value = false }
}
async function perform(callback, finish) {
  if (acting.value || !canFinance.value) return
  const shop = props.shopId; acting.value = true; dialogError.value = ''; error.value = ''
  try { await callback(shop); if (shop !== props.shopId) return; finish?.(); await refresh(); if (shop !== props.shopId) return; if (preview.value?.orderId) { const updated = await previewOrderMoney(shop, preview.value.orderId); if (shop !== props.shopId) return; preview.value = updated.data } emit('updated') }
  catch (failure) { if (shop === props.shopId) { const message = failure?.message || '处理未完成，请核对后重试'; if (observationVisible.value || resolutionVisible.value) dialogError.value = message; else error.value = message } }
  finally { acting.value = false }
}
function openObservation(row) { observationTarget.value = { ...row, shopId: props.shopId }; Object.assign(observationForm, { status: row.observation?.status || 'SUCCEEDED', amount: String(row.observation?.amount ?? row.amount), sourceReference: '', requestKey: requestKey('obs') }); dialogError.value = ''; observationVisible.value = true }
function submitObservation() {
  if (!observationTarget.value || observationTarget.value.shopId !== props.shopId) return
  if (!/^\d+(\.\d{1,2})?$/.test(observationForm.amount) || !observationForm.sourceReference.trim()) { dialogError.value = '填写非负金额与资料编号'; return }
  void perform(shop => importSandboxObservation(shop, { ...observationForm, amount: observationForm.amount, operationId: observationTarget.value.operationId }), () => { observationVisible.value = false; ElMessage.success('观测已保存') })
}
function openResolution(row) { resolutionTarget.value = { ...row, shopId: props.shopId }; Object.assign(resolutionForm, { evidenceReference: '', note: '', requestKey: requestKey('resolve') }); dialogError.value = ''; resolutionVisible.value = true }
function submitResolution() {
  if (!resolutionTarget.value || resolutionTarget.value.shopId !== props.shopId) return
  if (!resolutionForm.evidenceReference.trim() || !resolutionForm.note.trim()) { dialogError.value = '填写处理依据与说明'; return }
  void perform(shop => resolveMoneyReconciliation(shop, resolutionTarget.value.reconciliationId, { ...resolutionForm }), () => { resolutionVisible.value = false; ElMessage.success('复核完成，已保留处理证据') })
}
function saveReconciliation() { const id = preview.value?.orderId; if (!id) return; const key = requestKey('reconcile'); void perform(shop => createMoneyReconciliation(shop, { orderId: id, requestKey: key }), () => ElMessage.success('核对记录已保存')) }
watch(() => props.shopId, () => { generation++; ledger.value = null; reconciliations.value = []; preview.value = null; orderId.value = ''; error.value = ''; observationVisible.value = false; resolutionVisible.value = false; void refresh() })
onMounted(refresh)
defineExpose({ refresh })
</script>
<style scoped>
.cost-panel{background:var(--sc-surface);padding:22px;border:1px solid var(--sc-border);border-radius:10px}.cost-panel header{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px}.cost-panel h2{font-size:18px;margin:0 0 6px}.cost-panel h3{display:flex;align-items:center;gap:8px;font-size:15px;margin:28px 0 14px}.cost-panel small,.coverage{color:var(--sc-muted);font-size:12px}.cost-toolbar{display:flex;gap:10px;margin:18px 0}.cost-toolbar .el-input{max-width:350px}.money-summary{display:flex;gap:22px;flex-wrap:wrap;align-items:center;margin:18px 0}.money-summary span{display:flex;gap:8px;font-size:13px}.issues{color:var(--sc-warning);font-size:13px;padding-left:20px;line-height:1.8}.cost-actions{display:flex;justify-content:flex-end;margin:14px 0}.origin{display:block}.evidence{padding:0 24px;color:var(--sc-muted);font-size:13px}.evidence ul{line-height:1.8}@media(max-width:767px){.cost-panel{padding:14px}.cost-toolbar{flex-wrap:wrap}.cost-toolbar .el-input{max-width:none}.money-summary{gap:12px}}
</style>
