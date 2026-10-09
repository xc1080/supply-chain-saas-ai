
export function getDelistBlockReason(row) {
  if (row?.status != 1) return ''
  if (row?.commendType == 1) return '请先取消推荐后再下架'
  return ''
}


export function getDeleteBlockReason(row) {
  if (row?.status == 1) return '请先下架商品后再删除'
  if (row?.commendType == 1) return '请先取消推荐后再删除'
  return ''
}


export function getCommendBlockReason(row) {
  if (row?.commendType == 1) return ''
  if (row?.status != 1) return '仅已上架商品可设为推荐'
  return ''
}

export function canDelist(row) {
  return row?.status == 1 && !getDelistBlockReason(row)
}

export function canDelete(row) {
  return row?.status != -1 && !getDeleteBlockReason(row)
}

export function canCommend(row) {
  if (row?.commendType == 1) return true
  return !getCommendBlockReason(row)
}
