<template>
  <section class="agent-tasks" aria-labelledby="agent-task-title">
    <header><div><h2 id="agent-task-title">采购任务</h2><small>计划 · 独立审批 · 供应商确认</small></div><div><el-button :loading="loading" text @click="refresh">刷新</el-button><el-button v-if="canDraft" type="primary" :disabled="acting" @click="openCreate">新建规划任务</el-button></div></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="tasks" v-loading="loading" row-key="taskId" empty-text="暂无采购任务">
      <el-table-column type="expand"><template #default="scope"><div class="task-items"><div v-for="item in scope.row.items" :key="item.productId" class="task-item"><img :src="getProductImage(item)" :alt="item.productName" loading="lazy" @error="handleProductImageError($event, item)" /><div><strong>{{ item.productName }}</strong><small>{{ item.productCode }}</small></div><strong>{{ item.quantity }} 件</strong></div><p v-if="scope.row.status === 'WAITING_APPROVAL' && isCreator(scope.row)" class="muted">待其他成员审批</p></div></template></el-table-column>
      <el-table-column label="经营目标" prop="goal" min-width="250" show-overflow-tooltip />
      <el-table-column label="方案" width="145"><template #default="scope"><strong>v{{ scope.row.planVersion }}</strong><small>{{ scope.row.plannerMode === 'model' ? '模型提案' : '规则提案' }}</small></template></el-table-column>
      <el-table-column label="状态" width="145"><template #default="scope"><el-tag :type="tag(scope.row.status)">{{ labels[scope.row.status] || scope.row.status }}</el-tag><small v-if="scope.row.needsDateRefresh" class="due">跨日待复核</small></template></el-table-column>
      <el-table-column label="事实日期" prop="factDate" width="130" />
      <el-table-column label="操作" width="275" fixed="right"><template #default="scope"><el-button link @click="showDetail(scope.row)">记录</el-button><el-button v-if="canOperate(scope.row) && open(scope.row)" link type="primary" :disabled="acting" @click="resume(scope.row)">复核方案</el-button><template v-if="canReview(scope.row)"><el-button link type="primary" :disabled="acting" @click="openReview(scope.row, 'APPROVE')">审批</el-button><el-button link type="danger" :disabled="acting" @click="openReview(scope.row, 'REJECT')">拒绝</el-button></template><el-button v-if="canOperate(scope.row) && scope.row.status === 'APPROVED'" link type="primary" :disabled="acting" @click="openExecute(scope.row)">确认供货</el-button><el-button v-if="canOperate(scope.row) && open(scope.row)" link type="danger" :disabled="acting" @click="cancel(scope.row)">撤销</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="createVisible" title="新建采购规划任务" width="min(540px, 95vw)" :close-on-click-modal="!acting" :show-close="!acting"><el-form label-position="top"><el-form-item label="经营目标"><el-input v-model="goal" type="textarea" :rows="3" maxlength="500" :disabled="acting" placeholder="例如：按当前补货缺口提出采购计划，先少量备货" /></el-form-item></el-form><template #footer><el-button :disabled="acting" @click="createVisible = false">返回</el-button><el-button type="primary" :loading="acting" :disabled="!goal.trim()" @click="create">生成待审批任务</el-button></template></el-dialog>
    <el-dialog v-model="reviewVisible" :title="decision === 'APPROVE' ? `审批 v${target?.planVersion}` : '拒绝采购任务'" width="min(500px, 95vw)" :close-on-click-modal="!acting" :show-close="!acting"><p>{{ target?.goal }}</p><el-input v-model="note" type="textarea" :rows="3" maxlength="160" :disabled="acting" placeholder="填写核验依据与审批意见" aria-label="审批意见" /><template #footer><el-button :disabled="acting" @click="reviewVisible = false">返回</el-button><el-button type="primary" :loading="acting" :disabled="!note.trim()" @click="review">确认审批</el-button></template></el-dialog>
    <el-dialog v-model="executeVisible" title="登记供应商确认" width="min(500px, 95vw)" :close-on-click-modal="!acting" :show-close="!acting"><el-form label-position="top"><el-form-item label="到货仓库编号"><el-input-number v-model="supply.warehouseId" :min="1" :precision="0" :disabled="acting" /></el-form-item><el-form-item label="供应商确认依据"><el-input v-model="supply.sourceReference" maxlength="80" :disabled="acting" /></el-form-item><el-form-item label="预计到货时间"><el-date-picker v-model="supply.expectedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :disabled="acting" /></el-form-item></el-form><template #footer><el-button :disabled="acting" @click="executeVisible = false">返回</el-button><el-button type="primary" :loading="acting" :disabled="!supply.sourceReference.trim() || !supply.expectedAt" @click="execute">确认供货</el-button></template></el-dialog>
    <el-drawer v-model="detailVisible" title="任务与版本记录" size="min(640px, 95vw)"><div v-loading="detailLoading"><p>{{ detail?.goal }}</p><p class="muted">{{ detail?.taskId }} · {{ labels[detail?.status] }}</p><el-timeline><el-timeline-item v-for="version in detail?.versions || []" :key="version.planVersion" :timestamp="String(version.createdAt)"><strong>方案 v{{ version.planVersion }}</strong><p>{{ version.reason }}</p></el-timeline-item></el-timeline><el-table :data="detail?.events || []"><el-table-column label="版本" width="75"><template #default="scope">v{{ scope.row.planVersion }}</template></el-table-column><el-table-column label="操作" width="130"><template #default="scope">{{ eventLabels[scope.row.action] || scope.row.action }}</template></el-table-column><el-table-column label="操作人" prop="actorId" width="90" /><el-table-column label="记录" prop="detail" /></el-table><p v-if="detail?.draftId" class="muted">采购草稿 {{ detail.draftId }}</p><p v-for="line in detail?.result?.supplyLines || []" :key="line.productId">供货批次 {{ line.incomingId }} · {{ line.quantity }} 件</p></div></el-drawer>
  </section>
