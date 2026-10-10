<template>
  <section v-if="orderId && canRead" class="order-settlement">
    <header><h3>订单费用与沙箱结算</h3><el-button text :loading="loading" @click="refresh">刷新</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="record">
      <dl class="settlement-summary"><div v-for="field in summaryFields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ field.cost && !record.costComplete ? '成本不完整' : money(record[field.key]) }}</dd></div></dl>
      <el-table :data="record.expenses || []" row-key="expenseId" empty-text="尚未登记订单费用">
        <el-table-column label="费用" width="100"><template #default="s">{{ categories[s.row.category] || s.row.category }}</template></el-table-column>
        <el-table-column label="金额" width="115" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>
        <el-table-column label="收款方 / 凭证" min-width="180"><template #default="s">{{ s.row.payee }}<small>{{ s.row.evidenceReference }}</small></template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="s">{{ { DRAFT: '待审批', APPROVED: '已审批', REJECTED: '已拒绝' }[s.row.status] || s.row.status }}</template></el-table-column>
        <el-table-column label="已结 / 待结" min-width="170"><template #default="s">{{ money(s.row.settledAmount) }} / {{ money(s.row.outstandingAmount) }}</template></el-table-column>
        <el-table-column v-if="canReview" label="审批" width="125"><template #default="s"><template v-if="s.row.status === 'DRAFT'"><el-button text type="primary" :disabled="acting" @click="review(s.row, 'APPROVE')">通过</el-button><el-button text :disabled="acting" @click="review(s.row, 'REJECT')">拒绝</el-button></template></template></el-table-column>
      </el-table>
      <details v-if="canReview" class="settlement-form"><summary>登记订单费用</summary><el-form label-position="top" @submit.prevent="addExpense"><el-form-item label="费用类别"><el-select v-model="expense.category" :disabled="acting"><el-option v-for="(label, value) in categories" :key="value" :label="label" :value="value" /></el-select></el-form-item><el-form-item label="金额（元）"><el-input v-model="expense.amount" inputmode="decimal" :disabled="acting" /></el-form-item><el-form-item label="收款方"><el-input v-model="expense.payee" maxlength="200" :disabled="acting" /></el-form-item><el-form-item label="费用凭证编号"><el-input v-model="expense.evidenceReference" maxlength="200" :disabled="acting" /></el-form-item><el-button type="primary" :loading="acting" @click="addExpense">登记费用</el-button></el-form></details>
      <details v-if="canExecute && outstanding.length" class="settlement-form"><summary>结算已审批费用</summary><el-form label-position="top" @submit.prevent="pay"><div v-for="entry in outstanding" :key="entry.expenseId" class="allocation"><label>{{ categories[entry.category] }} · {{ entry.payee }} · 待结 {{ money(entry.outstandingAmount) }}</label><el-input-number v-model="allocations[entry.expenseId]" :min="0" :max="Number(entry.outstandingAmount)" :precision="2" :disabled="acting" :aria-label="`${entry.payee}本次结算金额`" /></div><el-form-item label="沙箱结算凭证编号"><el-input v-model="paymentEvidence" maxlength="200" :disabled="acting" /></el-form-item><el-button type="primary" :loading="acting" @click="pay">登记沙箱费用结算</el-button></el-form></details>
      <div class="settlement-actions"><router-link :to="{ path: '/ai/assistant', query: { question: `请分析订单 ${orderId} 的成本、费用和经营结果，并说明库存占用与出入库变化。` } }"><el-button>经营分析</el-button></router-link><el-button v-if="canReview" :loading="acting" @click="saveSnapshot">保存经营快照</el-button></div>
      <h4>沙箱费用结算记录</h4><el-table :data="record.payments || []" row-key="paymentId" empty-text="尚无结算记录"><el-table-column label="结算编号" prop="paymentId" min-width="230" /><el-table-column label="金额" width="120" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column><el-table-column label="凭证" prop="evidenceReference" min-width="180" /></el-table>
      <details v-if="record.snapshots?.length" class="settlement-form"><summary>经营快照（{{ record.snapshots.length }}）</summary><div v-for="snapshot in record.snapshots" :key="snapshot.snapshotId" class="snapshot-note"><strong>{{ snapshot.createdAt }} · {{ snapshot.result?.resultStatus === 'CLOSED' ? '经营结果' : '暂估经营贡献' }} {{ money(snapshot.result?.resultStatus === 'CLOSED' ? snapshot.result?.operatingResult : snapshot.result?.provisionalContribution) }}</strong><p>快照编号 {{ snapshot.snapshotId }}</p><p>凭证摘要 {{ snapshot.factsHash }}</p></div></details>
    </template>
  </section>
