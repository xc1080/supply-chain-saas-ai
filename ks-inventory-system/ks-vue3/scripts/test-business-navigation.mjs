import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { createRequire } from 'node:module'
import { join } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import vm from 'node:vm'
import { canShipCommerceOrder, commerceOrderStates } from '../src/utils/commerceOrderState.mjs'
import { documentQueryFromRoute, sourceOrderRoute, sourceReceiptRoute, splitReceiptIds, taskQueryRoute, verifiedOrderType, verifiedReceiptCategory } from '../src/utils/documentNavigation.mjs'

const require = createRequire(import.meta.url)
const { parse } = require('@babel/parser')
function vueScript(file) {
  const source = readFileSync(new URL('../src/' + file, import.meta.url), 'utf8')
  const script = source.match(/<script\s+setup[^>]*>([\s\S]*?)<\/script>/)[1]
  return { script, body: parse(script, { sourceType: 'module' }).program.body }
}
function declaration(page, name) {
  return page.body.flatMap(node => node.type === 'VariableDeclaration' ? node.declarations : []).find(node => node.id.name === name)
}
function functionSource(page, name) {
  const node = page.body.find(node => node.type === 'FunctionDeclaration' && node.id.name === name)
  assert.ok(node, name + ' must exist')
  return page.script.slice(node.start, node.end)
}
const taskPage = vueScript('views/index.vue')
const taskNode = declaration(taskPage, 'taskDefinitions')
const taskContext = Object.fromEntries(['ShoppingCart', 'Document', 'Tickets', 'Box', 'Sort', 'purchaseOrderHeadQuery', 'purchaseReceiptHeadQuery', 'salesOrderHeadQuery', 'salesReceiptHeadQuery', 'inventoryHeadQuery'].map(key => [key, {}]))
const tasks = vm.runInNewContext(taskPage.script.slice(taskNode.init.start, taskNode.init.end), taskContext)
const pages = {
  purchaseOrder: 'views/system/purchase/purchaseOrderQuery.vue',
  purchaseReceipt: 'views/system/purchase/purchaseDocumentQuery.vue',
  salesOrder: 'views/system/sales/salesOrderQuery.vue',
  salesReceipt: 'views/system/sales/salesDocumentQuery.vue',
  inventoryReceipt: 'views/system/inventory/InventoryDocumentQuery.vue',
  transfer: 'views/system/inventory/InventoryDocumentQuery.vue'
}
function queryDefaults(page) {
  const data = declaration(page, 'data').init.arguments[0]
  const query = data.properties.find(property => property.key.name === 'queryParams').value
  return vm.runInNewContext('(' + page.script.slice(query.start, query.end) + ')')
}
function plain(value) { return JSON.parse(JSON.stringify(value)) }

function exactLocalFile(relative) {
  let current = fileURLToPath(new URL('../src/', import.meta.url))
  const segments = relative.split('/')
  for (const [index, segment] of segments.entries()) {
    const entry = readdirSync(current, { withFileTypes: true }).find(item => item.name === segment)
    if (!entry || (index < segments.length - 1 ? !entry.isDirectory() : !entry.isFile())) return null
    current = join(current, segment)
  }
  return current
}
function resolveExactAlias(specifier) {
  const relative = specifier.slice(2)
  const candidates = /\.(?:js|mjs|vue)$/.test(relative) ? [relative] : [relative + '.js', relative + '.mjs', relative + '.vue', relative + '/index.js', relative + '/index.vue']
  return candidates.map(candidate => exactLocalFile(candidate)).find(Boolean)
}

test('new source modules and their page imports match exact filesystem case on Windows and Linux', () => {
  const componentFiles = ['components/SourceReceiptLink.vue', 'components/CommerceOrderStatus.vue']
  const relatedPages = [...new Set(Object.values(pages)), 'views/index.vue', 'views/commerce/orders.vue', 'views/system/purchase/purchaseDocumentProcessing.vue', 'views/system/purchase/purchaseOrderProcessing.vue', 'views/commerce/components/ShipmentTrackingPanel.vue', 'views/commerce/components/CostReconciliationPanel.vue']
  for (const file of [...componentFiles, ...relatedPages]) {
    const page = vueScript(file)
    const imports = page.body.filter(node => node.type === 'ImportDeclaration' && node.source.value.startsWith('@/'))
    for (const statement of imports) {
      const specifier = statement.source.value
      if (!componentFiles.includes(file) && !/\/(?:SourceReceiptLink|CommerceOrderStatus|documentNavigation\.mjs|commerceOrderState\.mjs)$/.test(specifier)) continue
      const resolved = resolveExactAlias(specifier)
      assert.ok(resolved, file + ': import must match exact path case: ' + specifier)
      if (specifier.startsWith('@/api/')) {
        const nodes = parse(readFileSync(resolved, 'utf8'), { sourceType: 'module' }).program.body
        const exported = new Set(nodes.flatMap(node => node.type === 'ExportNamedDeclaration' ? (node.declaration?.id ? [node.declaration.id.name] : (node.specifiers || []).map(item => item.exported.name)) : []))
        for (const imported of statement.specifiers.filter(item => item.type === 'ImportSpecifier')) assert.ok(exported.has(imported.imported.name), specifier + ': missing named export ' + imported.imported.name)
      }
    }
  }
  assert.equal(exactLocalFile('api/purchase/PurchaseDocumentProcessing.js'), null, 'case-insensitive filesystem lookup must not accept a wrong-case import')
  assert.ok(exactLocalFile('api/purchase/purchaseDocumentProcessing.js'))
})

