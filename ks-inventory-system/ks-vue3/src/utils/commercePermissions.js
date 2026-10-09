// Visibility mirrors business capabilities; the server remains the authority for every action.
const roleActions = {
  OPERATOR: ['CATALOG', 'FULFILMENT', 'SUPPLY_DRAFT'],
  CATALOG: ['CATALOG'], FULFILMENT: ['FULFILMENT'],
  FINANCE_REVIEW: ['REFUND_REVIEW'], FINANCE_EXECUTE: ['REFUND_EXECUTE'],
  SUPPLY_PLANNER: ['SUPPLY_DRAFT'], SUPPLY_REVIEWER: ['SUPPLY_REVIEW'],
  WAREHOUSE: ['FULFILMENT', 'STOCK_ADJUST'], VIEWER: []
}
export function commerceCapabilities(role) {
  if (role === 'OWNER') return ['READ', 'CATALOG', 'FULFILMENT', 'REFUND_REVIEW', 'REFUND_EXECUTE', 'STOCK_ADJUST', 'SUPPLY_POLICY', 'SUPPLY_DRAFT', 'SUPPLY_REVIEW', 'SHOP_MEMBERS']
  return Object.hasOwn(roleActions, role) ? ['READ', ...roleActions[role]] : []
}
