const receiptRoutes = {
  1: { processing: '/purchase/purchaseDocumentProcessing', query: '/purchase/purchaseDocumentQuery', permission: 'purchase:purchaseReceiptProcessing:systematicReceipt', queryPermission: 'purchase:purchaseReceiptQuery:headQuery' },
  2: { processing: '/sales/salesDocumentProcessing', query: '/sales/salesDocumentQuery', permission: 'sales:salesReceiptProcessing:systematicReceipt', queryPermission: 'sales:salesReceiptQuery:headQuery' },
  3: { processing: '/inventory/inventoryDocumentProcessing', query: '/inventory/inventoryDocumentQuery', permission: 'inventory:inventoryReceiptProcessing:systematicReceipt', queryPermission: 'inventory:inventoryReceiptQuery:headQuery' }
}
const orderRoutes = {
  1: { processing: '/purchase/purchaseOrderProcessing', query: '/purchase/purchaseOrderQuery', permission: 'purchase:purchaseOrderProcessing:systematicOrderForm', queryPermission: 'purchase:purchaseOrderQuery:headQuery' },
  2: { processing: '/sales/salesOrderProcessing', query: '/sales/salesOrderQuery', permission: 'sales:salesOrderProcessing:systematicOrderForm', queryPermission: 'sales:salesOrderQuery:headQuery' }
}

export function splitReceiptIds(value) {
  return [...new Set(String(value ?? '').split(',').map(id => id.trim()).filter(Boolean))]
}

export function sourceReceiptRoute(receipt, category, hasPermission, hasRoute) {
  const config = receiptRoutes[Number(category)]
  if (!receipt || !config) return null
  if (hasPermission(config.permission) && hasRoute(config.processing)) {
    return { path: config.processing, query: { systematicReceipt: String(receipt) } }
  }
  if (hasPermission(config.queryPermission) && hasRoute(config.query)) {
    return { path: config.query, query: { systematicReceipt: String(receipt), receiptCategory: String(category), queryScope: 'visible' } }
  }
  return null
}

export function verifiedReceiptCategory(receipt, record) {
  return record && String(record.systematicReceipt) === String(receipt) && receiptRoutes[Number(record.receiptCategory)]
    ? Number(record.receiptCategory) : null
}

export function sourceOrderRoute(order, type, hasPermission, hasRoute) {
  const config = orderRoutes[Number(type)]
  if (!order || !config) return null
  if (hasPermission(config.permission) && hasRoute(config.processing)) {
    return { path: config.processing, query: { systematicOrderForm: String(order) } }
  }
  if (hasPermission(config.queryPermission) && hasRoute(config.query)) {
    return { path: config.query, query: { systematicOrderForm: String(order), orderFormType: String(type), queryScope: 'visible' } }
  }
  return null
}

export function verifiedOrderType(order, record) {
  return record && String(record.systematicOrderForm) === String(order) && orderRoutes[Number(record.orderFormType)]
    ? Number(record.orderFormType) : null
}

export function taskQueryRoute(task, status = 1) {
  return { path: task.to, query: Object.fromEntries(Object.entries({ ...task.params, [task.statusKey]: status, queryScope: 'visible' }).map(([key, value]) => [key, String(value)])) }
}

function routeValue(value) { return typeof value === 'string' ? value : undefined }

// Only accept the documented query fields. Visible means no client-side userIds filter;
// server-side tenant and data permissions still determine the available records.
export function documentQueryFromRoute(query, defaults, userId) {
  const params = { ...defaults, pageNum: 1, userIds: routeValue(query.queryScope) === 'visible' ? undefined : userId }
  for (const key of ['systematicReceipt', 'systematicOrderForm']) {
    const value = routeValue(query[key])
    if (key in defaults && value) params[key] = value
  }
  const allowed = { orderFormStatus: [1, 2], orderFormType: [1, 2], receiptStatus: [1, 2], receiptCategory: [1, 2, 3], receiptType: [1, 2, 3, 4, 5, 6, 7, 8] }
  for (const [key, values] of Object.entries(allowed)) {
    const value = routeValue(query[key])
    if (!(key in defaults) || !value || !values.includes(Number(value))) continue
    // A purchase/sales page keeps its own document category/type.
    if ((key === 'receiptCategory' || key === 'orderFormType') && defaults[key] != null && Number(defaults[key]) !== Number(value)) continue
    params[key] = String(Number(value))
  }
  return params
}
