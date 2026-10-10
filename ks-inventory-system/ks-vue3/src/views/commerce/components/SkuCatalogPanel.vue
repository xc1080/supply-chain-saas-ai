<template>
  <section class="sku-catalog" aria-labelledby="sku-catalog-title">
    <header class="sku-heading"><div><h2 id="sku-catalog-title">商品规格</h2><span>{{ groups.length }} 个商品组 · {{ ungrouped.length }} 个未分组货品</span></div><el-button v-if="canWrite" type="primary" :disabled="loading || saving" @click="openCreate">创建商品组</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="groups" row-key="spuId" empty-text="暂无商品组" class="sku-groups">
      <el-table-column type="expand"><template #default="scope">
        <el-table :data="groupProducts(scope.row)" row-key="productId" class="sku-variants" empty-text="暂无已绑定规格">
          <el-table-column label="货品" min-width="250"><template #default="variant"><ProductIdentity :product="variant.row"><strong>{{ variant.row.productName }}</strong><small>{{ variant.row.productCode }}</small></ProductIdentity></template></el-table-column>
          <el-table-column v-for="property in scope.row.properties" :key="property.propertyId" :label="property.propertyName" min-width="100"><template #default="variant">{{ valueLabel(property, variant.row.attributes?.[property.propertyId]) }}</template></el-table-column>
          <el-table-column label="售价" align="right" width="110"><template #default="variant">{{ money(variant.row.price) }}</template></el-table-column>
          <el-table-column label="可售" align="right" width="85"><template #default="variant">{{ variant.row.availableStock ?? '—' }}</template></el-table-column>
          <el-table-column label="商城" width="90"><template #default="variant"><el-tag size="small" :type="variant.row.listed ? 'success' : 'info'">{{ variant.row.listed ? '已上架' : '未上架' }}</el-tag></template></el-table-column>
        </el-table>
      </template></el-table-column>
      <el-table-column label="商品组" min-width="260"><template #default="scope"><ProductIdentity :product="groupProducts(scope.row)[0] || {}"><strong>{{ scope.row.name }}</strong></ProductIdentity></template></el-table-column>
      <el-table-column label="规格维度" min-width="240"><template #default="scope"><el-tag v-for="property in scope.row.properties" :key="property.propertyId" size="small" class="dimension-tag">{{ property.propertyName }} {{ property.propertyValues.length }}</el-tag></template></el-table-column>
      <el-table-column label="已绑定规格" align="right" width="120"><template #default="scope">{{ scope.row.skus?.length || 0 }}</template></el-table-column>
      <el-table-column v-if="canWrite" label="操作" width="115"><template #default="scope"><el-button type="primary" link :disabled="loading || saving || !ungrouped.length" @click="openAppend(scope.row)">添加规格</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="target ? `添加规格 · ${target.name}` : '创建商品组'" width="min(1080px, 96vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item v-if="!target" label="商品组名称" required><el-input v-model="name" maxlength="100" :disabled="saving" placeholder="例如：卧室智能灯" /></el-form-item>
        <div class="form-section-title"><h3>规格维度</h3><el-button v-if="!target" text type="primary" :disabled="saving || properties.length >= 6" @click="addProperty">添加维度</el-button></div>
        <div v-for="(property, index) in properties" :key="property.propertyId" class="property-editor">
          <div class="property-heading"><el-input v-model="property.propertyName" :disabled="Boolean(target) || saving" placeholder="属性名称，如颜色" :aria-label="`第 ${index + 1} 个属性名称`" maxlength="40" /><el-button v-if="!target" link type="danger" :disabled="saving" @click="removeProperty(index)">删除维度</el-button></div>
          <div class="property-values"><div v-for="(value, valueIndex) in property.propertyValues" :key="value.propertyValueId" class="property-value"><template v-if="target"><el-tag>{{ value.propertyValue }}</el-tag></template><template v-else><el-input v-model="value.propertyValue" :disabled="saving" placeholder="属性值，如白色" maxlength="40" :aria-label="`第 ${index + 1} 个属性的第 ${valueIndex + 1} 个值名称`" /><el-button text type="danger" :disabled="saving" :aria-label="`删除第 ${valueIndex + 1} 个属性值`" @click="removeValue(property, valueIndex)">删除</el-button></template></div><el-button v-if="!target" text type="primary" :disabled="saving || property.propertyValues.length >= 30" @click="addValue(property)">添加属性值</el-button></div>
        </div>
        <div class="form-section-title"><h3>绑定货品</h3><el-select v-model="selectedIds" multiple :multiple-limit="100" filterable collapse-tags collapse-tags-tooltip :disabled="saving" placeholder="选择当前店铺的未分组货品" aria-label="选择待绑定规格货品"><el-option v-for="product in ungrouped" :key="product.productId" :value="String(product.productId)" :label="`${product.productName} · ${product.productCode || product.productId}`" /></el-select></div>
        <el-table :data="selectedProducts" row-key="productId" empty-text="先选择货品，再为每项选择属性组合">
          <el-table-column label="货品" min-width="235"><template #default="scope"><ProductIdentity :product="scope.row"><strong>{{ scope.row.productName }}</strong><small>{{ scope.row.productCode }}</small><small>{{ scope.row.spec }}</small></ProductIdentity></template></el-table-column>
          <el-table-column v-for="(property, index) in properties" :key="index" :label="property.propertyName || `属性 ${index + 1}`" min-width="145"><template #default="scope"><el-select v-model="assignments[String(scope.row.productId)][index]" :disabled="saving" placeholder="选择属性值" :aria-label="`${scope.row.productName} ${property.propertyName || '属性'}`"><el-option v-for="(value, valueIndex) in property.propertyValues" :key="valueIndex" :label="value.propertyValue || `属性值 ${valueIndex + 1}`" :value="valueIndex" /></el-select></template></el-table-column>
          <el-table-column label="售价 / 可售" align="right" width="125"><template #default="scope">{{ money(scope.row.price) }}<small>{{ scope.row.availableStock ?? '—' }} 件</small></template></el-table-column>
        </el-table>
      </el-form>
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon class="form-error" />
      <template #footer><el-button :disabled="saving" @click="dialogVisible = false">返回</el-button><el-button type="primary" :loading="saving" :disabled="!canWrite || !selectedIds.length" @click="save">{{ target ? '绑定规格' : '创建商品组' }}</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ProductIdentity from '@/components/ProductIdentity/index.vue'
