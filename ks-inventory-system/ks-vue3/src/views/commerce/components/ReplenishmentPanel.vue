<template>
  <section class="replenishment-panel" aria-labelledby="replenishment-title">
    <header><div><h2 id="replenishment-title">备货计划</h2><span>近 7 日净出库与当前可售库存</span></div><div class="planning-toolbar"><el-switch v-model="showAll" active-text="全部商品" inactive-text="缺口与逾期" aria-label="显示全部商品" /><el-button :loading="loading" text @click="refresh">刷新建议</el-button></div></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="planning-alert" />
    <el-table :data="visibleRows" v-loading="loading" row-key="productId" :empty-text="showAll ? '当前店铺暂无可计算的上架商品' : '当前没有补货缺口或逾期在途'">
      <el-table-column label="商品" min-width="250"><template #default="scope"><div class="planning-product"><img :src="getProductImage(scope.row)" :alt="scope.row.productName" loading="lazy" @error="handleProductImageError($event, scope.row)" /><div><strong>{{ scope.row.productName }}</strong><small>{{ scope.row.productCode }}</small></div></div></template></el-table-column>
      <el-table-column label="7日净出库" prop="salesUnits" align="right" width="110" />
      <el-table-column label="账面 / 预留" align="right" width="115"><template #default="scope">{{ scope.row.onHandStock }} / {{ scope.row.reservedStock }}</template></el-table-column>
      <el-table-column label="可售" prop="availableStock" align="right" width="85" />
      <el-table-column label="登记在途" align="right" width="110"><template #default="scope">{{ scope.row.incomingStock }}<small v-if="Number(scope.row.overdueIncoming) > 0" class="planning-overdue">逾期 {{ scope.row.overdueIncoming }}</small><small v-else class="planning-muted">仅已登记批次</small></template></el-table-column>
      <el-table-column label="不可售" prop="unavailableStock" align="right" width="85" />
      <el-table-column label="下限 / 目标" align="right" width="115"><template #default="scope">{{ scope.row.reorderPoint }} / {{ scope.row.targetStock }}</template></el-table-column>
      <el-table-column label="供货交期" width="105"><template #default="scope"><span v-if="scope.row.leadTimeKnown">{{ scope.row.supplierLeadDays }} 天</span><el-tag v-else size="small" type="warning">未确认</el-tag></template></el-table-column>
      <el-table-column label="建议采购" align="right" width="110"><template #default="scope"><el-tooltip :content="scope.row.reason || '根据经营数据计算的建议'" placement="top"><strong>{{ scope.row.suggestedQuantity }}</strong></el-tooltip></template></el-table-column>
      <el-table-column label="本次草稿数量" width="170"><template #default="scope"><el-input-number v-if="Number(scope.row.suggestedQuantity) > 0" v-model="quantities[scope.row.productId]" :min="0" :max="Math.min(999, Number(scope.row.suggestedQuantity))" :precision="0" size="small" controls-position="right" :disabled="acting" :aria-label="`${scope.row.productName}备货草稿数量`" /><span v-else class="planning-muted">无需补货</span></template></el-table-column>
    </el-table>
    <div class="planning-actions"><span>{{ selectedItems.length ? `已选 ${selectedItems.length} 项 · ${selectedQuantity} 件` : '选择有缺口的商品生成备货草稿' }}</span><el-button type="primary" :disabled="!selectedItems.length || acting" :loading="acting" @click="createDraft">保存待审批草稿</el-button></div>
    <div class="draft-heading"><h3>审批记录</h3><small>批准计划后仍需创建采购单；审批不增加库存。</small></div>
    <el-table :data="drafts" row-key="draftId" empty-text="暂无备货草稿">
      <el-table-column type="expand"><template #default="scope"><div class="draft-lines"><div v-for="item in scope.row.items || []" :key="item.productId" class="draft-line"><div class="planning-product"><img :src="getProductImage(item)" :alt="item.productName" loading="lazy" @error="handleProductImageError($event, item)" /><div><strong>{{ item.productName }}</strong><small>{{ item.leadTimeKnown ? `供货交期 ${item.supplierLeadDays} 天` : '供货交期未确认' }}</small></div></div><strong>{{ item.quantity }} 件</strong></div><p v-if="scope.row.reviewNote" class="planning-muted">审批意见：{{ scope.row.reviewNote }}</p></div></template></el-table-column>
      <el-table-column label="草稿编号" prop="draftId" min-width="210" />
      <el-table-column label="商品 / 件数" width="130"><template #default="scope">{{ scope.row.items?.length || 0 }} 项 / {{ (scope.row.items || []).reduce((sum, item) => sum + Number(item.quantity || 0), 0) }} 件</template></el-table-column>
      <el-table-column label="状态" width="115"><template #default="scope"><el-tag :type="scope.row.status === 'APPROVED' ? 'success' : scope.row.status === 'REJECTED' ? 'info' : 'warning'">{{ labels[scope.row.status] || scope.row.status }}</el-tag></template></el-table-column>
      <el-table-column label="创建时间" prop="createdAt" min-width="175" />
      <el-table-column label="采购执行" width="115"><template #default><span class="planning-muted">尚未采购</span></template></el-table-column>
      <el-table-column label="操作" width="150" fixed="right"><template #default="scope"><template v-if="scope.row.status === 'PENDING_APPROVAL'"><el-button link type="primary" :disabled="acting" @click="openReview(scope.row, 'APPROVE')">批准计划</el-button><el-button link type="danger" :disabled="acting" @click="openReview(scope.row, 'REJECT')">拒绝</el-button></template><span v-else class="planning-muted">已审批</span></template></el-table-column>
    </el-table>
    <el-dialog v-model="reviewVisible" :title="reviewDecision === 'APPROVE' ? '批准备货计划' : '拒绝备货草稿'" width="min(480px, 95vw)" :close-on-click-modal="!acting" :show-close="!acting">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p>{{ reviewTarget?.draftId }}</p><p v-if="reviewDecision === 'APPROVE'" class="planning-muted">请核对在途与供货交期。批准后由采购人员继续处理。</p><el-input v-model="note" type="textarea" :rows="3" maxlength="160" show-word-limit placeholder="填写审批意见" :disabled="acting" aria-label="审批意见" />
      <template #footer><el-button :disabled="acting" @click="reviewVisible = false">返回</el-button><el-button :type="reviewDecision === 'APPROVE' ? 'primary' : 'danger'" :loading="acting" @click="reviewDraft">{{ reviewDecision === 'APPROVE' ? '确认批准计划' : '确认拒绝' }}</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getCommerceReplenishment, listReplenishmentDrafts, createReplenishmentDraft, reviewReplenishmentDraft } from '@/api/commerce/orders'
