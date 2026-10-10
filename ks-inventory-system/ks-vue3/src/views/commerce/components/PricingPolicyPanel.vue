<template>
  <section v-if="canCatalog" class="pricing-panel">
    <header><h2>优惠与运费</h2><el-button text :loading="loading" @click="refresh">刷新</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form class="shipping-policy" inline @submit.prevent="saveShipping"><el-form-item label="每单运费（元）"><el-input v-model="shippingFee" inputmode="decimal" :disabled="acting || loading" /></el-form-item><el-button type="primary" :loading="acting" :disabled="loading" @click="saveShipping">保存运费</el-button></el-form>
    <div class="promotion-heading"><h3>优惠活动</h3><el-button :disabled="acting || loading" @click="edit()">新增优惠</el-button></div>
    <el-table :data="promotions" v-loading="loading" empty-text="暂无优惠活动"><el-table-column label="活动" prop="title" min-width="180" /><el-table-column label="优惠金额" width="110" align="right"><template #default="s">{{ money(s.row.discountAmount) }}</template></el-table-column><el-table-column label="适用商品" min-width="170"><template #default="s">{{ s.row.productIds?.length ? `${s.row.productIds.length} 个商品` : '全店商品' }}</template></el-table-column><el-table-column label="生效时间" prop="startsAt" min-width="165" /><el-table-column label="结束时间" prop="endsAt" min-width="165" /><el-table-column label="启用" width="85"><template #default="s"><el-tag :type="s.row.enabled ? 'success' : 'info'">{{ s.row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="80"><template #default="s"><el-button text type="primary" :disabled="acting" @click="edit(s.row)">编辑</el-button></template></el-table-column></el-table>
    <el-dialog v-model="visible" :title="editingId ? '编辑优惠' : '新增优惠'" width="min(520px, 95vw)" :close-on-click-modal="!acting" :close-on-press-escape="!acting" :show-close="!acting"><el-form label-position="top" @submit.prevent="savePromotion"><el-form-item label="优惠名称"><el-input v-model="form.title" maxlength="80" :disabled="acting" /></el-form-item><el-form-item label="优惠金额（元）"><el-input v-model="form.discountAmount" inputmode="decimal" :disabled="acting" /></el-form-item><el-form-item label="适用商品"><el-select v-model="form.productIds" multiple filterable clearable placeholder="留空适用于全店商品" :disabled="acting" style="width:100%"><el-option v-for="product in products" :key="product.productId" :value="Number(product.productId)" :label="`${product.productName || product.name} · ${product.spec || product.productCode || ''}`" /></el-select></el-form-item><el-form-item label="开始时间"><el-date-picker v-model="form.startsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :disabled="acting" /></el-form-item><el-form-item label="结束时间"><el-date-picker v-model="form.endsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :disabled="acting" /></el-form-item><el-form-item label="启用"><el-switch v-model="form.enabled" :disabled="acting" /></el-form-item></el-form><el-alert v-if="dialogError" :title="dialogError" type="error" :closable="false" /><template #footer><el-button :disabled="acting" @click="visible = false">返回</el-button><el-button type="primary" :loading="acting" @click="savePromotion">保存优惠</el-button></template></el-dialog>
  </section>
</template>
<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getPricingPolicy, savePricingPolicy, listPricingPromotions, savePricingPromotion } from '@/api/commerce/pricing'
const props = defineProps({ shopId: { type: String, required: true }, capabilities: { type: Array, default: () => [] }, products: { type: Array, default: () => [] } })
const canCatalog = computed(() => props.capabilities.includes('CATALOG'))
const loading = ref(false), acting = ref(false), error = ref(''), dialogError = ref(''), promotions = ref([]), shippingFee = ref('0.00'), visible = ref(false), editingId = ref('')
const form = reactive({ title: '', discountAmount: '', productIds: [], startsAt: '', endsAt: '', enabled: true })
const money = value => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(Number(value || 0))
const validAmount = (value, positive = false) => /^\d+(\.\d{1,2})?$/.test(value) && (!positive || Number(value) > 0)
let generation = 0
async function refresh() {
  if (!canCatalog.value) return
  const shop = props.shopId, current = ++generation; loading.value = true; error.value = ''
  try { const [policy, activities] = await Promise.all([getPricingPolicy(shop), listPricingPromotions(shop)]); if (current !== generation || shop !== props.shopId) return; shippingFee.value = Number(policy.data.shippingFee).toFixed(2); promotions.value = activities.data || [] }
  catch (failure) { if (current === generation) error.value = failure?.message || '优惠与运费读取失败' }
  finally { if (current === generation) loading.value = false }
}
function edit(row) { if (acting.value || !canCatalog.value) return; editingId.value = row?.promotionId || ''; Object.assign(form, { title: row?.title || '', discountAmount: row ? String(row.discountAmount) : '', productIds: [...(row?.productIds || [])].map(Number), startsAt: String(row?.startsAt || '').replace(' ', 'T'), endsAt: String(row?.endsAt || '').replace(' ', 'T'), enabled: row ? !!row.enabled : true }); dialogError.value = ''; visible.value = true }
async function perform(callback, isDialog) {
  if (acting.value || !canCatalog.value) return
  const shop = props.shopId, current = generation; acting.value = true; error.value = ''; dialogError.value = ''
  try { await callback(shop); if (current !== generation || shop !== props.shopId) return; if (isDialog) visible.value = false; ElMessage.success(isDialog ? '优惠已保存' : '运费已保存'); await refresh() }
  catch (failure) { if (current === generation && shop === props.shopId) { if (isDialog) dialogError.value = failure?.message || '优惠保存失败'; else error.value = failure?.message || '运费保存失败' } }
  finally { acting.value = false }
}
function saveShipping() { if (!validAmount(shippingFee.value.trim())) { error.value = '填写非负金额，最多两位小数'; return } const body = { shippingFee: shippingFee.value.trim() }; void perform(shop => savePricingPolicy(shop, body), false) }
function savePromotion() { const body = { ...form, title: form.title.trim(), discountAmount: form.discountAmount.trim(), productIds: [...form.productIds] }; if (!body.title || !validAmount(body.discountAmount, true) || !body.startsAt || !body.endsAt || body.startsAt >= body.endsAt) { dialogError.value = '填写名称、正数金额及有效起止时间'; return } const id = editingId.value; void perform(shop => savePricingPromotion(shop, id, body), true) }
watch(() => [props.shopId, canCatalog.value], () => { generation++; promotions.value = []; shippingFee.value = '0.00'; visible.value = false; error.value = ''; void refresh() }, { immediate: true })
defineExpose({ refresh })
</script>
<style scoped>
.pricing-panel{padding:22px;margin-top:22px;background:var(--sc-surface);border:1px solid var(--sc-border);border-radius:10px}.pricing-panel header,.promotion-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.pricing-panel h2{font-size:18px;margin:0}.pricing-panel h3{font-size:15px;margin:0}.shipping-policy{margin-top:24px}.promotion-heading{margin:12px 0 16px}@media(max-width:767px){.pricing-panel{padding:14px}.shipping-policy .el-button{margin-bottom:18px}}
</style>