test('partially shipped orders retain requested, failed and rejected after-sales states', () => {
  const cancelled = commerceOrderStates({ orderStatus: 4, fulfillmentStatus: 'SETTLED' })
  assert.equal(cancelled.find(state => state.axis === 'fulfillment').label, '无需发货', 'no remaining fulfilment must not claim financial clearance')
  for (const status of ['REQUESTED', 'REFUND_FAILED', 'REJECTED']) {
    const states = commerceOrderStates({ orderStatus: 1, fulfillmentStatus: 'PARTIALLY_SHIPPED', afterSalesStatus: status, refundedAmount: 10, totalAmount: 100 })
    assert.deepEqual(states.map(state => state.axis), ['order', 'fulfillment', 'afterSales'])
    assert.equal(states[1].label, '部分发货')
    assert.equal(states[2].value, status)
    if (status === 'REFUND_FAILED') assert.equal(states[2].tone, 'danger')
  }
  assert.equal(commerceOrderStates({ orderStatus: 1, totalAmount: 100, refundedAmount: 20 }).at(-1).label, '部分退款')
})

test('server SHIP actions take precedence over status and quantities, and require the shop capability', () => {
  const order = { orderStatus: 1, items: [{ quantity: 5, shippableQuantity: 2 }], availableActions: [] }
  assert.equal(canShipCommerceOrder(order, ['FULFILMENT']), false)
  assert.equal(canShipCommerceOrder({ ...order, availableActions: ['SHIP'] }, []), false)
  assert.equal(canShipCommerceOrder({ ...order, availableActions: ['SHIP'] }, ['FULFILMENT']), true)
  const legacy = { orderStatus: 1, items: [{ quantity: 5, shippableQuantity: 0 }] }
  assert.equal(canShipCommerceOrder(legacy, ['FULFILMENT']), false)
  assert.equal(canShipCommerceOrder({ ...legacy, items: [{ quantity: 5 }] }, ['FULFILMENT']), true)
})

