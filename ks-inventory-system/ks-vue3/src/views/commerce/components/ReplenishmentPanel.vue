<template>
  <section class="replenishment-panel" aria-labelledby="replenishment-title">
    <header><div><h2 id="replenishment-title">备货计划</h2><span>近 7 日净出库与当前可售库存</span></div><div class="planning-toolbar"><span v-if="!dispatchPolicy">发货额度未配置</span><el-button v-if="canPolicy" :disabled="loading || acting" text @click="openDispatchPolicy">发货设置</el-button><el-switch v-model="showAll" active-text="全部商品" inactive-text="缺口与逾期" aria-label="显示全部商品" /><el-button :loading="loading" text @click="refresh">刷新建议</el-button></div></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="planning-alert" />
    <el-table :data="visibleRows" v-loading="loading" row-key="productId" :empty-text="showAll ? '当前店铺暂无可计算的上架商品' : '当前没有补货缺口或逾期在途'">
      <el-table-column label="商品" min-width="250"><template #default="scope"><div class="planning-product"><img :src="getProductImage(scope.row)" :alt="scope.row.productName" loading="lazy" @error="handleProductImageError($event, scope.row)" /><div><strong>{{ scope.row.productName }}</strong><small>{{ scope.row.productCode }}</small></div></div></template></el-table-column>
      <el-table-column label="7日净出库" prop="salesUnits" align="right" width="110" />
      <el-table-column label="账面 / 预留" align="right" width="115"><template #default="scope">{{ scope.row.onHandStock }} / {{ scope.row.reservedStock }}</template></el-table-column>
      <el-table-column label="可售" prop="availableStock" align="right" width="85" />
      <el-table-column label="登记在途" align="right" width="110"><template #header><el-tooltip content="仅统计已登记的供应商确认批次" placement="top"><span tabindex="0">登记在途 ⓘ</span></el-tooltip></template><template #default="scope">{{ scope.row.incomingStock }}<small v-if="Number(scope.row.overdueIncoming) > 0" class="planning-overdue">逾期 {{ scope.row.overdueIncoming }}</small></template></el-table-column>
      <el-table-column label="不可售" prop="unavailableStock" align="right" width="85" />
      <el-table-column label="下限 / 目标" align="right" width="115"><template #default="scope">{{ scope.row.reorderPoint }} / {{ scope.row.targetStock }}</template></el-table-column>
      <el-table-column label="供货交期" width="105"><template #default="scope"><span v-if="scope.row.leadTimeKnown">{{ scope.row.supplierLeadDays }} 天</span><el-tag v-else size="small" type="warning">未确认</el-tag></template></el-table-column>
      <el-table-column label="建议采购" align="right" width="110"><template #default="scope"><el-tooltip :content="scope.row.reason || '根据经营数据计算的建议'" placement="top"><strong tabindex="0">{{ scope.row.suggestedQuantity }}</strong></el-tooltip></template></el-table-column>
      <el-table-column v-if="canDraft" label="本次草稿数量" width="170"><template #default="scope"><el-input-number v-if="Number(scope.row.suggestedQuantity) > 0" v-model="quantities[scope.row.productId]" :min="0" :max="Math.min(999, Number(scope.row.suggestedQuantity))" :precision="0" size="small" controls-position="right" :disabled="acting" :aria-label="`${scope.row.productName}备货草稿数量`" /><span v-else class="planning-muted">{{ scope.row.resolutionRequired ? '先处理供货异常' : '无需补货' }}</span></template></el-table-column>
    </el-table>
    <div v-if="canDraft" class="planning-actions"><span>{{ selectedItems.length ? `已选 ${selectedItems.length} 项 · ${selectedQuantity} 件` : '选择有缺口的商品生成备货草稿' }}</span><el-button type="primary" :disabled="!selectedItems.length || acting" :loading="acting" @click="createDraft">保存待审批草稿</el-button></div>
    <div class="draft-heading"><h3>备货与采购记录</h3><details class="planning-flow"><summary>采购流程</summary><ol><li>由其他成员独立审批备货草稿</li><li>登记供应商确认批次</li><li>实际收货后通过 ERP 单据入库</li></ol></details></div>
    <el-select v-model="history.drafts.status" aria-label="备货草稿状态" @change="filterHistory('drafts')"><el-option label="未完成" value="UNFINISHED" /><el-option label="全部状态" value="ALL" /><el-option v-for="(label, status) in labels" :key="status" :label="label" :value="status" /></el-select>
    <el-table :data="drafts" v-loading="history.drafts.loading" row-key="draftId" empty-text="暂无备货草稿">
      <el-table-column type="expand"><template #default="scope"><div class="draft-lines"><div v-for="item in scope.row.items || []" :key="item.productId" class="draft-line"><div class="planning-product"><img :src="getProductImage(item)" :alt="item.productName" loading="lazy" @error="handleProductImageError($event, item)" /><div><strong>{{ item.productName }}</strong><small>{{ item.leadTimeKnown ? `供货交期 ${item.supplierLeadDays} 天` : '供货交期未确认' }}</small><small v-if="supplyLine(scope.row, item)?.incomingId">在途批次 {{ supplyLine(scope.row, item).incomingId }} · {{ lineLabels[supplyLine(scope.row, item).state] || supplyLine(scope.row, item).state }}</small></div></div><strong>{{ item.quantity }} 件</strong></div><p v-if="scope.row.reviewNote" class="planning-muted">审批意见：{{ scope.row.reviewNote }}</p></div></template></el-table-column>
      <el-table-column label="草稿编号" prop="draftId" min-width="210" />
      <el-table-column label="商品 / 件数" width="130"><template #default="scope">{{ scope.row.items?.length || 0 }} 项 / {{ (scope.row.items || []).reduce((sum, item) => sum + Number(item.quantity || 0), 0) }} 件</template></el-table-column>
      <el-table-column label="状态" width="125"><template #default="scope"><el-tag :type="['APPROVED','EXECUTED','RECEIVED'].includes(scope.row.status) ? 'success' : scope.row.status === 'PENDING_APPROVAL' ? 'warning' : 'info'">{{ labels[scope.row.status] || scope.row.status }}</el-tag></template></el-table-column>
      <el-table-column label="创建时间" prop="createdAt" min-width="175" />
      <el-table-column label="采购执行" width="145"><template #default="scope"><span class="planning-muted">{{ executionLabel(scope.row) }}</span></template></el-table-column>
      <el-table-column label="操作" width="210" fixed="right"><template #default="scope"><template v-if="canReview(scope.row)"><el-button link type="primary" :disabled="acting" @click="openReview(scope.row, 'APPROVE')">批准计划</el-button><el-button link type="danger" :disabled="acting" @click="openReview(scope.row, 'REJECT')">拒绝</el-button></template><el-button v-if="canExecute(scope.row)" link type="primary" :disabled="acting" @click="openSupply(scope.row, 'EXECUTE')">登记采购确认</el-button><el-button v-if="canCancel(scope.row)" link type="danger" :disabled="acting" @click="openSupply(scope.row, 'CANCEL')">取消草稿</el-button><span v-if="scope.row.status === 'PENDING_APPROVAL' && !canReview(scope.row)" class="planning-muted">{{ isCreator(scope.row) ? '待其他成员审批' : '待审批' }}</span></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="history.drafts.pageNum" v-model:page-size="history.drafts.pageSize" :total="history.drafts.total" :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="loadHistory('drafts')" @size-change="filterHistory('drafts')" />
    <div class="draft-heading"><h3>在途与供货异常</h3></div>
    <el-select v-model="history.incoming.status" aria-label="供货批次状态" @change="filterHistory('incoming')"><el-option label="未收完" value="UNFINISHED" /><el-option label="逾期未收完" value="OVERDUE" /><el-option label="全部状态" value="ALL" /><el-option v-for="status in ['CONFIRMED', 'RECEIVED', 'CANCELLED', 'PARTIAL_CLOSED']" :key="status" :label="lineLabels[status]" :value="status" /></el-select>
    <el-table :data="incomingRows" v-loading="history.incoming.loading" row-key="incomingId" empty-text="暂无登记在途">
      <el-table-column label="商品 / 批次" min-width="260"><template #default="scope"><div class="planning-product"><img :src="getProductImage(productRow(scope.row.productId))" :alt="productName(scope.row.productId)" loading="lazy" @error="handleProductImageError($event, productRow(scope.row.productId))" /><div><strong>{{ productName(scope.row.productId) }}</strong><small>{{ scope.row.incomingId }}</small></div></div></template></el-table-column>
      <el-table-column label="确认 / 已收 / 取消" width="155" align="right"><template #default="scope">{{ scope.row.quantity }} / {{ scope.row.receivedQuantity }} / {{ scope.row.cancelledQuantity }}</template></el-table-column>
      <el-table-column label="待到货" prop="outstandingQuantity" width="90" align="right" />
      <el-table-column label="预计到货" min-width="170"><template #default="scope">{{ scope.row.expectedAt?.replace('T', ' ') }}<small v-if="scope.row.overdue" class="planning-overdue">逾期 · 原承诺待处理</small></template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="scope.row.overdue ? 'warning' : 'info'">{{ lineLabels[scope.row.status] || scope.row.status }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="190" fixed="right"><template #default="scope"><template v-if="canChangeIncoming(scope.row)"><el-button link type="primary" :disabled="acting" @click="openIncomingChange(scope.row, 'DELAY')">申请延期</el-button><el-button link type="danger" :disabled="acting" @click="openIncomingChange(scope.row, 'CANCEL_REMAINDER')">取消剩余</el-button></template><span v-else-if="pendingIncomingChange(scope.row)" class="planning-muted">变更待审批</span></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="history.incoming.pageNum" v-model:page-size="history.incoming.pageSize" :total="history.incoming.total" :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="loadHistory('incoming')" @size-change="filterHistory('incoming')" />
    <div class="draft-heading"><h3>供货变更审批</h3></div>
    <el-select v-model="history.changes.status" aria-label="供货变更状态" @change="filterHistory('changes')"><el-option label="全部状态" value="ALL" /><el-option v-for="(label, status) in changeLabels" :key="status" :label="label" :value="status" /></el-select>
    <el-table :data="incomingChanges" v-loading="history.changes.loading" row-key="changeId" empty-text="暂无供货变更申请">
      <el-table-column label="变更" min-width="230"><template #default="scope">{{ productName(scope.row.before?.productId) }} · {{ scope.row.action === 'DELAY' ? '延期' : '取消剩余' }}<small class="planning-muted">{{ scope.row.incomingId }}</small></template></el-table-column>
      <el-table-column label="变更内容" min-width="220"><template #default="scope"><span v-if="scope.row.action === 'DELAY'">{{ scope.row.before?.expectedAt?.replace('T', ' ') }} → {{ scope.row.after?.expectedAt?.replace('T', ' ') }}</span><span v-else>取消 {{ Number(scope.row.after?.cancelledQuantity || 0) - Number(scope.row.before?.cancelledQuantity || 0) }} 件，已收 {{ scope.row.before?.receivedQuantity }} 件保留</span></template></el-table-column>
      <el-table-column label="原因 / 依据" min-width="180"><template #default="scope">{{ scope.row.reason }}<small class="planning-muted">{{ scope.row.sourceReference }}</small><small v-if="scope.row.reviewNote" class="planning-muted">审批：{{ scope.row.reviewNote }}</small></template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="scope"><el-tag :type="scope.row.status === 'PENDING_APPROVAL' ? 'warning' : scope.row.status === 'APPLIED' ? 'success' : 'info'">{{ changeLabels[scope.row.status] || scope.row.status }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="175" fixed="right"><template #default="scope"><template v-if="canReviewChange(scope.row)"><el-button link type="primary" :disabled="acting" @click="openChangeReview(scope.row, 'APPROVE')">批准变更</el-button><el-button link type="danger" :disabled="acting" @click="openChangeReview(scope.row, 'REJECT')">拒绝</el-button></template><span v-else-if="scope.row.status === 'PENDING_APPROVAL'" class="planning-muted">待其他成员审批</span></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="history.changes.pageNum" v-model:page-size="history.changes.pageSize" :total="history.changes.total" :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="loadHistory('changes')" @size-change="filterHistory('changes')" />
    <el-dialog v-model="policyVisible" title="发货设置" width="min(440px, 95vw)" :close-on-click-modal="!acting" :close-on-press-escape="!acting" :show-close="!acting">
      <el-form label-position="top" @submit.prevent="submitDispatchPolicy">
        <el-form-item label="每日发货额度（件）" required><el-input-number v-model="policyForm.dailyItemCapacity" :min="1" :max="1000000" :precision="0" placeholder="必填" :disabled="acting" controls-position="right" aria-label="每日发货额度（必填）" /></el-form-item>
        <el-form-item label="发货处理天数" required><el-input-number v-model="policyForm.dispatchDays" :min="0" :max="30" :precision="0" placeholder="必填" :disabled="acting" controls-position="right" aria-label="发货处理天数（必填）" /></el-form-item>
      </el-form>
      <el-alert v-if="policyError" :title="policyError" type="error" :closable="false" />
      <template #footer><el-button :disabled="acting" @click="policyVisible = false">返回</el-button><el-button type="primary" :disabled="!canPolicy" :loading="acting" @click="submitDispatchPolicy">保存设置</el-button></template>
    </el-dialog>
    <el-dialog v-model="changeVisible" :title="changeAction === 'DELAY' ? '申请供应商延期' : '申请取消剩余供货'" width="min(500px, 95vw)" :close-on-click-modal="!acting" :close-on-press-escape="!acting" :show-close="!acting">
      <p>{{ productName(changeTarget?.productId) }} · 待到货 {{ changeTarget?.outstandingQuantity }} 件</p>
      <el-form label-position="top" @submit.prevent="submitIncomingChange">
        <el-form-item v-if="changeAction === 'DELAY'" label="新的预计到货时间"><el-date-picker v-model="changeForm.expectedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :disabled="acting" style="width:100%" /></el-form-item>
        <el-form-item label="供应商变更依据"><el-input v-model="changeForm.sourceReference" maxlength="80" :disabled="acting" placeholder="供应商通知或取消确认记录" /></el-form-item>
        <el-form-item label="变更原因"><el-input v-model="changeForm.reason" type="textarea" :rows="3" maxlength="160" :disabled="acting" /></el-form-item>
      </el-form>
      <el-alert v-if="changeError" :title="changeError" type="error" :closable="false" />
      <template #footer><el-button :disabled="acting" @click="changeVisible = false">返回</el-button><el-button type="primary" :disabled="!canChangeIncoming(changeTarget)" :loading="acting" @click="submitIncomingChange">提交独立审批</el-button></template>
    </el-dialog>
    <el-dialog v-model="changeReviewVisible" :title="changeReviewDecision === 'APPROVE' ? '批准供货变更' : '拒绝供货变更'" width="min(480px, 95vw)" :close-on-click-modal="!acting" :close-on-press-escape="!acting" :show-close="!acting">
      <p>{{ changeReviewTarget?.reason }}</p><p class="planning-muted">{{ changeReviewTarget?.sourceReference }}</p>
      <el-input v-model="changeReviewNote" type="textarea" :rows="3" maxlength="160" placeholder="填写审批意见" :disabled="acting" aria-label="供货变更审批意见" />
      <el-alert v-if="changeError" :title="changeError" type="error" :closable="false" />
      <template #footer><el-button :disabled="acting" @click="changeReviewVisible = false">返回</el-button><el-button :type="changeReviewDecision === 'APPROVE' ? 'primary' : 'danger'" :disabled="!canReviewChange(changeReviewTarget)" :loading="acting" @click="submitChangeReview">确认{{ changeReviewDecision === 'APPROVE' ? '批准' : '拒绝' }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="reviewVisible" :title="reviewDecision === 'APPROVE' ? '批准备货计划' : '拒绝备货草稿'" width="min(480px, 95vw)" :close-on-click-modal="!acting" :close-on-press-escape="!acting" :show-close="!acting">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p>{{ reviewTarget?.draftId }}</p><p v-if="reviewDecision === 'APPROVE'" class="planning-muted">请核对在途与供货交期。批准后由采购人员继续处理。</p><el-input v-model="note" type="textarea" :rows="3" maxlength="160" show-word-limit placeholder="填写审批意见" :disabled="acting" aria-label="审批意见" />
      <template #footer><el-button :disabled="acting" @click="reviewVisible = false">返回</el-button><el-button :type="reviewDecision === 'APPROVE' ? 'primary' : 'danger'" :disabled="!canReview(reviewTarget)" :loading="acting" @click="reviewDraft">{{ reviewDecision === 'APPROVE' ? '确认批准计划' : '确认拒绝' }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="supplyVisible" :title="supplyAction === 'EXECUTE' ? '登记采购确认' : '取消备货草稿'" width="min(480px, 95vw)" :close-on-click-modal="!acting" :close-on-press-escape="!acting" :show-close="!acting">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p>{{ supplyTarget?.draftId }}</p>
      <el-form v-if="supplyAction === 'EXECUTE'" label-position="top" @submit.prevent="submitSupply">
        <el-form-item label="到货仓库编号"><el-input-number v-model="supplyForm.warehouseId" :min="1" :max="Number.MAX_SAFE_INTEGER" :precision="0" :disabled="acting" controls-position="right" aria-label="到货仓库编号" /></el-form-item>
        <el-form-item label="供应商确认依据"><el-input v-model="supplyForm.sourceReference" maxlength="80" placeholder="采购单号或供应商确认记录" :disabled="acting" /></el-form-item>
        <el-form-item label="预计到货时间"><el-date-picker v-model="supplyForm.expectedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="选择确认的到货时间" :disabled="acting" style="width:100%" /></el-form-item>
        <p class="planning-muted">确认后登记在途批次，实际收货后仍需通过 ERP 单据入库。</p>
      </el-form>
      <el-input v-else v-model="supplyForm.reason" type="textarea" :rows="3" maxlength="160" show-word-limit placeholder="填写取消原因" :disabled="acting" aria-label="取消原因" />
      <template #footer><el-button :disabled="acting" @click="supplyVisible = false">返回</el-button><el-button :type="supplyAction === 'EXECUTE' ? 'primary' : 'danger'" :disabled="supplyAction === 'EXECUTE' ? !canExecute(supplyTarget) : !canCancel(supplyTarget)" :loading="acting" @click="submitSupply">{{ supplyAction === 'EXECUTE' ? '确认采购并登记在途' : '确认取消草稿' }}</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getCommerceReplenishment, saveCommercePlanningPolicy, createReplenishmentDraft, reviewReplenishmentDraft, executeReplenishmentDraft, cancelReplenishmentDraft, proposeIncomingChange, reviewIncomingChange } from '@/api/commerce/orders'