import { listCommerceSpus, createCommerceSpu, appendCommerceSkus, listCommerceInventory } from '@/api/commerce/orders'

const props = defineProps({ shopId: { type: String, required: true }, capabilities: { type: Array, default: () => [] } })
const emit = defineEmits(['updated'])
const canWrite = computed(() => props.capabilities.includes('CATALOG'))
const canRead = computed(() => props.capabilities.includes('READ'))
const groups = ref([]), products = ref([]), loading = ref(false), saving = ref(false), error = ref('')
const dialogVisible = ref(false), target = ref(null), name = ref(''), properties = ref([]), selectedIds = ref([]), formError = ref('')
const assignments = reactive({})
let readVersion = 0, writeVersion = 0
const ungrouped = computed(() => {
  const assigned = new Set(groups.value.flatMap(group => (group.skus || []).map(sku => String(sku.productId))))
  return products.value.filter(product => !product.skuCatalog && !assigned.has(String(product.productId)))
})
const selectedProducts = computed(() => ungrouped.value.filter(product => selectedIds.value.includes(String(product.productId))))
const money = value => value != null && value !== '' && Number.isFinite(Number(value)) && Number(value) > 0 ? `¥${Number(value).toFixed(2)}` : '未定价'
const valueLabel = (property, id) => property.propertyValues?.find(value => value.propertyValueId === id)?.propertyValue || '—'
const groupProducts = group => (group.skus || []).map(sku => ({ ...products.value.find(product => String(product.productId) === String(sku.productId)), ...sku }))
// IDs are created once and survive label edits; persisted SKU identity never
// depends on a merchant's display name or input order.
function newIdentity(prefix) {
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(8))
  return `${prefix}_${Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')}`
}
const emptyValue = () => ({ propertyValueId: newIdentity('value'), propertyValue: '' })
const emptyProperty = () => ({ propertyId: newIdentity('attr'), propertyName: '', propertyValues: [emptyValue()] })
function clearAssignments() { Object.keys(assignments).forEach(key => delete assignments[key]) }
function resetDialog() { dialogVisible.value = false; target.value = null; name.value = ''; properties.value = []; selectedIds.value = []; formError.value = ''; clearAssignments() }
function openCreate() { resetDialog(); properties.value = [emptyProperty()]; dialogVisible.value = true }
function openAppend(group) { resetDialog(); target.value = group; properties.value = JSON.parse(JSON.stringify(group.properties || [])); dialogVisible.value = true }
function addProperty() { properties.value.push(emptyProperty()); clearAssignments(); selectedIds.value.forEach(id => { assignments[id] = [] }) }
function removeProperty(index) { properties.value.splice(index, 1); clearAssignments(); selectedIds.value.forEach(id => { assignments[id] = [] }) }
function addValue(property) { property.propertyValues.push(emptyValue()) }
function removeValue(property, index) { property.propertyValues.splice(index, 1); clearAssignments(); selectedIds.value.forEach(id => { assignments[id] = [] }) }
watch(selectedIds, ids => { ids.forEach(id => { if (!assignments[id]) assignments[id] = [] }) }, { deep: true })
async function refresh() {
  const version = ++readVersion, shop = props.shopId
  loading.value = true; error.value = ''
  if (!canRead.value) { groups.value = []; products.value = []; loading.value = false; return }
  try {
    const [groupResponse, inventoryResponse] = await Promise.all([listCommerceSpus(), listCommerceInventory()])
    if (version !== readVersion || shop !== props.shopId) return
    groups.value = groupResponse.data || []; products.value = inventoryResponse.data || []
  } catch (failure) { if (version === readVersion && shop === props.shopId) error.value = failure?.message || '商品规格读取失败，请刷新重试' }
  finally { if (version === readVersion) loading.value = false }
}
function payload() {
  const codePattern = /^[a-z0-9_]{1,32}$/
  if (!target.value && (!name.value.trim() || name.value.trim().length > 100)) throw new Error('商品组名称需为 1–100 个字符')
  if (!properties.value.length || properties.value.length > 6) throw new Error('规格维度需为 1–6 个')
  const propertyIds = new Set(), propertyNames = new Set()
  const definitions = properties.value.map(property => {
    const id = property.propertyId.trim(), label = property.propertyName.trim()
    if (!label || label.length > 40) throw new Error('属性名称需为 1–40 个字符')
    if (propertyNames.has(label)) throw new Error(`属性名称“${label}”重复`)
    propertyNames.add(label)
    if (!codePattern.test(id)) throw new Error('规格数据异常，请关闭后重新创建')
    if (propertyIds.has(id)) throw new Error('规格数据重复，请关闭后重新创建')
    propertyIds.add(id)
    const valueIds = new Set(), valueNames = new Set()
    if (!property.propertyValues.length || property.propertyValues.length > 30) throw new Error(`${label}的属性值需为 1–30 个`)
    const values = property.propertyValues.map(value => {
      const valueId = value.propertyValueId.trim(), valueLabel = value.propertyValue.trim()
      if (!valueLabel || valueLabel.length > 40) throw new Error(`${label}的属性值名称需为 1–40 个字符`)
      if (valueNames.has(valueLabel)) throw new Error(`${label}的属性值“${valueLabel}”重复`)
      valueNames.add(valueLabel)
      if (!codePattern.test(valueId)) throw new Error('规格数据异常，请关闭后重新创建')
      if (valueIds.has(valueId)) throw new Error('规格数据重复，请关闭后重新创建')
      valueIds.add(valueId)
      return { propertyValueId: valueId, propertyValue: valueLabel }
    })
    return { propertyId: id, propertyName: label, propertyValues: values }
  })
  const signatures = new Set((target.value?.skus || []).map(sku => sku.propertyValueIds))
  if (selectedProducts.value.length > 100) throw new Error('每次最多绑定 100 个货品')
  if (!selectedProducts.value.length || selectedProducts.value.length !== selectedIds.value.length) throw new Error('选择当前店铺的未分组货品')
  const skus = selectedProducts.value.map(product => {
    const indices = assignments[String(product.productId)] || []
    const attributes = Object.fromEntries(definitions.map((property, index) => {
      const value = property.propertyValues[indices[index]]
      if (!value) throw new Error(`为 ${product.productName} 选择 ${property.propertyName}`)
      return [property.propertyId, value.propertyValueId]
    }))
    const signature = definitions.map(property => attributes[property.propertyId]).join('-')
    if (signatures.has(signature)) throw new Error('每个属性组合只能绑定一个货品')
    signatures.add(signature)
    return { productId: product.productId, attributes }
  })
  return { name: name.value.trim(), properties: definitions, skus }
}
async function save() {
  if (!canWrite.value || saving.value) return
  let data
  try { data = payload() } catch (failure) { formError.value = failure.message; return }
  const shop = props.shopId, version = ++writeVersion, groupId = target.value?.spuId
  saving.value = true; formError.value = ''
  try {
    if (groupId) await appendCommerceSkus(groupId, { skus: data.skus })
    else await createCommerceSpu(data)
    if (version !== writeVersion || shop !== props.shopId) return
    resetDialog(); ElMessage.success(groupId ? '已绑定规格' : '已创建商品组'); await refresh(); emit('updated')
  } catch (failure) { if (version === writeVersion && shop === props.shopId) formError.value = failure?.message || '保存失败，请重试' }
  finally { if (version === writeVersion) saving.value = false }
}
watch(() => [props.shopId, canRead.value], () => { ++readVersion; ++writeVersion; saving.value = false; groups.value = []; products.value = []; resetDialog(); refresh() }, { immediate: true })
onBeforeUnmount(() => { ++readVersion; ++writeVersion })
defineExpose({ refresh })
</script>