test('each actual dashboard link reproduces the counter filters in its actual ERP query page', () => {
  for (const task of tasks) {
    const page = vueScript(pages[task.id])
    const defaults = queryDefaults(page)
    for (const status of [1, 2]) {
      const target = taskQueryRoute(task, status)
      const params = documentQueryFromRoute(target.query, defaults, 42)
      assert.equal(target.path, task.to)
      assert.equal(params.userIds, undefined, task.id + ' must use the same visible scope as its counter')
      for (const [key, value] of Object.entries({ ...task.params, [task.statusKey]: status })) {
        assert.equal(params[key], String(value), task.id + ' route must populate ' + key)
      }
      assert.equal(params.pageNum, 1)
    }
    const ordinary = documentQueryFromRoute({}, defaults, 42)
    assert.equal(ordinary.userIds, 42, task.id + ' ordinary entrance keeps the existing current-user default')
    assert.equal(ordinary[task.statusKey], undefined)
    assert.match(page.script, /watch\(\(\) => \[route\.path, route\.query\]/, 'cached pages must read a changed query')
  }
  const transfer = tasks.find(task => task.id === 'transfer')
  assert.equal(taskQueryRoute(transfer).query.receiptType, '7')
  assert.match(readFileSync(new URL('../src/views/index.vue', import.meta.url), 'utf8'), /:to="taskQueryRoute\(task, 2\)"/)
})

test('query route parsing rejects arrays and invalid values and keeps purchase category fixed', () => {
  const defaults = { pageNum: 2, userIds: undefined, receiptCategory: 1, receiptStatus: undefined, receiptType: undefined, systematicReceipt: undefined }
  const params = documentQueryFromRoute({ queryScope: ['visible'], receiptStatus: ['1'], receiptCategory: '2', receiptType: 'invalid', systematicReceipt: ['A', 'B'] }, defaults, 7)
  assert.equal(params.userIds, 7)
  assert.equal(params.receiptCategory, 1)
  assert.equal(params.receiptStatus, undefined)
  assert.equal(params.receiptType, undefined)
  assert.equal(params.systematicReceipt, undefined)
})

test('ERP business queries still load when the account cannot read the system user options', async () => {
  for (const task of tasks.filter(task => task.id !== 'transfer')) {
    const page = vueScript(pages[task.id])
    let queries = 0
    const context = {
      loading: { value: false }, queryParams: { value: queryDefaults(page) }, routeQueryDefaults: queryDefaults(page), defaultUserId: undefined,
      queryInitialized: false, route: { path: task.to, query: taskQueryRoute(task, 1).query }, option: { value: {} },
      listWarehouse: () => Promise.resolve({ rows: [] }), listSupplier: () => Promise.resolve({ rows: [] }), listCustomer: () => Promise.resolve({ rows: [] }),
      listUser: () => Promise.reject(new Error('403 Forbidden')), getUserProfile: () => Promise.resolve({ data: { userId: 42 } }),
      userOptions: { value: [] }, warehouseOptions: { value: [] }, supplierOptions: { value: [] }, customerOptions: { value: [] },
      documentQueryFromRoute, getList: () => { queries++ }
    }
    await vm.runInNewContext(functionSource(page, 'applyRouteQuery') + '; ' + functionSource(page, 'Options') + '; Options()', context)
    assert.equal(queries, 1, task.id + ' must not be blocked by unavailable user options')
    assert.equal(context.queryParams.value.userIds, undefined)
    assert.equal(context.queryParams.value[task.statusKey], '1')
  }
})

test('all actual ERP query pages ignore obsolete responses after a newer query or navigation', async () => {
  for (const task of tasks.filter(task => task.id !== 'transfer')) {
    const page = vueScript(pages[task.id])
    const responses = []
    const initialRows = [{ marker: 'initial' }]
    const listRefs = ['purchaseOrderList', 'salesOrderList', 'headReceiptList']
    const context = { queryRequestVersion: 0, loading: { value: false }, queryParams: { value: queryDefaults(page) }, dateRange: { value: [] }, route: { path: task.to }, total: { value: 0 }, proxy: { addDateRange: params => params } }
    for (const name of listRefs) context[name] = { value: initialRows }
    context.headQuery = context.purchaseOrderHeadQuery = () => new Promise(resolve => responses.push(resolve))
    vm.runInNewContext(functionSource(page, 'getList') + '; getList(); getList();', context)
    responses[1]({ rows: [{ marker: 'new' }], total: 2 })
    await Promise.resolve()
    responses[0]({ rows: [{ marker: 'old' }], total: 1 })
    await Promise.resolve()
    assert.equal(context.total.value, 2, task.id + ' older response must not overwrite latest counter')
    vm.runInNewContext('getList()', context)
    context.route.path = '/index'
    responses[2]({ rows: [{ marker: 'inactive' }], total: 99 })
    await Promise.resolve()
    assert.equal(context.total.value, 2, task.id + ' inactive response must not update cached page')
  }
})

test('receipt links require both the actual view permission and a registered ERP route', () => {
  const permission = 'sales:salesReceiptProcessing:systematicReceipt'
  const hasPermission = value => value === permission
  assert.equal(sourceReceiptRoute('XC-1', 2, () => false, () => true), null)
  assert.equal(sourceReceiptRoute('XC-1', 2, hasPermission, () => false), null)
  assert.equal(sourceReceiptRoute('XC-1', undefined, hasPermission, () => true), null)
  assert.deepEqual(sourceReceiptRoute('XC-1', 2, hasPermission, () => true), { path: '/sales/salesDocumentProcessing', query: { systematicReceipt: 'XC-1' } })
  assert.deepEqual(sourceReceiptRoute('XC-1', 2, value => value === 'sales:salesReceiptQuery:headQuery', () => true), { path: '/sales/salesDocumentQuery', query: { systematicReceipt: 'XC-1', receiptCategory: '2', queryScope: 'visible' } })
})

test('free-text purchase order references cannot be verified as receipt records', () => {
  assert.equal(verifiedReceiptCategory('notes', null), null)
  assert.equal(verifiedReceiptCategory('XC-1', { systematicReceipt: 'XC-10', receiptCategory: 2 }), null)
  assert.equal(verifiedReceiptCategory('CG-1', { systematicOrderForm: 'CG-1', orderFormType: 1 }), null)
  assert.equal(verifiedOrderType('CG-1', { systematicReceipt: 'CG-1', receiptCategory: 1 }), null)
  assert.equal(verifiedOrderType('CG-1', { systematicOrderForm: 'CG-1', orderFormType: 1 }), 1)
  assert.deepEqual(sourceOrderRoute('CG-1', 1, value => value === 'purchase:purchaseOrderProcessing:systematicOrderForm', () => true), { path: '/purchase/purchaseOrderProcessing', query: { systematicOrderForm: 'CG-1' } })
  assert.deepEqual(splitReceiptIds('XC-1, CR-2,XC-1'), ['XC-1', 'CR-2'])
  assert.equal(verifiedReceiptCategory('XC-1', { systematicReceipt: 'XC-1', receiptCategory: '2' }), 2)
})

test('source component verifies unknown references only on click, preserves permissions and cancels obsolete clicks', async () => {
  const page = vueScript('components/SourceReceiptLink.vue')
  let script = page.script
  for (const node of page.body.filter(node => node.type === 'ImportDeclaration').reverse()) script = script.slice(0, node.start) + script.slice(node.end)
  const props = { receipt: 'XC-1', category: '', verify: false, kind: 'receipt' }
  const callbacks = []
  const responses = []
  const pushed = []
  let calls = 0
  const context = {
    computed: getter => ({ get value() { return getter() } }), ref: value => ({ value }),
    defineProps: () => props, watch: (_, callback) => callbacks.push(callback), onBeforeUnmount: () => {},
    auth: { hasPermi: permission => ['inventory:inventoryReceiptQuery:systematicReceipt', 'sales:salesReceiptProcessing:systematicReceipt'].includes(permission) },
    useRouter: () => ({ getRoutes: () => [{ path: '/sales/salesDocumentProcessing' }], push: target => pushed.push(target) }),
    sourceReceiptRoute, sourceOrderRoute, splitReceiptIds, verifiedReceiptCategory, verifiedOrderType
  }
  for (const name of ['getPurchaseReceipt', 'getSalesReceipt', 'getReceipt', 'getPurchaseOrder', 'getSalesOrder', 'getInventoryReceipt', 'getPurchaseOrderQuery', 'getPurchaseOrderProcessing', 'getSalesOrderQuery', 'getSalesOrderProcessing']) {
    context[name] = () => { calls++; return new Promise(resolve => responses.push(resolve)) }
  }
  const component = vm.runInNewContext(script + '; ({ canVerify, verifyAndOpen, loadingId })', context)
  assert.equal(calls, 0, 'rendering source rows must not issue metadata requests')
  assert.equal(component.canVerify('XC-1'), true)
  const first = component.verifyAndOpen('XC-1')
  assert.equal(calls, 1)
  responses[0]({ data: { systematicReceipt: 'XC-1', receiptCategory: 2 } })
  await first
  assert.deepEqual(plain(pushed[0]), { path: '/sales/salesDocumentProcessing', query: { systematicReceipt: 'XC-1' } })
  const second = component.verifyAndOpen('XC-1')
  props.receipt = 'XC-2'
  callbacks[0]()
  responses[1]({ data: { systematicReceipt: 'XC-1', receiptCategory: 2 } })
  await second
  assert.equal(pushed.length, 1, 'a response after source props change must not navigate')
  props.category = 2
  props.verify = true
  assert.equal(component.canVerify('XC-2'), true, 'the sales Processing permission also permits its metadata lookup')
  props.category = 1
  assert.equal(component.canVerify('XC-2'), false, 'inventory lookup permission must not imply permission to a known purchase source')
  assert.equal(component.canVerify('a free text plan'), false)
})

test('cached detail pages reload changed IDs and ignore an older response', async () => {
  for (const [file, path, key, typeField, type] of [
    ['views/system/sales/salesOrderProcessing.vue', '/sales/salesOrderProcessing', 'systematicOrderForm', 'orderFormType', 2],
    ['views/system/sales/salesDocumentProcessing.vue', '/sales/salesDocumentProcessing', 'systematicReceipt', 'receiptCategory', 2],
    ['views/system/inventory/inventoryDocumentProcessing.vue', '/inventory/inventoryDocumentProcessing', 'systematicReceipt', 'receiptCategory', 3]
  ]) {
    const page = vueScript(file)
    const responses = []
    const context = { detailLoadVersion: 0, proxy: { $route: { path, query: { [key]: 'new' } } }, option: { value: {} }, form: { value: {} } }
    context.getSalesOrder = context.getReceipt = () => new Promise(resolve => responses.push(resolve))
    vm.runInNewContext(functionSource(page, 'loadDetail') + '; loadDetail("old");', context)
    responses[0]({ data: { [key]: 'old', [typeField]: type } })
    await Promise.resolve()
    assert.deepEqual(plain(context.form.value), {}, file + ' response for old ID must be ignored')
    assert.match(page.script, new RegExp('watch\\(\\(\\) => \\[proxy\\.\\$route\\.path, proxy\\.\\$route\\.query\\.' + key + '\\]'))
  }
})