</template>
<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getOrderSettlement, registerOrderExpense, reviewOrderExpense, settleOrderExpenses, snapshotOrderSettlement } from '@/api/commerce/settlements'
const props = defineProps({ shopId: { type: String, required: true }, orderId: { type: String, required: true }, capabilities: { type: Array, default: () => [] } })
const canReview = computed(() => props.capabilities.includes('REFUND_REVIEW')), canExecute = computed(() => props.capabilities.includes('REFUND_EXECUTE')), canRead = computed(() => canReview.value || canExecute.value)
const record = ref(null), loading = ref(false), acting = ref(false), error = ref(''), paymentEvidence = ref('')
const expense = reactive({ category: 'LOGISTICS', amount: '', payee: '', evidenceReference: '' }), allocations = reactive({})
const outstanding = computed(() => (record.value?.expenses || []).filter(row => row.status === 'APPROVED' && Number(row.outstandingAmount) > 0))
const categories = { LOGISTICS: '物流', PACKAGING: '包装', PLATFORM: '平台', OTHER: '其他' }
const summaryFields = computed(() => [{ key: 'grossPaid', label: '实付收入' }, { key: 'refundedAmount', label: '退款' }, { key: 'netReceipts', label: '净收入' }, { key: 'netStockCost', label: '净商品成本', cost: true }, { key: 'approvedExpenseAmount', label: '已批费用' }, { key: 'settledExpenseAmount', label: '已结费用' }, { key: 'outstandingExpenseAmount', label: '待结费用' }, record.value?.resultStatus === 'CLOSED' ? { key: 'operatingResult', label: '经营结果', cost: true } : { key: 'provisionalContribution', label: '暂估经营贡献', cost: true }])
const money = value => value == null ? '—' : new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(Number(value))
const validAmount = value => /^\d+(\.\d{1,2})?$/.test(value) && Number(value) > 0
let generation = 0
const keys = new Map()
const keyFor = (action, body) => { const fingerprint = JSON.stringify([props.shopId, props.orderId, action, body]); if (!keys.has(fingerprint)) keys.set(fingerprint, crypto.randomUUID()); return keys.get(fingerprint) }
async function refresh() {
  if (!props.orderId || !canRead.value) return
  const shop = props.shopId, id = props.orderId, current = ++generation; loading.value = true; error.value = ''
  try { const result = await getOrderSettlement(shop, id); if (current !== generation || shop !== props.shopId || id !== props.orderId) return; record.value = result.data; for (const key of Object.keys(allocations)) delete allocations[key]; for (const row of outstanding.value) allocations[row.expenseId] = 0 }
  catch (failure) { if (current === generation) error.value = failure?.message || '订单费用读取失败' }
  finally { if (current === generation) loading.value = false }
}
async function perform(action, body, callback) {
  if (acting.value) return
  const shop = props.shopId, id = props.orderId, current = generation; acting.value = true; error.value = ''
  try { await callback(shop, id, { ...body, requestKey: keyFor(action, body) }); if (current !== generation || shop !== props.shopId || id !== props.orderId) return; ElMessage.success('费用与结算记录已更新'); await refresh() }
  catch (failure) { if (current === generation && shop === props.shopId && id === props.orderId) error.value = failure?.message || '操作结果未确认，请重试原操作' }
  finally { acting.value = false }
}
function addExpense() { if (!canReview.value) return; const body = { ...expense, amount: expense.amount.trim(), payee: expense.payee.trim(), evidenceReference: expense.evidenceReference.trim() }; if (!validAmount(body.amount) || !body.payee || !body.evidenceReference) { error.value = '填写正数金额、收款方和凭证编号'; return } void perform('expense', body, registerOrderExpense) }
function review(row, decision) { if (!canReview.value || row.status !== 'DRAFT') return; void perform(`review:${row.expenseId}`, { decision }, (shop, id, body) => reviewOrderExpense(shop, row.expenseId, body)) }
function pay() { if (!canExecute.value) return; const selected = outstanding.value.map(row => ({ expenseId: row.expenseId, amount: Number(allocations[row.expenseId] || 0).toFixed(2) })).filter(row => Number(row.amount) > 0); if (!selected.length || !paymentEvidence.value.trim()) { error.value = '选择结算金额并填写沙箱凭证编号'; return } void perform('payment', { allocations: selected, evidenceReference: paymentEvidence.value.trim() }, settleOrderExpenses) }
function saveSnapshot() { if (canReview.value) void perform('snapshot:' + JSON.stringify([record.value?.resultStatus, record.value?.grossPaid, record.value?.refundedAmount, record.value?.netStockCost, record.value?.expenses, record.value?.payments]), {}, snapshotOrderSettlement) }
watch(() => [props.shopId, props.orderId], () => { generation++; record.value = null; error.value = ''; paymentEvidence.value = ''; Object.assign(expense, { category: 'LOGISTICS', amount: '', payee: '', evidenceReference: '' }); void refresh() }, { immediate: true })
defineExpose({ refresh })
</script>
<style scoped>
.order-settlement{border-top:1px solid var(--sc-border);margin-top:25px;padding-top:8px}.order-settlement header{display:flex;justify-content:space-between;align-items:center}.order-settlement h3{font-size:16px}.order-settlement h4{font-size:14px;margin-top:24px}.settlement-summary{display:flex;flex-wrap:wrap;gap:18px 25px;margin:20px 0}.settlement-summary dt{font-size:12px;color:var(--sc-muted);margin-bottom:7px}.settlement-summary dd{margin:0;font-size:15px;font-weight:600}.order-settlement small{display:block;color:var(--sc-muted)}.settlement-form{margin-top:20px}.settlement-form summary{cursor:pointer;font-size:14px}.settlement-form .el-form{max-width:500px;margin-top:18px}.allocation{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:16px 0;font-size:13px}.settlement-actions{display:flex;justify-content:flex-end;gap:12px;margin-top:20px}.snapshot-note{font-size:12px;color:var(--sc-muted);overflow-wrap:anywhere;border-top:1px solid var(--sc-border);padding:14px 0}.snapshot-note:first-of-type{margin-top:14px}@media(max-width:767px){.allocation{flex-wrap:wrap}}
</style>