<style scoped>
.sku-catalog { padding: 22px; margin-bottom: 22px; background: var(--sc-surface); border: 1px solid var(--sc-border); border-radius: 10px; }
.sku-heading, .form-section-title, .property-heading { display: flex; justify-content: space-between; align-items: center; gap: 14px; }
.sku-heading { margin-bottom: 20px; }.sku-heading h2 { margin: 0 0 6px; font-size: 18px; font-weight: 600; }.sku-heading span, small { color: var(--sc-muted); font-size: 12px; }
small { display: block; line-height: 1.6; }.sku-groups { margin-top: 16px; }.sku-variants { padding: 8px 18px; }.dimension-tag { margin: 3px 6px 3px 0; }
.form-section-title { margin: 24px 0 14px; }.form-section-title h3 { margin: 0; font-size: 15px; }.form-section-title :deep(.el-select) { width: min(450px, 70%); }
.property-editor { padding: 14px; border-left: 3px solid var(--sc-border); margin-bottom: 12px; background: var(--sc-surface-soft); }.property-heading :deep(.el-input) { max-width: 280px; }
.property-values { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 12px; }.property-value { display: flex; align-items: center; gap: 6px; }.property-value :deep(.el-input) { width: 160px; }.form-error { margin-top: 16px; }
@media(max-width:700px) { .sku-catalog { padding: 14px; }.sku-heading, .property-heading, .form-section-title { align-items: flex-start; flex-wrap: wrap; }.form-section-title :deep(.el-select) { width: 100%; }.property-value { flex-wrap: wrap; }.property-value :deep(.el-input) { width: 135px; } }
</style>
