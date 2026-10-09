<template>
  <section class="after-sales-panel">
    <header><h2>售后处理</h2><el-button text :loading="loading" @click="refresh">刷新</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="rows" v-loading="loading" empty-text="暂无售后申请">
      <el-table-column label="订单编号" prop="orderId" min-width="220" />
      <el-table-column label="售后范围" width="120"><template #default="scope">{{ scope.row.kind === 'UNSHIPPED_REFUND' ? '未发货退款' : '退货退款' }}</template></el-table-column>
      <el-table-column label="申请原因" prop="reason" min-width="200" show-overflow-tooltip />
      <el-table-column label="申请金额" width="110"><template #default="scope">{{ money(scope.row.refundAmount) }}</template></el-table-column>
      <el-table-column label="状态" width="150"><template #default="scope"><el-tag :type="scope.row.status === 'REFUNDED' ? 'success' : pending(scope.row) ? 'warning' : 'info'">{{ statusLabel(scope.row) }}</el-tag></template></el-table-column>
      <el-table-column label="申请时间" prop="createdAt" min-width="170" />
      <el-table-column label="操作" width="110" fixed="right"><template #default="scope"><el-button link type="primary" @click="open(scope.row)">{{ ['REFUNDED','REJECTED'].includes(scope.row.status) ? '查看记录' : '处理申请' }}</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="page" v-model:limit="pageSize" @pagination="refresh" />
    <el-drawer v-model="visible" title="售后申请详情" size="min(640px, 100vw)" :close-on-click-modal="!acting" :close-on-press-escape="!acting" :show-close="!acting">
      <div v-loading="detailLoading" class="after-sales-detail">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
        <template v-if="detail">
          <p class="status">{{ statusLabel(detail) }}</p>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="订单编号">{{ detail.orderId }}</el-descriptions-item>
            <el-descriptions-item label="售后原因">{{ detail.reason }}</el-descriptions-item>
            <el-descriptions-item label="退款金额">{{ money(detail.refundAmount) }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.reviewNote" label="审核说明">{{ detail.reviewNote }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.returnReceiptId" label="退货入库单">{{ detail.returnReceiptId }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.returnCondition" label="验收结果">{{ conditionLabels[detail.returnCondition] || detail.returnCondition }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.refundId" label="沙箱退款凭证">{{ detail.refundId }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.refundOperation" label="渠道处理结果">{{ outcomeLabels[detail.refundOperation.outcome] || detail.refundOperation.outcome }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="detail.items || []" empty-text="暂无商品明细">
            <el-table-column label="商品" min-width="230"><template #default="scope"><div class="return-product"><img :src="getProductImage(scope.row)" :alt="scope.row.productName" @error="handleProductImageError($event, scope.row)" /><div>{{ scope.row.productName }}<small>{{ scope.row.spec }}</small></div></div></template></el-table-column><el-table-column label="申请数量" prop="quantity" width="95" />
          </el-table>
          <div v-if="can('REVIEW')" class="actions">
            <el-input v-model="note" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="审核说明（拒绝时请说明原因）" :disabled="acting" />
            <el-button :disabled="acting" @click="review('REJECT')">拒绝申请</el-button><el-button type="primary" :loading="acting" @click="review('APPROVE')">审核通过</el-button>
          </div>
          <div v-else-if="can('ACCEPT_RETURN')" class="actions">
            <p>核验本申请商品和数量，选择实际状态后返原仓。</p>
            <el-button type="primary" :loading="acting" @click="acceptReturn">登记退货验收</el-button>
          </div>
          <div v-else-if="can('SANDBOX_REFUND')" class="actions">
            <el-button type="primary" :loading="acting" @click="refund">沙箱退款 {{ money(detail.refundAmount) }}</el-button>
          </div>
          <div v-else-if="can('QUERY_REFUND')" class="actions"><el-button type="primary" :loading="acting" @click="queryRefund">查询退款结果</el-button></div>
          <details class="records"><summary>处理时间</summary><p v-for="row in times" :key="row.key">{{ row.label }}：{{ detail[row.key] || '—' }}</p></details>
        </template>
      </div>
    </el-drawer>
    <el-dialog v-model="acceptVisible" title="登记退货验收" width="min(480px, 95vw)" :close-on-click-modal="!acting" :close-on-press-escape="!acting" :show-close="!acting">
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
      <p>请按实际商品状态选择验收结果。</p>
      <el-select v-model="returnCondition" placeholder="选择验收结果" aria-label="退货验收结果" :disabled="acting" style="width:100%"><el-option v-for="(label, value) in conditionLabels" :key="value" :value="value" :label="label" /></el-select>
      <p class="records">待质检与损坏商品入库后保持不可售，退款另行处理。</p>
      <template #footer><el-button :disabled="acting" @click="acceptVisible = false">返回</el-button><el-button type="primary" :disabled="!returnCondition" :loading="acting" @click="submitReturn">确认验收并入库</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listCommerceAfterSales, getCommerceAfterSales, reviewCommerceAfterSales, acceptCommerceReturn, refundCommerceAfterSales, queryCommercePayment } from '@/api/commerce/orders'
import { getProductImage, handleProductImageError } from '@/utils/productMedia'
const props = defineProps({shopId: {type:String, required:true}, capabilities: {type:Array, default:()=>[]}})
const emit = defineEmits(['updated'])
const rows = ref([]), total = ref(0), page = ref(1), pageSize = ref(20), loading = ref(false), error = ref('')
const visible = ref(false), detail = ref(null), detailLoading = ref(false), detailError = ref(''), acting = ref(false), note = ref('')
const acceptVisible = ref(false), returnCondition = ref('')
const conditionLabels = {SELLABLE:'完好可售',QUALITY_HOLD:'待质检',DAMAGED:'损坏'}
const labels = {REQUESTED:'待审核',APPROVED:'待退款',AWAITING_RETURN:'待退货验收',RETURN_RECEIVED:'已验收，待退款',REFUNDED:'已退款',REJECTED:'已拒绝'}
const outcomeLabels = {PREPARED:'请求待确认',PENDING:'退款处理中',UNKNOWN:'退款结果待确认',SUCCEEDED:'退款成功',FAILED:'退款失败'}
const pending = row => ['PREPARED','PENDING','UNKNOWN'].includes(row.refundOperation?.outcome)
const statusLabel = row => pending(row) ? outcomeLabels[row.refundOperation.outcome] : row.statusName || labels[row.status]
const times = computed(() => [{key:'createdAt',label:'申请'},{key:'reviewedAt',label:'审核'},{key:'returnedAt',label:'验收'},{key:'refundedAt',label:'退款'}].filter(row => detail.value?.[row.key]))
const can = action => props.capabilities.includes(({REVIEW:'REFUND_REVIEW',ACCEPT_RETURN:'FULFILMENT',SANDBOX_REFUND:'REFUND_EXECUTE',QUERY_REFUND:'REFUND_EXECUTE'})[action]) && (Array.isArray(detail.value?.availableActions) ? detail.value.availableActions.includes(action) : ({REVIEW:['REQUESTED'],ACCEPT_RETURN:['AWAITING_RETURN'],SANDBOX_REFUND:['APPROVED','RETURN_RECEIVED']}[action] || []).includes(detail.value?.status))
const money = value => new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'}).format(Number(value || 0))
const operationKeys = new Map()
const keyFor = action => {const key = detail.value.afterSalesId + ':' + action;if (!operationKeys.has(key)) operationKeys.set(key,crypto.randomUUID());return operationKeys.get(key)}
async function refresh() {
  if (loading.value) return
  loading.value = true; error.value = ''; const shop = props.shopId
  try {const response = await listCommerceAfterSales({pageNum:page.value,pageSize:pageSize.value});if (shop !== props.shopId) return;rows.value = response.data.rows;total.value = Number(response.data.total)}
  catch (failure) {if (shop === props.shopId) error.value = failure?.message || '售后记录读取失败'}
  finally {loading.value = false;if (shop !== props.shopId) void refresh()}
}
async function open(row) {
  visible.value = true; detail.value = null; detailError.value = ''; note.value = ''; returnCondition.value = ''; acceptVisible.value = false; detailLoading.value = true; const shop = props.shopId
  try {const response = await getCommerceAfterSales(row.afterSalesId);if (shop === props.shopId) detail.value = response.data}
  catch (failure) {if (shop === props.shopId) detailError.value = failure?.message || '售后详情读取失败'}
  finally {detailLoading.value = false}
}
async function perform(action, operation) {
  if (acting.value || !detail.value) return
  acting.value = true; detailError.value = ''; const shop = props.shopId
  try {await operation(keyFor(action));if (shop !== props.shopId) return;detail.value = (await getCommerceAfterSales(detail.value.afterSalesId)).data;ElMessage.success('处理结果已更新');emit('updated');await refresh()}
  catch (failure) {if (shop === props.shopId) detailError.value = failure?.message || '处理结果暂未确认，请重试原操作'}
  finally {acting.value = false}
}
async function review(decision) {
  if (!can('REVIEW')) return
  if (decision === 'REJECT' && !note.value.trim()) {ElMessage.warning('请填写拒绝原因');return}
  await perform('review-' + decision, requestKey => reviewCommerceAfterSales(detail.value.afterSalesId,{requestKey,decision,note:note.value.trim()}))
}
async function acceptReturn() {
  if (!can('ACCEPT_RETURN')) return
  detailError.value = ''; acceptVisible.value = true
}
async function submitReturn() {
  if (!can('ACCEPT_RETURN') || !conditionLabels[returnCondition.value] || acting.value) return
  const condition = returnCondition.value
  await perform('accept-return-' + condition,requestKey => acceptCommerceReturn(detail.value.afterSalesId,{requestKey,condition}))
  if (detail.value?.status !== 'AWAITING_RETURN') acceptVisible.value = false
}
async function refund() {
  if (!can('SANDBOX_REFUND')) return
  const confirmed = await ElMessageBox.confirm('确认在本地支付沙箱退回 ' + money(detail.value.refundAmount) + '？','沙箱退款',{confirmButtonText:'确认退款',cancelButtonText:'返回'}).then(()=>true).catch(()=>false)
  if (confirmed && can('SANDBOX_REFUND')) await perform('refund-' + (detail.value.refundOperation?.operationId || 'first'),requestKey => refundCommerceAfterSales(detail.value.afterSalesId,{requestKey,scenario:'success'}))
}
async function queryRefund() {
  if (!can('QUERY_REFUND')) return
  await perform('query', () => queryCommercePayment(detail.value.refundOperation.operationId))
}
watch(() => props.shopId, () => {visible.value = false;acceptVisible.value = false;detail.value = null;rows.value = [];total.value = 0;page.value = 1;void refresh()})
onMounted(refresh)
defineExpose({refresh})
</script>
<style scoped>
.return-product{display:flex;align-items:center;gap:12px}.return-product img{width:44px;height:44px;object-fit:contain;border-radius:6px;background:var(--sc-surface-soft)}.return-product small{display:block;color:var(--sc-muted)}
.after-sales-panel{padding:22px;margin-bottom:22px;background:var(--sc-surface);border:1px solid var(--sc-border);border-radius:10px}.after-sales-panel>header{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.after-sales-panel h2{font-size:18px;margin:0}.status{font-size:19px;font-weight:600}.after-sales-detail{line-height:1.8}.after-sales-detail .el-table{margin-top:20px}.actions{margin-top:22px}.actions>.el-button{margin-top:16px}.actions p,.records{font-size:13px;color:var(--sc-muted)}.records{margin-top:24px}.records summary{cursor:pointer}
</style>