</template>
<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAgentTasks, getAgentTask, planProcurementTask, refreshAgentTask, reviewAgentTask, executeAgentTask, cancelAgentTask } from '@/api/commerce/agentTasks'
import { getProductImage, handleProductImageError } from '@/utils/productMedia'
const props = defineProps({ shopId: { type: String, required: true }, capabilities: { type: Array, default: () => [] }, userId: { type: [String, Number], default: null } })
const emit = defineEmits(['updated'])
const tasks = ref([]), loading = ref(false), acting = ref(false), error = ref('')
const createVisible = ref(false), reviewVisible = ref(false), executeVisible = ref(false), detailVisible = ref(false), detailLoading = ref(false), detail = ref(null), target = ref(null)
const detailTaskId = ref(null)
const goal = ref('按当前补货缺口提出采购计划，等待独立审批'), note = ref(''), decision = ref('APPROVE')
const supply = reactive({ warehouseId: 1, sourceReference: '', expectedAt: '' })
let createKey = '', reviewKey = ''
const key = prefix => prefix + ':' + crypto.randomUUID().replaceAll('-', '')
const labels = { WAITING_APPROVAL: '待独立审批', APPROVED: '待供应商确认', EXECUTED: '已登记供货', NO_ACTION: '缺口已消除', REJECTED: '已拒绝', CANCELLED: '已撤销' }
const eventLabels = { CREATED: '创建任务', REPLANNED: '重新规划', APPROVED: '独立审批', EXECUTED: '登记供货', REJECTED: '拒绝', CANCELLED: '撤销' }
const canDraft = computed(() => props.capabilities.includes('SUPPLY_DRAFT'))
const isCreator = row => String(row?.createdBy) === String(props.userId)
const canOperate = row => canDraft.value && (isCreator(row) || props.capabilities.includes('SHOP_MEMBERS'))
const canReview = row => row?.status === 'WAITING_APPROVAL' && props.capabilities.includes('SUPPLY_REVIEW') && !isCreator(row)
const open = row => ['WAITING_APPROVAL', 'APPROVED'].includes(row?.status)
const tag = status => status === 'WAITING_APPROVAL' ? 'warning' : ['APPROVED', 'EXECUTED'].includes(status) ? 'success' : 'info'
const errorText = e => typeof e?.response?.data?.detail === 'string' ? e.response.data.detail : e?.message || '任务操作失败，请刷新后核验状态'
async function refresh() { const shop = props.shopId; loading.value = true; error.value = ''; try { const result = await listAgentTasks(shop); if (shop === props.shopId) tasks.value = result.data } catch (e) { if (shop === props.shopId) error.value = errorText(e) } finally { if (shop === props.shopId) loading.value = false } }
async function act(operation, close) { const shop = props.shopId; let operationError = ''; acting.value = true; error.value = ''; try { const result = await operation(shop); if (shop !== props.shopId) return; close?.(); if (result.data?.replanRequired) ElMessage.warning('经营数据已变化，已生成新版本，请重新审批'); else ElMessage.success('任务已更新'); emit('updated') } catch (e) { if (shop === props.shopId) operationError = errorText(e) } finally { acting.value = false; if (shop === props.shopId) { await refresh(); if (operationError) error.value = operationError } } }
function openCreate() { createKey = key('TASKCREATE'); createVisible.value = true }
async function create() { if (!canDraft.value) return; await act(shop => planProcurementTask(shop, { requestKey: createKey, goal: goal.value.trim() }), () => { createVisible.value = false }) }
async function resume(row) { await act(shop => refreshAgentTask(shop, row.taskId, { requestKey: key('TASKREFRESH') })) }
function openReview(row, value) { target.value = row; decision.value = value; note.value = ''; reviewKey = key('TASKREVIEW'); reviewVisible.value = true }
async function review() { const row = target.value; await act(shop => reviewAgentTask(shop, row.taskId, { requestKey: reviewKey, planVersion: row.planVersion, decision: decision.value, note: note.value.trim() }), () => { reviewVisible.value = false }) }
function commandKey(row) { return `TASKUI:${row.taskId}:${row.planVersion}` }
function storageKey(row) { return `procurement-command:${row.tenantId}:${row.shopId}:${props.userId}:${row.taskId}:${row.planVersion}` }
function openExecute(row) { target.value = row; let saved; try { saved = JSON.parse(sessionStorage.getItem(storageKey(row))) } catch { saved = null } Object.assign(supply, saved || { warehouseId: 1, sourceReference: '', expectedAt: '' }); executeVisible.value = true }
async function execute() { const row = target.value; const body = { requestKey: commandKey(row), planVersion: row.planVersion, warehouseId: supply.warehouseId, sourceReference: supply.sourceReference.trim(), expectedAt: supply.expectedAt }; try { sessionStorage.setItem(storageKey(row), JSON.stringify(body)) } catch { /* Server task remains the recovery authority. */ } await act(shop => executeAgentTask(shop, row.taskId, body), () => { executeVisible.value = false }) }
async function cancel(row) { const response = await ElMessageBox.prompt('撤销原因', '撤销采购任务', { inputValidator: v => !!v?.trim() && v.trim().length <= 160 || '请填写1至160字原因' }).catch(() => null); if (!response) return; await act(shop => cancelAgentTask(shop, row.taskId, { requestKey: key('TASKCANCEL'), reason: response.value.trim() })) }
async function showDetail(row) { const shop = props.shopId; detailTaskId.value = row.taskId; detailVisible.value = true; detailLoading.value = true; detail.value = null; try { const result = await getAgentTask(shop, row.taskId); if (shop === props.shopId && detailTaskId.value === row.taskId) detail.value = result.data } catch (e) { if (shop === props.shopId && detailTaskId.value === row.taskId) error.value = errorText(e) } finally { if (shop === props.shopId && detailTaskId.value === row.taskId) detailLoading.value = false } }
watch(() => props.shopId, () => { tasks.value = []; detail.value = null; target.value = null; detailTaskId.value = null; detailLoading.value = false; createVisible.value = reviewVisible.value = executeVisible.value = detailVisible.value = false; loading.value = false; refresh() }, { immediate: true })
defineExpose({ refresh })
</script>
<style scoped>
.agent-tasks { margin-top: 24px; padding-top: 20px; border-top: 1px solid #e6ebf0; }
header { display: flex; justify-content: space-between; gap: 16px; align-items: center; margin-bottom: 16px; }
h2 { margin: 0 0 5px; font-size: 19px; } small { display: block; font-size: 12px; color: #64748b; margin-top: 4px; }
.task-items { padding: 12px 24px; } .task-item { display: flex; gap: 12px; align-items: center; padding: 8px 0; max-width: 600px; }
.task-item img { width: 48px; height: 48px; object-fit: contain; background: #f4f6f8; border-radius: 8px; } .task-item div { flex: 1; }
.muted { color: #64748b; font-size: 13px; } .due { color: #a6630c; }
@media(max-width: 700px) { header { align-items: flex-start; flex-direction: column; } }
</style>
