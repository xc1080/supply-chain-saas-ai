<template>
  <div class="workspace" aria-labelledby="workspace-title">
    <a class="workspace-skip" href="#workspace-stock">跳至库存关注</a>
    <header class="workspace-heading">
      <div>
        <div class="workspace-date">{{ today }}</div>
        <h1 id="workspace-title">供应链工作台</h1>
        <p>掌握库存，处理待办，让每一次采购和交付都有依据。</p>
      </div>
      <div class="workspace-actions">
        <router-link v-if="canManageCommerce" class="workspace-button workspace-button-quiet" to="/commerce/orders">商城订单与库存</router-link>
        <a class="workspace-button workspace-button-quiet" href="http://127.0.0.1:6001/" target="_blank" rel="noopener">顾客商城</a>
        <button class="workspace-button workspace-button-quiet" type="button" :disabled="refreshing" @click="refreshDashboard">
          <Refresh aria-hidden="true" />{{ refreshing ? '刷新中…' : '刷新数据' }}
        </button>
        <router-link class="workspace-button workspace-button-primary" to="/ai/assistant">
          <ChatDotRound aria-hidden="true" />智能选品助手
        </router-link>
      </div>
    </header>

    <section class="workspace-summary" aria-label="业务概况" aria-live="polite" :aria-busy="refreshing">
      <div v-for="metric in metrics" :key="metric.label" class="workspace-metric">
        <span class="workspace-metric-label">{{ metric.label }}</span>
        <strong :class="{ 'workspace-number-alert': metric.alert }">{{ metric.value }}</strong>
        <span class="workspace-metric-note">{{ metric.note }}</span>
      </div>
    </section>

    <div class="workspace-data-status" aria-live="polite">
      <span v-if="refreshing">正在读取业务数据…</span>
      <span v-else-if="lastUpdated">最近读取 {{ formatTime(lastUpdated) }}</span>
      <span v-else>数据尚未读取</span>
      <span v-if="products.error || taskError || finance.error" class="workspace-status-error">部分数据未能读取，可点击刷新重试</span>
    </div>

    <div class="workspace-grid">
      <section class="workspace-panel workspace-stock" aria-labelledby="workspace-stock" :aria-busy="stockLoading">
        <div class="workspace-panel-heading">
          <div>
            <h2 id="workspace-stock" tabindex="-1">库存关注</h2>
            <p>{{ stockDescription }}</p>
          </div>
          <div class="workspace-select-wrap">
            <label class="workspace-sr-only" for="workspace-stock-filter">库存关注范围</label>
            <select id="workspace-stock-filter" :value="stockFilter" @change="setQuery('stock', $event.target.value, 'all')">
              <option v-for="option in stockOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </div>
        </div>

        <div v-if="!canReadProducts" class="workspace-empty">当前账户没有查看货品的权限，请联系管理员分配权限。</div>
        <div v-else-if="stockLoading" class="workspace-empty" role="status">正在读取库存…</div>
        <div v-else-if="stockError" class="workspace-empty workspace-error" role="status">
          <strong>库存数据未能读取</strong>
          <p>检查服务连接后重试，避免依据不完整的库存做决定。</p>
          <button class="workspace-button workspace-button-quiet" type="button" @click="retryStock">重新读取库存</button>
        </div>
        <div v-else-if="!stockRows.length" class="workspace-empty">
          <strong>{{ stockFilter === 'all' ? '还没有货品资料' : '当前范围没有库存预警' }}</strong>
          <p>{{ stockFilter === 'all' ? '先建立货品资料，再通过业务单据管理入库和出库。' : '可以切换到全部货品，查看当前账面库存。' }}</p>
          <router-link v-if="stockFilter === 'all'" class="workspace-text-link" to="/basedate/product">管理货品资料</router-link>
        </div>
        <div v-else class="workspace-table-wrap">
          <table class="workspace-stock-table">
            <caption class="workspace-sr-only">{{ stockDescription }}，展示 {{ formatNumber(stockRows.length) }} 项</caption>
            <thead><tr><th scope="col">货品</th><th scope="col">账面库存</th><th scope="col">状态</th></tr></thead>
            <tbody>
              <tr v-for="product in stockRows" :key="product.productId || product.productCode">
                <td>
                  <div class="workspace-product-identity">
                  <img :src="getProductImage(product)" :alt="`${product.productName || '货品'}缩略图`" loading="lazy" @error="handleProductImageError($event, product)" />
                  <div>
                  <span class="workspace-product-name">{{ product.productName || '未命名货品' }}</span>
                  <span class="workspace-product-meta">{{ product.productCode || '未填写编号' }}<span v-if="product.productSpecifications"> / {{ product.productSpecifications }}</span></span>
                  </div>
                  </div>
                </td>
                <td class="workspace-stock-quantity"><strong>{{ formatNumber(product.inventoryQty) }}</strong><span v-if="product.measureUnit">{{ product.measureUnit }}</span></td>
                <td><span class="workspace-stock-state" :class="'workspace-stock-state-' + productState(product).tone"><span aria-hidden="true" class="workspace-state-dot"></span>{{ productState(product).label }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="canReadProducts && !stockLoading && !stockError" class="workspace-panel-footer">
          <span>共 {{ formatNumber(stockTotal) }} 项{{ stockTotal > stockRows.length ? '，展示前 ' + formatNumber(stockRows.length) + ' 项' : '' }}</span>
          <router-link class="workspace-text-link" to="/basedate/product">查看货品资料<ArrowRight aria-hidden="true" /></router-link>
        </div>
      </section>

      <aside class="workspace-panel workspace-tasks" aria-labelledby="workspace-tasks-title">
        <div class="workspace-panel-heading">
          <div><h2 id="workspace-tasks-title">单据待办</h2><p>先审核，再进入下一步业务</p></div>
          <span class="workspace-task-total">{{ pendingLabel }}</span>
        </div>
        <div v-if="!visibleTasks.length" class="workspace-empty">当前账户没有查看单据的权限。</div>
        <template v-else>
          <div class="workspace-task-columns" aria-hidden="true"><span>业务单据</span><span>待审核</span><span>已审核</span></div>
          <router-link v-for="task in visibleTasks" :key="task.id" :to="task.to" class="workspace-task-row" :aria-label="task.label + '，待审核 ' + taskCount(task, 'pending') + '，已审核 ' + taskCount(task, 'approved')">
            <span class="workspace-task-name"><component :is="task.icon" aria-hidden="true" />{{ task.label }}</span>
            <strong :class="{ 'workspace-number-alert': task.pending > 0 }">{{ taskCount(task, 'pending') }}</strong>
            <span class="workspace-approved-count">{{ taskCount(task, 'approved') }}</span>
          </router-link>
          <div v-if="taskError" class="workspace-task-error" role="status">部分单据未能读取。<button type="button" @click="loadTasks" :disabled="tasksLoading">重新读取</button></div>
        </template>
        <div class="workspace-shortcuts">
          <h3>常用业务</h3>
          <nav aria-label="常用业务入口">
            <router-link v-for="link in visibleShortcuts" :key="link.to" :to="link.to"><component :is="link.icon" aria-hidden="true" /><span>{{ link.label }}</span><ArrowRight aria-hidden="true" /></router-link>
          </nav>
        </div>
      </aside>
    </div>

    <section v-if="canReadFinance" class="workspace-panel workspace-finance" aria-labelledby="workspace-finance-title" :aria-busy="finance.loading">
      <div class="workspace-finance-heading">
        <h2 id="workspace-finance-title">销售与毛利</h2>
        <label class="workspace-sr-only" for="workspace-finance-period">销售统计周期</label>
        <select id="workspace-finance-period" :value="financePeriod" @change="setQuery('period', $event.target.value, 'month')"><option v-for="period in financePeriods" :key="period.value" :value="period.value">{{ period.label }}</option></select>
        <p>按业务系统已审核单据口径统计</p>
      </div>
      <div v-if="finance.error" class="workspace-finance-error" role="status">销售数据未能读取。<button type="button" @click="loadFinance" :disabled="finance.loading">重新读取</button></div>
      <dl v-else class="workspace-finance-values"><div><dt>销售金额</dt><dd>{{ finance.loading ? '读取中…' : formatCurrency(finance.data?.[selectedPeriod.sales]) }}</dd></div><div><dt>毛利润</dt><dd>{{ finance.loading ? '读取中…' : formatCurrency(finance.data?.[selectedPeriod.profit]) }}</dd></div></dl>
    </section>
  </div>
</template>

<script setup name="Index">
import { computed, markRaw, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Box, ChatDotRound, Document, Refresh, ShoppingCart, Sort, Tickets } from '@element-plus/icons-vue'
import auth from '@/plugins/auth'
import { getProductImage, handleProductImageError } from '@/utils/productMedia'
import { listProduct, listDangerProduct, listOverstockProduct, listThreeOverstock, listSixOverstock, listTwelveOverstock } from '@/api/basedate/product'
import { purchaseOrderHeadQuery } from '@/api/purchase/purchaseOrderQuery'
import { headQuery as salesOrderHeadQuery } from '@/api/sales/SalesOrderQuery'
import { headQuery as purchaseReceiptHeadQuery } from '@/api/purchase/PurchaseDocumentQuery'
import { headQuery as salesReceiptHeadQuery } from '@/api/sales/SalesDocumentQuery'
import { headQuery as inventoryHeadQuery, salesAmountQuery } from '@/api/inventory/inventoryDocumentQuery'

const route = useRoute()
const router = useRouter()
const numberFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 })
const currencyFormatter = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 2 })
const dateFormatter = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })
const timeFormatter = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
const today = dateFormatter.format(new Date())
const refreshing = ref(false)
const lastUpdated = ref(null)
const products = reactive({ rows: [], total: null, loading: true, error: false })
const alerts = reactive({})
const finance = reactive({ data: null, loading: true, error: false })
const tasksLoading = ref(false)
const canReadProducts = computed(() => auth.hasPermi('baseDate:product:list'))
const canManageCommerce = computed(() => auth.hasRole('admin'))
const canReadFinance = computed(() => auth.hasPermi('inventory:inventoryReceiptQuery:salesAmountQuery'))
const stockOptions = [
  { value: 'all', label: '全部货品' }, { value: 'out', label: '缺货' }, { value: 'low', label: '低于库存下限', fetch: listDangerProduct },
  { value: 'high', label: '高于库存上限', fetch: listOverstockProduct }, { value: 'three', label: '3 个月未出库', fetch: listThreeOverstock },
  { value: 'six', label: '6 个月未出库', fetch: listSixOverstock }, { value: 'twelve', label: '12 个月未出库', fetch: listTwelveOverstock }
]
const financePeriods = [
  { value: 'day', label: '今天', sales: 'dailySalesAmount', profit: 'dailyGrossProfit' },
  { value: 'month', label: '近 1 个月', sales: 'lastMonthSalesAmount', profit: 'lastMonthGrossProfit' },
  { value: 'half', label: '近半年', sales: 'lastSixMonthsSalesAmount', profit: 'lastSixMonthsGrossProfit' },
  { value: 'year', label: '今年', sales: 'thisYearSalesAmount', profit: 'thisYearGrossProfit' },
  { value: 'lastyear', label: '上一年', sales: 'lastYearSalesAmount', profit: 'lastYearGrossProfit' },
  { value: 'twoyears', label: '上上一年', sales: 'lastTwoYearsSalesAmount', profit: 'lastTwoYearsGrossProfit' }
]
const stockFilter = computed(() => stockOptions.some(option => option.value === route.query.stock) ? route.query.stock : 'all')
const financePeriod = computed(() => financePeriods.some(period => period.value === route.query.period) ? route.query.period : 'month')
const selectedPeriod = computed(() => financePeriods.find(period => period.value === financePeriod.value))
const taskDefinitions = [
  { id: 'purchaseOrder', label: '采购订单', icon: ShoppingCart, to: '/purchase/purchaseOrderQuery', permission: 'purchase:purchaseOrderQuery:headQuery', fetch: purchaseOrderHeadQuery, params: { orderFormType: 1 }, statusKey: 'orderFormStatus' },
  { id: 'purchaseReceipt', label: '采购单据', icon: Document, to: '/purchase/purchaseDocumentQuery', permission: 'purchase:purchaseReceiptQuery:headQuery', fetch: purchaseReceiptHeadQuery, params: { receiptCategory: 1 }, statusKey: 'receiptStatus' },
  { id: 'salesOrder', label: '销售订单', icon: Tickets, to: '/sales/salesOrderQuery', permission: 'sales:salesOrderQuery:headQuery', fetch: salesOrderHeadQuery, params: { orderFormType: 2 }, statusKey: 'orderFormStatus' },
  { id: 'salesReceipt', label: '销售单据', icon: Document, to: '/sales/salesDocumentQuery', permission: 'sales:salesReceiptQuery:headQuery', fetch: salesReceiptHeadQuery, params: { receiptCategory: 2 }, statusKey: 'receiptStatus' },
  { id: 'inventoryReceipt', label: '库存单据', icon: Box, to: '/inventory/inventoryDocumentQuery', permission: 'inventory:inventoryReceiptQuery:headQuery', fetch: inventoryHeadQuery, params: { receiptCategory: 3 }, statusKey: 'receiptStatus' },
  { id: 'transfer', label: '其中：调拨单', icon: Sort, to: '/inventory/inventoryDocumentQuery', permission: 'inventory:inventoryReceiptQuery:headQuery', fetch: inventoryHeadQuery, params: { receiptCategory: 3, receiptType: 7 }, statusKey: 'receiptStatus', includeInTotal: false }
]
const tasks = reactive(taskDefinitions.map(task => ({ ...task, icon: markRaw(task.icon), pending: null, approved: null, loading: false, error: false })))
const visibleTasks = computed(() => tasks.filter(task => auth.hasPermi(task.permission)))
const taskError = computed(() => visibleTasks.value.some(task => task.error))
const shortcuts = [
  { label: '货品资料', to: '/basedate/product', permission: 'baseDate:product:list', icon: Box },
  { label: '库存查询', to: '/inventory/inventoryItemInquiry', permission: 'inventory:inventoryItemInquiry:list', icon: Document },
  { label: '采购订单', to: '/purchase/purchaseOrderProcessing', permission: 'purchase:purchaseOrderProcessing:add', icon: ShoppingCart },
  { label: '销售订单', to: '/sales/salesOrderProcessing', permission: 'sales:salesOrderProcessing:add', icon: Tickets }
]
const visibleShortcuts = computed(() => shortcuts.filter(link => auth.hasPermi(link.permission)))
const inventoryTotal = computed(() => {
  if (products.error || products.loading || !canReadProducts.value) return null
  const quantities = products.rows.map(product => numericValue(product.inventoryQty))
  return quantities.some(value => value === null) ? null : quantities.reduce((sum, value) => sum + value, 0)
})
const outOfStockCount = computed(() => {
  if (products.error || products.loading || !canReadProducts.value) return null
  return products.rows.some(product => numericValue(product.inventoryQty) === null) ? null : products.rows.filter(product => Number(product.inventoryQty) <= 0).length
})
const totalTasks = computed(() => visibleTasks.value.filter(task => task.includeInTotal !== false))
const pendingTotal = computed(() => totalTasks.value.length && totalTasks.value.every(task => !task.loading && task.pending !== null) ? totalTasks.value.reduce((sum, task) => sum + task.pending, 0) : null)
const pendingLabel = computed(() => tasksLoading.value ? '读取中…' : pendingTotal.value === null ? '待办未知' : formatNumber(pendingTotal.value) + ' 项待审核')
const metrics = computed(() => [
  { label: '货品总数', value: productMetric(products.total), note: '已建立的货品资料' },
  { label: '账面库存', value: productMetric(inventoryTotal.value), note: '各货品库存数量合计' },
  { label: '缺货货品', value: productMetric(outOfStockCount.value), note: '账面库存小于或等于 0', alert: outOfStockCount.value > 0 },
  { label: '待审核单据', value: !visibleTasks.value.length ? '无权限' : tasksLoading.value ? '读取中…' : formatNumber(pendingTotal.value), note: '当前账户可查询的单据', alert: pendingTotal.value > 0 }
])
const selectedAlert = computed(() => alerts[stockFilter.value])
const stockLoading = computed(() => canReadProducts.value && (['all', 'out'].includes(stockFilter.value) ? products.loading : !selectedAlert.value || selectedAlert.value.loading))
const stockError = computed(() => ['all', 'out'].includes(stockFilter.value) ? products.error : selectedAlert.value?.error)
const sortedProducts = computed(() => [...products.rows].sort((a, b) => stockPriority(a) - stockPriority(b) || String(a.productCode || '').localeCompare(String(b.productCode || ''), 'zh-CN')))
const filteredProducts = computed(() => stockFilter.value === 'out' ? sortedProducts.value.filter(product => numericValue(product.inventoryQty) !== null && Number(product.inventoryQty) <= 0) : sortedProducts.value)
const stockRows = computed(() => (['all', 'out'].includes(stockFilter.value) ? filteredProducts.value : selectedAlert.value?.rows || []).slice(0, 8))
const stockTotal = computed(() => ['all', 'out'].includes(stockFilter.value) ? filteredProducts.value.length : selectedAlert.value?.total ?? null)
const stockDescription = computed(() => ({ all: '缺货与低库存优先显示', out: '账面库存小于或等于 0 的货品', low: '账面库存低于设定下限的货品', high: '账面库存高于设定上限的货品', three: '业务系统中 3 个月未出库的货品', six: '业务系统中 6 个月未出库的货品', twelve: '业务系统中 12 个月未出库的货品' })[stockFilter.value])