import { pageReplenishmentDrafts, pageCommerceIncoming, pageIncomingChanges } from '@/api/commerce/planning'
import { getProductImage, handleProductImageError } from '@/utils/productMedia'
const props = defineProps({shopId: {type: String, required: true}, capabilities: {type: Array, default: () => []}, userId: {type: [String, Number], default: null}, memberRole: {type: String, default: ''}})
const emit = defineEmits(['updated'])
const rows = ref([]), drafts = ref([]), loading = ref(false), acting = ref(false), error = ref('')
const newHistory = status => ({ pageNum: 1, pageSize: 20, total: 0, status, loading: false })
const history = reactive({ drafts: newHistory('UNFINISHED'), incoming: newHistory('UNFINISHED'), changes: newHistory('PENDING_APPROVAL') })
const historyGeneration = { drafts: 0, incoming: 0, changes: 0 }
let generation = 0
const dispatchPolicy = ref(null), policyVisible = ref(false), policyError = ref('')
const policyForm = reactive({dailyItemCapacity: null, dispatchDays: null})
const showAll = ref(false)
const visibleRows = computed(() => showAll.value ? rows.value : rows.value.filter(row => Number(row.suggestedQuantity) > 0 || Number(row.overdueIncoming) > 0))
const quantities = reactive({}), reviewVisible = ref(false), reviewTarget = ref(null), reviewDecision = ref('APPROVE'), note = ref('')
const supplyVisible = ref(false), supplyTarget = ref(null), supplyAction = ref('EXECUTE')
const supplyForm = reactive({warehouseId: undefined, sourceReference: '', expectedAt: '', reason: ''})
const incomingRows = ref([]), incomingChanges = ref([]), changeVisible = ref(false), changeTarget = ref(null), changeAction = ref('DELAY'), changeError = ref('')
const changeForm = reactive({expectedAt:'', reason:'', sourceReference:''})
const changeReviewVisible = ref(false), changeReviewTarget = ref(null), changeReviewDecision = ref('APPROVE'), changeReviewNote = ref('')
const labels = {PENDING_APPROVAL:'待审批',APPROVED:'计划已批准',REJECTED:'已拒绝',CANCELLED:'已取消',EXECUTED:'采购已确认',RECEIVED:'已入库',CLOSED_PARTIAL:'部分入库已关闭'}
const lineLabels = {CONFIRMED:'待收货',EXECUTED:'待收货',RECEIVED:'已入库',CANCELLED:'已取消',PARTIAL_CLOSED:'部分收货已关闭'}
const changeLabels = {PENDING_APPROVAL:'待审批',APPLIED:'已生效',REJECTED:'已拒绝'}
const canDraft = computed(() => props.capabilities.includes('SUPPLY_DRAFT'))
const canPolicy = computed(() => props.capabilities.includes('SUPPLY_POLICY'))
const knownUser = value => value != null && String(value) !== ''
const isCreator = draft => knownUser(props.userId) && knownUser(draft?.createdBy) && String(props.userId) === String(draft.createdBy)
const currentDraft = draft => drafts.value.find(row => row.draftId === draft?.draftId) || draft
const canReview = draft => {const row = currentDraft(draft); return props.capabilities.includes('SUPPLY_REVIEW') && row?.status === 'PENDING_APPROVAL' && knownUser(props.userId) && knownUser(row.createdBy) && !isCreator(row)}
const canExecute = draft => {const row = currentDraft(draft); return canDraft.value && row?.status === 'APPROVED' && knownUser(row.createdBy) && knownUser(row.reviewedBy) && String(row.reviewedBy) !== String(row.createdBy)}
const canCancel = draft => {const row = currentDraft(draft); return canDraft.value && ['PENDING_APPROVAL','APPROVED'].includes(row?.status) && (isCreator(row) || props.memberRole === 'OWNER')}
const supplyLine = (draft, item) => (draft.supplyLines || []).find(line => String(line.productId) === String(item.productId))
const executionLabel = draft => ({SUPPLIER_CONFIRMED:'供应商已确认',ERP_RECEIVED:'ERP 已入库',PARTIAL_RECEIVED_CLOSED:'部分入库，余量已取消',SUPPLIER_CANCELLED:'剩余供货已取消'})[draft.executionStatus] || ({EXECUTED:'供应商已确认',RECEIVED:'ERP 已入库',APPROVED:'待采购确认'})[draft.status] || '未执行'
const productRow = id => rows.value.find(row => String(row.productId) === String(id)) || {productId:id}
const productName = id => rows.value.find(row => String(row.productId) === String(id))?.productName || (id ? `商品 ${id}` : '')
const pendingIncomingChange = row => row?.pendingChange || incomingChanges.value.some(change => change.incomingId === row?.incomingId && change.status === 'PENDING_APPROVAL')
const canChangeIncoming = row => canDraft.value && row?.status === 'CONFIRMED' && Number(row.outstandingQuantity) > 0 && !pendingIncomingChange(row)
const canReviewChange = row => props.capabilities.includes('SUPPLY_REVIEW') && row?.status === 'PENDING_APPROVAL' && knownUser(props.userId) && knownUser(row.createdBy) && String(props.userId) !== String(row.createdBy)
const selectedItems = computed(() => rows.value.filter(row => Number(quantities[row.productId]) > 0).map(row => ({productId: String(row.productId), quantity: Number(quantities[row.productId])})))
const selectedQuantity = computed(() => selectedItems.value.reduce((sum, row) => sum + row.quantity, 0))
let draftFingerprint = '', draftKey = '', reviewFingerprint = '', reviewKey = ''
const supplyKeys = new Map()
function filterHistory(kind) { history[kind].pageNum = 1; void loadHistory(kind) }
async function loadHistory(kind) {
  const shop = props.shopId, current = ++historyGeneration[kind], page = history[kind]
  const request = { drafts: pageReplenishmentDrafts, incoming: pageCommerceIncoming, changes: pageIncomingChanges }[kind]
  const target = { drafts, incoming: incomingRows, changes: incomingChanges }[kind]
  page.loading = true
  try {
    const result = await request(shop, { pageNum: page.pageNum, pageSize: page.pageSize, status: page.status })
    if (shop !== props.shopId || current !== historyGeneration[kind]) return
    target.value = result.data.rows; page.total = result.data.total
    if (!target.value.length && page.pageNum > 1) { page.pageNum = Math.max(1, Math.ceil(page.total / page.pageSize)); void loadHistory(kind) }
  } catch (failure) { if (shop === props.shopId && current === historyGeneration[kind]) error.value = failure?.message || '历史记录读取失败，请刷新重试' }
  finally { if (shop === props.shopId && current === historyGeneration[kind]) page.loading = false }
}
async function refresh() {
  loading.value = true; error.value = ''; const shop = props.shopId, current = ++generation
  try {
    const [suggestion] = await Promise.all([getCommerceReplenishment(), loadHistory('drafts'), loadHistory('incoming'), loadHistory('changes')])
    if (shop !== props.shopId || current !== generation) return
    rows.value = suggestion.data.items || []; dispatchPolicy.value = suggestion.data.dispatchPolicy || null
    for (const row of rows.value) quantities[row.productId] = Math.min(Number(quantities[row.productId]) || 0, Number(row.suggestedQuantity) || 0, 999)
  } catch (failure) { if (shop === props.shopId && current === generation) error.value = failure?.message || '备货数据读取失败，请刷新重试' }
  finally { if (shop === props.shopId && current === generation) loading.value = false }
}
function openDispatchPolicy() {
  if (loading.value || acting.value || !canPolicy.value) return
  Object.assign(policyForm, {dailyItemCapacity: dispatchPolicy.value?.dailyItemCapacity ?? null, dispatchDays: dispatchPolicy.value?.dispatchDays ?? null})
  policyError.value = ''; policyVisible.value = true
}
async function submitDispatchPolicy() {
  if (acting.value || !canPolicy.value) return
  const body = {...policyForm}, shop = props.shopId
  if (!Number.isInteger(body.dailyItemCapacity) || body.dailyItemCapacity < 1 || body.dailyItemCapacity > 1000000 || !Number.isInteger(body.dispatchDays) || body.dispatchDays < 0 || body.dispatchDays > 30) {policyError.value = '请填写每日发货额度（1～1000000）和发货处理天数（0～30）';return}
  acting.value = true; policyError.value = ''
  try {
    await saveCommercePlanningPolicy(body)
    if (shop !== props.shopId) return
    policyVisible.value = false; ElMessage.success('发货设置已保存'); await refresh(); emit('updated')
  } catch (failure) {if (shop === props.shopId) policyError.value = failure?.message || '发货设置保存失败，请重试'}
  finally {acting.value = false}
}
async function createDraft() {
  if (acting.value || !canDraft.value || !selectedItems.value.length) return
  const items = selectedItems.value, fingerprint = JSON.stringify([props.shopId, items]); const shop = props.shopId
  if (fingerprint !== draftFingerprint) { draftFingerprint = fingerprint; draftKey = crypto.randomUUID() }
  acting.value = true; error.value = ''
  try { await createReplenishmentDraft({requestKey: draftKey, items}); if (shop !== props.shopId) return; for (const item of items) quantities[item.productId] = 0; draftFingerprint = ''; ElMessage.success('已保存待审批草稿'); await refresh() }
  catch (failure) { if (shop === props.shopId) error.value = failure?.message || '保存结果暂未确认，请重试原操作' }
  finally { acting.value = false }
}
function openReview(draft, decision) {if (acting.value || !canReview(draft)) return;error.value = '';reviewTarget.value = draft; reviewDecision.value = decision; note.value = ''; reviewVisible.value = true}
async function reviewDraft() {
  if (acting.value || !canReview(reviewTarget.value)) return
  if (!note.value.trim()) { ElMessage.warning('请填写审批意见'); return }
  const body = {decision: reviewDecision.value, note: note.value.trim()}, id = reviewTarget.value.draftId, shop = props.shopId
  const fingerprint = JSON.stringify([shop, id, body]); if (fingerprint !== reviewFingerprint) {reviewFingerprint = fingerprint; reviewKey = crypto.randomUUID()}
  acting.value = true; error.value = ''
  try { await reviewReplenishmentDraft(id, {requestKey: reviewKey, ...body}); if (shop !== props.shopId) return; reviewVisible.value = false; ElMessage.success(body.decision === 'APPROVE' ? '备货计划已批准' : '草稿已拒绝'); await refresh() }
  catch (failure) { if (shop === props.shopId) error.value = failure?.message || '审批结果暂未确认，请重试原操作' }
  finally { acting.value = false }
}
function openSupply(draft, action) {
  if (acting.value || (action === 'EXECUTE' ? !canExecute(draft) : !canCancel(draft))) return
  error.value = ''; supplyTarget.value = draft; supplyAction.value = action
  Object.assign(supplyForm, {warehouseId: undefined, sourceReference: '', expectedAt: '', reason: ''}); supplyVisible.value = true
}
async function submitSupply() {
  const action = supplyAction.value
  if (acting.value || (action === 'EXECUTE' ? !canExecute(supplyTarget.value) : !canCancel(supplyTarget.value))) return
  const body = action === 'EXECUTE' ? {warehouseId: supplyForm.warehouseId, sourceReference: supplyForm.sourceReference.trim(), expectedAt: supplyForm.expectedAt} : {reason: supplyForm.reason.trim()}
  if (action === 'EXECUTE' && (!Number.isSafeInteger(body.warehouseId) || body.warehouseId < 1 || !body.sourceReference || !(new Date(body.expectedAt).getTime() > Date.now()))) {error.value = '请填写到货仓库、供应商确认依据和未来的预计到货时间';return}
  if (action === 'CANCEL' && !body.reason) {error.value = '请填写取消原因';return}
  const shop = props.shopId, id = supplyTarget.value.draftId, fingerprint = JSON.stringify([shop, id, action, body])
  if (!supplyKeys.has(fingerprint)) supplyKeys.set(fingerprint, crypto.randomUUID())
  acting.value = true; error.value = ''
  try {
    await (action === 'EXECUTE' ? executeReplenishmentDraft : cancelReplenishmentDraft)(id, {requestKey: supplyKeys.get(fingerprint), ...body})
    if (shop !== props.shopId) return
    supplyVisible.value = false; ElMessage.success(action === 'EXECUTE' ? '采购确认已登记，在途记录已更新' : '草稿已取消'); await refresh(); emit('updated')
  } catch (failure) {if (shop === props.shopId) error.value = failure?.message || '处理结果暂未确认，请重试原操作'}
  finally {acting.value = false}
}
function openIncomingChange(row, action) {
  if (acting.value || !canChangeIncoming(row)) return
  changeTarget.value = row; changeAction.value = action; changeError.value = ''
  Object.assign(changeForm, {expectedAt:'', reason:'', sourceReference:''}); changeVisible.value = true
}
async function submitIncomingChange() {
  if (acting.value || !canChangeIncoming(changeTarget.value)) return
  const shop = props.shopId, id = changeTarget.value.incomingId, body = {action:changeAction.value, reason:changeForm.reason.trim(), sourceReference:changeForm.sourceReference.trim()}
  if (body.action === 'DELAY') body.expectedAt = changeForm.expectedAt
  if (!body.reason || !body.sourceReference) {changeError.value = '请填写变更原因和供应商依据';return}
  if (body.action === 'DELAY' && !(new Date(body.expectedAt).getTime() > Math.max(Date.now(), new Date(changeTarget.value.expectedAt).getTime()))) {changeError.value = '新到货时间须晚于原承诺且在未来';return}
  const fingerprint = JSON.stringify([shop,id,'INCOMING_CHANGE',body]); if (!supplyKeys.has(fingerprint)) supplyKeys.set(fingerprint,crypto.randomUUID())
  acting.value = true; changeError.value = ''
  try {await proposeIncomingChange(id,{requestKey:supplyKeys.get(fingerprint),...body});if (shop !== props.shopId) return;changeVisible.value = false;ElMessage.success('供货变更已提交独立审批');await refresh()}
  catch (failure) {if (shop === props.shopId) changeError.value = failure?.message || '提交结果暂未确认，请重试原操作'}
  finally {acting.value = false}
}
function openChangeReview(row, decision) {
  if (acting.value || !canReviewChange(row)) return
  changeReviewTarget.value = row;changeReviewDecision.value = decision;changeReviewNote.value = '';changeError.value = '';changeReviewVisible.value = true
}
async function submitChangeReview() {
  if (acting.value || !canReviewChange(changeReviewTarget.value)) return
  const shop = props.shopId, id = changeReviewTarget.value.changeId, body = {decision:changeReviewDecision.value,note:changeReviewNote.value.trim()}
  if (!body.note) {changeError.value = '请填写审批意见';return}
  const fingerprint = JSON.stringify([shop,id,'INCOMING_REVIEW',body]);if (!supplyKeys.has(fingerprint)) supplyKeys.set(fingerprint,crypto.randomUUID())
  acting.value = true;changeError.value = ''
  try {await reviewIncomingChange(id,{requestKey:supplyKeys.get(fingerprint),...body});if (shop !== props.shopId) return;changeReviewVisible.value = false;ElMessage.success(body.decision === 'APPROVE' ? '供货变更已生效' : '供货变更已拒绝');await refresh();emit('updated')}
  catch (failure) {if (shop === props.shopId) changeError.value = failure?.message || '审批结果暂未确认，请重试原操作'}
  finally {acting.value = false}
}
watch(() => props.shopId, () => {generation++;for (const [kind, status] of [['drafts', 'UNFINISHED'], ['incoming', 'UNFINISHED'], ['changes', 'PENDING_APPROVAL']]) {historyGeneration[kind]++;Object.assign(history[kind], newHistory(status))}rows.value = [];drafts.value = [];incomingRows.value = [];incomingChanges.value = [];changeVisible.value = false;changeTarget.value = null;changeReviewVisible.value = false;changeReviewTarget.value = null;changeError.value = '';dispatchPolicy.value = null;policyVisible.value = false;policyError.value = '';Object.assign(policyForm, {dailyItemCapacity: null, dispatchDays: null});reviewVisible.value = false;reviewTarget.value = null;supplyVisible.value = false;supplyTarget.value = null;Object.keys(quantities).forEach(key => delete quantities[key]);void refresh()})
onMounted(refresh)
defineExpose({refresh})
</script>
<style scoped>
.planning-product > div { min-width: 0; }
.planning-product strong, .planning-product small { overflow-wrap: anywhere; }
.planning-flow { margin-top: 4px; font-size: 12px; color: var(--sc-muted); }
.planning-flow summary { display: list-item; cursor: pointer; }
.planning-flow ol { margin: 8px 0 0; padding-left: 20px; line-height: 1.8; }
.replenishment-panel { min-width: 0; }
@media (max-width: 767px) { .replenishment-panel > header { align-items: flex-start; flex-direction: column; } .planning-toolbar { width: 100%; } .planning-toolbar .el-button { flex: 1; } }
.planning-toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.planning-overdue{display:block;font-size:12px;color:var(--sc-warning)}
.replenishment-panel{padding:18px;margin-bottom:18px;background:var(--sc-surface);border:1px solid var(--sc-border);border-radius:8px}.replenishment-panel>header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px}.replenishment-panel h2{font-size:16px;margin:0 0 5px}.replenishment-panel header span,.planning-muted,.draft-heading small{font-size:12px;color:var(--sc-muted)}.planning-muted{display:block;line-height:1.7}.planning-product{display:flex;gap:12px;align-items:center}.planning-product img{width:44px;height:44px;object-fit:contain;border:1px solid var(--sc-border);border-radius:6px;background:var(--sc-surface-soft)}.planning-product strong{font-size:13px}.planning-product small{display:block;color:var(--sc-muted);font-size:12px;margin-top:3px}.planning-actions{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;padding:18px 0;color:var(--sc-muted);font-size:13px}.draft-heading{border-top:1px solid var(--sc-border);padding-top:18px;margin:5px 0 18px}.draft-heading h3{font-size:15px;margin:0 0 5px}.draft-lines{padding:5px 24px}.draft-line{display:flex;justify-content:space-between;align-items:center;padding:10px 0;border-bottom:1px solid var(--sc-border)}.planning-alert{margin-bottom:14px}:deep(.el-input-number){width:140px}@media(max-width:767px){.replenishment-panel{padding:14px}.planning-actions .el-button{width:100%}}
</style>
