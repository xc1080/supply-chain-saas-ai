<template>
  <span class="source-receipts">
    <template v-for="(id, index) in receiptIds" :key="id">
      <span v-if="index" aria-hidden="true">，</span>
      <router-link v-if="!needsVerification && destination(id, category)" class="source-receipt-link" :to="destination(id, category)" :aria-label="'查看来源单据 ' + id">{{ id }}</router-link>
      <el-button v-else-if="canVerify(id)" link type="primary" :loading="loadingId === id" :disabled="!!loadingId" :aria-label="'核对并查看来源单据 ' + id" @click="verifyAndOpen(id)">{{ id }}</el-button>
      <span v-else>{{ id }}</span>
    </template>
    <span v-if="!receiptIds.length">—</span>
    <span v-if="message" class="source-receipt-message" role="status">{{ message }}</span>
  </span>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import auth from '@/plugins/auth'
import { getPurchaseReceipt } from '@/api/purchase/PurchaseDocumentQuery'
import { getSalesReceipt } from '@/api/sales/SalesDocumentQuery'
import { getReceipt } from '@/api/inventory/inventoryDocumentQuery'
import { getPurchaseOrder } from '@/api/purchase/purchaseDocumentProcessing'
import { getSalesOrder } from '@/api/sales/salesDocumentProcessing'
import { getReceipt as getInventoryReceipt } from '@/api/inventory/inventoryDocumentProcessing'
import { getPurchaseOrder as getPurchaseOrderQuery } from '@/api/purchase/purchaseOrderQuery'
import { getPurchaseOrder as getPurchaseOrderProcessing } from '@/api/purchase/purchaseOrderProcessing'
import { getSalesOrder as getSalesOrderQuery } from '@/api/sales/SalesOrderQuery'
import { getSalesOrder as getSalesOrderProcessing } from '@/api/sales/salesOrderProcessing'
import { sourceReceiptRoute, sourceOrderRoute, splitReceiptIds, verifiedReceiptCategory, verifiedOrderType } from '@/utils/documentNavigation.mjs'

const props = defineProps({
  receipt: { type: [String, Number], default: '' },
  category: { type: [String, Number], default: '' },
  verify: { type: Boolean, default: false },
  kind: { type: String, default: 'receipt', validator: value => ['receipt', 'order'].includes(value) }
})
const router = useRouter()
const receiptIds = computed(() => splitReceiptIds(props.receipt))
const categories = computed(() => props.kind === 'order' ? [1, 2] : [1, 2, 3])
const needsVerification = computed(() => props.verify || !categories.value.includes(Number(props.category)))
const loadingId = ref('')
const message = ref('')
let requestVersion = 0
const receiptLookups = [
  { category: 3, permission: 'inventory:inventoryReceiptQuery:systematicReceipt', fetch: getReceipt },
  { category: 3, permission: 'inventory:inventoryReceiptProcessing:systematicReceipt', fetch: getInventoryReceipt },
  { category: 2, permission: 'sales:salesReceiptQuery:systematicReceipt', fetch: getSalesReceipt },
  { category: 2, permission: 'sales:salesReceiptProcessing:systematicReceipt', fetch: getSalesOrder },
  { category: 1, permission: 'purchase:purchaseReceiptQuery:systematicReceipt', fetch: getPurchaseReceipt },
  { category: 1, permission: 'purchase:purchaseReceiptProcessing:systematicReceipt', fetch: getPurchaseOrder }
]
const orderLookups = [
  { category: 1, permission: 'purchase:purchaseOrderQuery:systematicOrderForm', fetch: getPurchaseOrderQuery },
  { category: 1, permission: 'purchase:purchaseOrderProcessing:systematicOrderForm', fetch: getPurchaseOrderProcessing },
  { category: 2, permission: 'sales:salesOrderQuery:systematicOrderForm', fetch: getSalesOrderQuery },
  { category: 2, permission: 'sales:salesOrderProcessing:systematicOrderForm', fetch: getSalesOrderProcessing }
]
const expectedCategory = computed(() => categories.value.includes(Number(props.category)) ? Number(props.category) : null)
const lookups = computed(() => (props.kind === 'order' ? orderLookups : receiptLookups)
  .filter(lookup => !expectedCategory.value || lookup.category === expectedCategory.value))
function destination(id, category) {
  const resolveRoute = props.kind === 'order' ? sourceOrderRoute : sourceReceiptRoute
  return resolveRoute(id, category, permission => auth.hasPermi(permission), path => router.getRoutes().some(route => route.path === path))
}
function canVerify(id) {
  return needsVerification.value && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,199}$/.test(id)
    && lookups.value.some(lookup => auth.hasPermi(lookup.permission))
    && (expectedCategory.value ? !!destination(id, expectedCategory.value) : categories.value.some(category => destination(id, category)))
}
// Unknown sources and free-text references are checked only on click. No row-level
// mounted requests or cross-tenant result cache are created.
async function verifyAndOpen(id) {
  if (loadingId.value || !canVerify(id)) return
  const version = ++requestVersion
  const requestedCategory = expectedCategory.value
  loadingId.value = id
  message.value = ''
  try {
    const lookup = lookups.value.find(candidate => auth.hasPermi(candidate.permission))
    const response = await lookup.fetch(id)
    if (version !== requestVersion) return
    const category = props.kind === 'order' ? verifiedOrderType(id, response.data) : verifiedReceiptCategory(id, response.data)
    const target = category && (!requestedCategory || requestedCategory === category) ? destination(id, category) : null
    if (target) await router.push(target)
    else message.value = '未找到可查看的来源单据'
  } catch {
    if (version === requestVersion) message.value = '来源单据未能读取，请重试'
  } finally {
    if (version === requestVersion) loadingId.value = ''
  }
}
watch(() => [props.receipt, props.category, props.verify, props.kind], () => { requestVersion++; loadingId.value = ''; message.value = '' })
onBeforeUnmount(() => { requestVersion++ })
</script>

<style scoped>
.source-receipts { overflow-wrap: anywhere; }
.source-receipt-link { color: var(--sc-primary, #2563eb); }
.source-receipt-link:hover { text-decoration: underline; text-underline-offset: 3px; }
.source-receipt-link:focus-visible { outline: 2px solid var(--sc-primary, #2563eb); outline-offset: 3px; }
.source-receipt-message { display: block; color: var(--sc-muted, #69778a); font-size: 12px; line-height: 1.5; }
</style>