function numericValue(value) {
  if (value === null || value === undefined || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}
function formatNumber(value) { const number = numericValue(value); return number === null ? '未知' : numberFormatter.format(number) }
function formatCurrency(value) { const number = numericValue(value); return number === null ? '暂无统计' : currencyFormatter.format(number) }
function formatTime(value) { return timeFormatter.format(value) }
function productMetric(value) { return !canReadProducts.value ? '无权限' : products.loading ? '读取中…' : products.error ? '未知' : formatNumber(value) }
function taskCount(task, key) { return task.loading ? '…' : formatNumber(task[key]) }
function productState(product) {
  const quantity = numericValue(product.inventoryQty)
  if (quantity === null) return { label: '待核对', tone: 'muted' }
  if (quantity <= 0) return { label: '缺货', tone: 'warning' }
  if (numericValue(product.lowerLimit) !== null && quantity < Number(product.lowerLimit)) return { label: '低库存', tone: 'warning' }
  if (numericValue(product.upperLimit) !== null && quantity > Number(product.upperLimit)) return { label: '库存积压', tone: 'muted' }
  return { label: '正常', tone: 'success' }
}
function stockPriority(product) { const state = productState(product); return state.label === '缺货' ? 0 : state.label === '低库存' ? 1 : state.label === '库存积压' ? 2 : 3 }
function setQuery(key, value, defaultValue) {
  const query = { ...route.query }
  if (value === defaultValue) delete query[key]
  else query[key] = value
  router.replace({ query })
}
async function loadProducts() {
  if (!canReadProducts.value) { products.loading = false; return }
  if (products.loading && products.started) return
  products.started = true
  products.loading = true
  products.error = false
  products.total = null
  try {
    const pageSize = 100
    const firstPage = await listProduct({ pageNum: 1, pageSize, orderByColumn: 'productId', isAsc: 'asc' })
    const total = numericValue(firstPage.total)
    if (total === null || !Array.isArray(firstPage.rows) || total > 10000) throw new Error('Incomplete product summary')
    const rows = [...firstPage.rows]
    for (let pageNum = 2; rows.length < total; pageNum++) {
      const page = await listProduct({ pageNum, pageSize, orderByColumn: 'productId', isAsc: 'asc' })
      if (!Array.isArray(page.rows) || !page.rows.length || numericValue(page.total) !== total) throw new Error('Product data changed during loading')
      rows.push(...page.rows)
    }
    if (rows.length !== total || new Set(rows.map(product => product.productId)).size !== total) throw new Error('Incomplete product summary')
    products.rows = rows
    products.total = total
  } catch {
    products.rows = []
    products.error = true
  } finally { products.loading = false }
}
async function loadAlert(force = false) {
  const key = stockFilter.value
  const option = stockOptions.find(item => item.value === key)
  if (!canReadProducts.value || !option?.fetch || alerts[key]?.loading || (!force && alerts[key] && !alerts[key].error)) return
  alerts[key] = { loading: true, error: false, rows: [], total: null }
  try {
    const result = await option.fetch({ pageNum: 1, pageSize: 8 })
    if (!Array.isArray(result.rows) || numericValue(result.total) === null) throw new Error('Invalid stock response')
    alerts[key].rows = result.rows
    alerts[key].total = Number(result.total)
  } catch { alerts[key].error = true }
  finally { alerts[key].loading = false }
}
async function loadTasks() {
  if (tasksLoading.value) return
  tasksLoading.value = true
  await Promise.allSettled(visibleTasks.value.map(async task => {
    task.loading = true
    task.error = false
    task.pending = null
    task.approved = null
    const results = await Promise.allSettled([1, 2].map(status => task.fetch({ pageNum: 1, pageSize: 1, ...task.params, [task.statusKey]: status })))
    results.forEach((result, index) => {
      const total = result.status === 'fulfilled' ? numericValue(result.value.total) : null
      if (total === null) task.error = true
      task[index === 0 ? 'pending' : 'approved'] = total
    })
    task.loading = false
  }))
  tasksLoading.value = false
}
async function loadFinance() {
  if (!canReadFinance.value) { finance.loading = false; return }
  if (finance.loading && finance.started) return
  finance.started = true
  finance.loading = true
  finance.error = false
  finance.data = null
  try {
    const result = await salesAmountQuery()
    if (!result || !financePeriods.every(period => Object.hasOwn(result, period.sales) && Object.hasOwn(result, period.profit))) throw new Error('Invalid sales response')
    finance.data = result
  } catch { finance.error = true }
  finally { finance.loading = false }
}
async function retryStock() { if (['all', 'out'].includes(stockFilter.value)) await loadProducts(); else await loadAlert(true) }
async function refreshDashboard() {
  if (refreshing.value) return
  refreshing.value = true
  Object.keys(alerts).forEach(key => { if (!alerts[key].loading) delete alerts[key] })
  await Promise.allSettled([loadProducts(), loadTasks(), loadFinance(), loadAlert(true)])
  lastUpdated.value = new Date()
  refreshing.value = false
}
watch(stockFilter, () => loadAlert())
onMounted(refreshDashboard)
</script>

<style scoped>
.workspace { max-width: 1680px; margin: 0 auto; padding: 26px 30px 30px; color: var(--sc-text, #18283b); }
.workspace-heading { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 24px; }
.workspace-heading > div, .workspace-grid > *, .workspace-panel-heading > div { min-width: 0; }
.workspace-date { margin-bottom: 8px; color: var(--sc-muted, #69778a); font-size: 12px; }
.workspace h1 { margin: 0; font-size: 27px; line-height: 1.3; font-weight: 650; letter-spacing: -.6px; text-wrap: balance; }
.workspace-heading p { margin: 8px 0 0; color: var(--sc-muted, #69778a); font-size: 13px; line-height: 1.6; }
.workspace-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.workspace-button { display: inline-flex; align-items: center; justify-content: center; gap: 8px; min-height: 40px; padding: 10px 14px; border: 1px solid var(--sc-border, #e2e8f0); border-radius: 7px; font: inherit; font-size: 13px; line-height: 1.4; cursor: pointer; touch-action: manipulation; transition: background-color .15s, border-color .15s; }
.workspace-button svg, .workspace-text-link svg { width: 16px; height: 16px; flex: 0 0 16px; }
.workspace-button-primary { background: var(--sc-primary, #2563eb); border-color: var(--sc-primary, #2563eb); color: var(--sc-on-primary, #fff); font-weight: 600; }
.workspace-button-primary:hover { background: var(--sc-primary-hover, #1d4ed8); border-color: var(--sc-primary-hover, #1d4ed8); }
.workspace-button-quiet { color: var(--sc-text, #18283b); background: var(--sc-surface, #fff); }
.workspace-button-quiet:hover { background: var(--sc-surface-soft, #f0f4fa); border-color: var(--sc-primary, #2563eb); }
.workspace-button:disabled { cursor: wait; opacity: .6; }
.workspace-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border: 1px solid var(--sc-border, #e2e8f0); border-radius: 10px; background: var(--sc-surface, #fff); padding: 23px 0; }
.workspace-metric { min-width: 0; display: flex; flex-direction: column; gap: 10px; padding: 0 26px; border-right: 1px solid var(--sc-border, #e2e8f0); }
.workspace-metric:last-child { border-right: 0; }
.workspace-metric-label { color: var(--sc-muted, #69778a); font-size: 13px; }
.workspace-metric strong { font-size: 31px; line-height: 1.1; font-weight: 650; letter-spacing: -.8px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.workspace-metric-note { color: var(--sc-muted, #69778a); font-size: 11px; line-height: 1.5; }
.workspace-number-alert { color: var(--sc-warning, #b45309); }
.workspace-data-status { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 6px 20px; margin: 12px 1px 18px; color: var(--sc-muted, #69778a); font-size: 11px; line-height: 1.6; }
.workspace-status-error { color: var(--sc-warning, #b45309); }
.workspace-grid { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(310px, 1fr); align-items: start; gap: 20px; }
.workspace-panel { background: var(--sc-surface, #fff); border: 1px solid var(--sc-border, #e2e8f0); border-radius: 10px; overflow: hidden; }
.workspace-tasks { padding: 0; margin: 0; color: inherit; font: inherit; line-height: normal; }
.workspace-panel-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding: 20px 22px 18px; }
.workspace h2 { margin: 0; font-size: 16px; line-height: 1.5; font-weight: 600; scroll-margin-top: 110px; }
.workspace-panel-heading p, .workspace-finance-heading p { margin: 5px 0 0; font-size: 12px; line-height: 1.6; color: var(--sc-muted, #69778a); }
.workspace select { max-width: 100%; min-height: 36px; border: 1px solid var(--sc-border, #e2e8f0); border-radius: 6px; padding: 7px 28px 7px 10px; color: var(--sc-text, #18283b); background-color: var(--sc-surface, #fff); font: inherit; font-size: 12px; cursor: pointer; }
.workspace-table-wrap { min-width: 0; }
.workspace-stock-table { width: 100%; table-layout: fixed; border-collapse: collapse; font-size: 13px; }
.workspace-stock-table th { background: var(--sc-surface-soft, #f5f7fb); color: var(--sc-muted, #69778a); text-align: left; font-size: 11px; font-weight: 500; padding: 11px 22px; }
.workspace-stock-table th:first-child { width: 57%; }
.workspace-stock-table th:nth-child(2) { width: 23%; }
.workspace-stock-table th:last-child { width: 20%; }
.workspace-stock-table td { border-bottom: 1px solid var(--sc-border, #e2e8f0); padding: 12px 22px; vertical-align: middle; }
.workspace-stock-table tr:last-child td { border-bottom: 0; }
.workspace-stock-table tbody tr:hover { background: var(--sc-surface-soft, #f5f7fb); }
.workspace-product-name, .workspace-product-meta { display: block; overflow-wrap: anywhere; }
.workspace-product-name { font-weight: 500; line-height: 1.55; }
.workspace-product-identity { display: flex; align-items: center; gap: 10px; }
.workspace-product-identity img { width: 40px; height: 40px; flex: 0 0 40px; object-fit: contain; background: var(--sc-surface-soft); border: 1px solid var(--sc-border); border-radius: 5px; }
.workspace-product-meta { color: var(--sc-muted, #69778a); font-size: 11px; line-height: 1.5; margin-top: 4px; }
.workspace-stock-quantity { font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.workspace-stock-quantity strong { font-size: 14px; font-weight: 600; }
.workspace-stock-quantity > span { color: var(--sc-muted, #69778a); margin-left: 5px; font-size: 11px; }
.workspace-stock-state { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; line-height: 1.5; }
.workspace-state-dot { height: 5px; width: 5px; flex: 0 0 5px; border-radius: 50%; background: currentColor; }
.workspace-stock-state-success { color: var(--sc-success, #157347); }
.workspace-stock-state-warning { color: var(--sc-warning, #b45309); }
.workspace-stock-state-muted { color: var(--sc-muted, #69778a); }
.workspace-panel-footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; border-top: 1px solid var(--sc-border, #e2e8f0); padding: 14px 22px; color: var(--sc-muted, #69778a); font-size: 11px; }
.workspace-text-link { display: inline-flex; align-items: center; gap: 5px; color: var(--sc-primary, #2563eb); font-size: 12px; min-height: 28px; }
.workspace-text-link:hover { text-decoration: underline; text-underline-offset: 4px; }
.workspace-empty { display: flex; align-items: flex-start; justify-content: center; flex-direction: column; min-height: 260px; gap: 9px; padding: 30px 22px; color: var(--sc-muted, #69778a); font-size: 13px; line-height: 1.7; }
.workspace-empty strong { color: var(--sc-text, #18283b); font-size: 14px; font-weight: 500; }
.workspace-empty p { margin: 0; max-width: 38em; }
.workspace-error { min-height: 260px; }
.workspace-task-total { color: var(--sc-primary, #2563eb); background: var(--sc-primary-soft, #eef4ff); border-radius: 5px; font-size: 11px; line-height: 1.5; padding: 5px 8px; font-variant-numeric: tabular-nums; }
.workspace-task-columns, .workspace-task-row { display: grid; grid-template-columns: minmax(0, 1fr) 64px 64px; align-items: center; gap: 12px; padding: 11px 22px; }
.workspace-task-columns { background: var(--sc-surface-soft, #f5f7fb); color: var(--sc-muted, #69778a); font-size: 11px; }
.workspace-task-columns span:nth-child(n+2), .workspace-task-row > strong, .workspace-approved-count { text-align: right; font-variant-numeric: tabular-nums; }
.workspace-task-row { min-height: 47px; color: var(--sc-text, #18283b); font-size: 12px; border-bottom: 1px solid var(--sc-border, #e2e8f0); transition: background-color .15s; }
.workspace-task-row:hover { background: var(--sc-surface-soft, #f5f7fb); }
.workspace-task-name { display: flex; align-items: center; min-width: 0; gap: 9px; }
.workspace-task-name svg { color: var(--sc-muted, #69778a); width: 16px; height: 16px; flex: 0 0 16px; }
.workspace-task-row > strong { font-size: 14px; font-weight: 600; }
.workspace-approved-count { color: var(--sc-muted, #69778a); }
.workspace-shortcuts { padding: 18px 22px 16px; }
.workspace h3 { margin: 0 0 10px; font-size: 12px; font-weight: 600; }
.workspace-shortcuts nav { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px 12px; }
.workspace-shortcuts a { display: flex; align-items: center; min-height: 36px; gap: 7px; min-width: 0; color: var(--sc-muted, #69778a); font-size: 12px; }
.workspace-shortcuts a svg { flex: 0 0 15px; width: 15px; height: 15px; }
.workspace-shortcuts a svg:last-child { width: 12px; height: 12px; flex-basis: 12px; margin-left: auto; }
.workspace-shortcuts a:hover { color: var(--sc-primary, #2563eb); }
.workspace-task-error, .workspace-finance-error { color: var(--sc-warning, #b45309); font-size: 12px; line-height: 1.6; padding: 12px 22px; }
.workspace-task-error button, .workspace-finance-error button { border: 0; padding: 4px 6px; background: transparent; color: var(--sc-primary, #2563eb); font: inherit; text-decoration: underline; text-underline-offset: 3px; cursor: pointer; }
.workspace-finance { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr); align-items: center; gap: 24px; padding: 20px 24px; margin-top: 20px; }
.workspace-finance-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 16px; }
.workspace-finance-heading p { width: 100%; margin: 0; }
.workspace-finance-values { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 28px; margin: 0; }
.workspace-finance-values > div { min-width: 0; }
.workspace-finance-values dt { font-size: 12px; color: var(--sc-muted, #69778a); margin-bottom: 9px; }
.workspace-finance-values dd { margin: 0; font-size: 24px; font-weight: 600; line-height: 1.3; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.workspace :is(button, a, select):focus-visible { outline: 3px solid var(--sc-primary, #2563eb); outline-offset: 3px; }
.workspace a, .workspace button { -webkit-tap-highlight-color: transparent; touch-action: manipulation; }
.workspace-sr-only, .workspace-skip:not(:focus) { position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; border: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.workspace-skip:focus { display: block; position: fixed; z-index: 2002; top: 10px; left: 12px; padding: 12px 16px; color: var(--sc-primary, #2563eb); background: var(--sc-surface, #fff); border-radius: 6px; }
@media (max-width: 1250px) { .workspace { padding: 24px; } .workspace-grid { grid-template-columns: minmax(0, 1fr) minmax(295px, .85fr); } .workspace-metric { padding: 0 20px; } .workspace-stock-table :is(td, th) { padding-left: 16px; padding-right: 16px; } }
@media (max-width: 1050px) { .workspace-grid { grid-template-columns: minmax(0, 1fr); } .workspace-heading { align-items: flex-start; flex-direction: column; gap: 16px; } .workspace-tasks { width: 100%; } .workspace-summary { padding: 20px 0; } .workspace-metric strong { font-size: 27px; } }
@media (max-width: 600px) { .workspace { padding: 20px 14px; } .workspace h1 { font-size: 24px; } .workspace-heading p { max-width: 25em; } .workspace-actions { width: 100%; } .workspace-actions .workspace-button { flex: 1; padding-left: 8px; padding-right: 8px; } .workspace-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 0; } .workspace-metric { padding: 18px; gap: 8px; } .workspace-metric:nth-child(2) { border-right: 0; } .workspace-metric:nth-child(-n+2) { border-bottom: 1px solid var(--sc-border, #e2e8f0); } .workspace-metric strong { font-size: 27px; } .workspace-panel-heading { padding: 18px 16px 14px; } .workspace-stock-table :is(td, th) { padding: 11px 12px; } .workspace-stock-table th:first-child { width: 49%; } .workspace-stock-table th:nth-child(2) { width: 26%; } .workspace-stock-table th:last-child { width: 25%; } .workspace-product-name { font-size: 12px; } .workspace-product-meta { font-size: 10px; } .workspace-stock-quantity strong { font-size: 13px; } .workspace-stock-quantity > span { display: block; margin: 3px 0 0; } .workspace-stock-state { gap: 4px; font-size: 10px; } .workspace-panel-footer { padding: 12px 16px; } .workspace-finance { grid-template-columns: minmax(0, 1fr); gap: 18px; padding: 18px 16px; } .workspace-finance-values { gap: 20px; } .workspace-finance-values dd { font-size: 22px; } .workspace-task-columns, .workspace-task-row { padding-left: 16px; padding-right: 16px; grid-template-columns: minmax(0, 1fr) 58px 58px; gap: 8px; } .workspace-shortcuts { padding-left: 16px; padding-right: 16px; } }
@media (prefers-reduced-motion: reduce) { .workspace *, .workspace *::before, .workspace *::after { transition: none !important; scroll-behavior: auto !important; } }
</style>