import { getProductImage, handleProductImageError } from '@/utils/productMedia'
const props = defineProps({shopId: {type: String, required: true}})
const rows = ref([]), drafts = ref([]), loading = ref(false), acting = ref(false), error = ref('')
const showAll = ref(false)
const visibleRows = computed(() => showAll.value ? rows.value : rows.value.filter(row => Number(row.suggestedQuantity) > 0 || Number(row.overdueIncoming) > 0))
const quantities = reactive({}), reviewVisible = ref(false), reviewTarget = ref(null), reviewDecision = ref('APPROVE'), note = ref('')
const labels = {PENDING_APPROVAL:'待审批',APPROVED:'计划已批准',REJECTED:'已拒绝'}
const selectedItems = computed(() => rows.value.filter(row => Number(quantities[row.productId]) > 0).map(row => ({productId: String(row.productId), quantity: Number(quantities[row.productId])})))
const selectedQuantity = computed(() => selectedItems.value.reduce((sum, row) => sum + row.quantity, 0))
let draftFingerprint = '', draftKey = '', reviewFingerprint = '', reviewKey = ''
async function refresh() {
  if (loading.value) return
  loading.value = true; error.value = ''; const shop = props.shopId
  try {
    const [suggestion, history] = await Promise.all([getCommerceReplenishment(), listReplenishmentDrafts()])
    if (shop !== props.shopId) return
    rows.value = suggestion.data.items || []; drafts.value = history.data || []
    for (const row of rows.value) quantities[row.productId] = Math.min(Number(quantities[row.productId]) || 0, Number(row.suggestedQuantity) || 0, 999)
  } catch (failure) { if (shop === props.shopId) error.value = failure?.message || '备货数据读取失败，请刷新重试' }
  finally { loading.value = false; if (shop !== props.shopId) void refresh() }
}
async function createDraft() {
  if (acting.value || !selectedItems.value.length) return
  const items = selectedItems.value, fingerprint = JSON.stringify([props.shopId, items]); const shop = props.shopId
  if (fingerprint !== draftFingerprint) { draftFingerprint = fingerprint; draftKey = crypto.randomUUID() }
  acting.value = true; error.value = ''
  try { await createReplenishmentDraft({requestKey: draftKey, items}); if (shop !== props.shopId) return; for (const item of items) quantities[item.productId] = 0; draftFingerprint = ''; ElMessage.success('已保存待审批草稿'); await refresh() }
  catch (failure) { if (shop === props.shopId) error.value = failure?.message || '保存结果暂未确认，请重试原操作' }
  finally { acting.value = false }
}
function openReview(draft, decision) {reviewTarget.value = draft; reviewDecision.value = decision; note.value = ''; reviewVisible.value = true}
async function reviewDraft() {
  if (acting.value || !reviewTarget.value) return
  if (!note.value.trim()) { ElMessage.warning('请填写审批意见'); return }
  const body = {decision: reviewDecision.value, note: note.value.trim()}, id = reviewTarget.value.draftId, shop = props.shopId
  const fingerprint = JSON.stringify([shop, id, body]); if (fingerprint !== reviewFingerprint) {reviewFingerprint = fingerprint; reviewKey = crypto.randomUUID()}
  acting.value = true; error.value = ''
  try { await reviewReplenishmentDraft(id, {requestKey: reviewKey, ...body}); if (shop !== props.shopId) return; reviewVisible.value = false; ElMessage.success(body.decision === 'APPROVE' ? '备货计划已批准' : '草稿已拒绝'); await refresh() }
  catch (failure) { if (shop === props.shopId) error.value = failure?.message || '审批结果暂未确认，请重试原操作' }
  finally { acting.value = false }
}
watch(() => props.shopId, () => {rows.value = [];drafts.value = [];reviewVisible.value = false;reviewTarget.value = null;Object.keys(quantities).forEach(key => delete quantities[key]);void refresh()})
onMounted(refresh)
defineExpose({refresh})
</script>
<style scoped>
.planning-toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.planning-overdue{display:block;font-size:12px;color:var(--sc-warning)}
.replenishment-panel{padding:22px;margin-bottom:22px;background:var(--sc-surface);border:1px solid var(--sc-border);border-radius:10px}.replenishment-panel>header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px}.replenishment-panel h2{font-size:18px;margin:0 0 5px}.replenishment-panel header span,.planning-muted,.draft-heading small{font-size:12px;color:var(--sc-muted)}.planning-muted{display:block;line-height:1.7}.planning-product{display:flex;gap:12px;align-items:center}.planning-product img{width:44px;height:44px;object-fit:contain;border:1px solid var(--sc-border);border-radius:6px;background:var(--sc-surface-soft)}.planning-product strong{font-size:13px}.planning-product small{display:block;color:var(--sc-muted);font-size:12px;margin-top:3px}.planning-actions{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;padding:18px 0;color:var(--sc-muted);font-size:13px}.draft-heading{border-top:1px solid var(--sc-border);padding-top:18px;margin:5px 0 18px}.draft-heading h3{font-size:15px;margin:0 0 5px}.draft-lines{padding:5px 24px}.draft-line{display:flex;justify-content:space-between;align-items:center;padding:10px 0;border-bottom:1px solid var(--sc-border)}.planning-alert{margin-bottom:14px}:deep(.el-input-number){width:140px}@media(max-width:767px){.replenishment-panel{padding:14px}.planning-actions .el-button{width:100%}}
</style>
