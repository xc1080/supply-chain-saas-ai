<template>
  <section class="payment-panel">
    <el-collapse v-model="expanded" @change="onExpand">
      <el-collapse-item name="money">
        <template #title><div class="payment-heading"><h2>支付与退款核对</h2><el-tag v-if="report" :type="report.healthy ? 'success' : 'warning'" size="small">{{ report.healthy ? '已核对' : '有待处理记录' }}</el-tag></div></template>
        <div class="payment-toolbar"><span>本地支付沙箱</span><el-button text :loading="loading" @click="refresh">刷新</el-button></div>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <template v-if="report">
          <el-table :data="report.pendingOperations || []" v-loading="loading" empty-text="没有待确认的支付或退款">
            <el-table-column label="订单" prop="orderId" min-width="220" />
            <el-table-column label="业务" width="100"><template #default="scope">{{ kinds[scope.row.kind] || scope.row.kind }}</template></el-table-column>
            <el-table-column label="金额" width="110" align="right"><template #default="scope">{{ money(scope.row.amount) }}</template></el-table-column>
            <el-table-column label="状态" min-width="130"><template #default="scope">{{ states[scope.row.outcome] || scope.row.outcome }}</template></el-table-column>
            <el-table-column v-if="canQuery" label="操作" width="120"><template #default="scope"><el-button link type="primary" :disabled="!!acting" :loading="acting === scope.row.operationId" @click="query(scope.row)">查询渠道结果</el-button></template></el-table-column>
          </el-table>
          <template v-if="report.differences?.length"><h3>资金差异</h3><el-table :data="report.differences"><el-table-column label="订单" prop="orderId" min-width="220" /><el-table-column label="订单应留金额" align="right" width="150"><template #default="scope">{{ money(scope.row.expectedNet) }}</template></el-table-column><el-table-column label="渠道净收款" align="right" width="130"><template #default="scope">{{ money(scope.row.channelNet) }}</template></el-table-column><el-table-column label="差额" align="right" width="110"><template #default="scope"><strong class="difference">{{ money(scope.row.difference) }}</strong></template></el-table-column></el-table></template>
          <template v-if="report.unappliedEvents?.length"><h3>待补记的渠道通知</h3><el-table :data="report.unappliedEvents"><el-table-column label="通知编号" prop="eventId" min-width="220" /><el-table-column label="收到时间" prop="createdAt" min-width="170" /><el-table-column v-if="canQuery" label="操作" width="120"><template #default="scope"><el-button link type="primary" :disabled="!!acting" :loading="acting === scope.row.eventId" @click="replay(scope.row)">重试补记</el-button></template></el-table-column></el-table></template>
          <p v-if="report.untrackedLegacyOrders" class="legacy-coverage">{{ report.untrackedLegacyOrders }} 笔历史沙箱订单缺少渠道账本，未计入本次资金核对。</p>
        </template>
      </el-collapse-item>
    </el-collapse>
  </section>
</template>
<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getPaymentReconciliation, queryCommercePayment, replayCommercePaymentEvent } from '@/api/commerce/orders'
const props = defineProps({shopId:{type:String,required:true},canQuery:{type:Boolean,default:false}})
const emit = defineEmits(['updated'])
const expanded = ref([]), report = ref(null), loading = ref(false), acting = ref(''), error = ref('')
const kinds = {PAYMENT:'付款',REFUND:'售后退款',COMPENSATION:'关单退款'}
const states = {PREPARED:'请求待确认',PENDING:'渠道处理中',UNKNOWN:'结果待确认',COMPENSATION_PENDING:'关单退款处理中',SUCCEEDED:'已成功',COMPENSATED:'已退回',FAILED:'已失败'}
const money = value => new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'}).format(Number(value || 0))
async function refresh() {
  if (loading.value || !expanded.value.includes('money')) return
  const shop = props.shopId; loading.value = true; error.value = ''
  try { const response = await getPaymentReconciliation(); if (shop === props.shopId) report.value = response.data }
  catch (failure) { if (shop === props.shopId) error.value = failure?.message || '资金核对暂不可用，请重试' }
  finally { loading.value = false; if (shop !== props.shopId) void refresh() }
}
function onExpand() { if (expanded.value.includes('money') && !report.value) void refresh() }
async function perform(id,operation) {
  if (acting.value || !props.canQuery) return
  const shop = props.shopId; acting.value = id; error.value = ''
  try {const response = await operation(); if (shop !== props.shopId) return; ElMessage[response.data?.outcome === 'PENDING' ? 'info' : 'success'](response.data?.outcome === 'PENDING' ? '渠道仍在处理' : '处理结果已更新'); await refresh();emit('updated')}
  catch (failure) {if (shop === props.shopId) error.value = failure?.message || '处理结果暂未确认，请重新查询'}
  finally {acting.value = ''}
}
const query = row => perform(row.operationId,()=>queryCommercePayment(row.operationId))
const replay = row => perform(row.eventId,()=>replayCommercePaymentEvent(row.eventId))
watch(()=>props.shopId,()=>{report.value=null;error.value='';void refresh()})
defineExpose({refresh})
</script>
<style scoped>
.payment-panel{padding:4px 22px;margin-bottom:22px;background:var(--sc-surface);border:1px solid var(--sc-border);border-radius:10px}.payment-panel :deep(.el-collapse){border:0}.payment-panel :deep(.el-collapse-item__header){border:0;min-height:64px}.payment-panel :deep(.el-collapse-item__wrap){border:0}.payment-heading{display:flex;align-items:center;gap:14px}.payment-heading h2{font-size:18px;margin:0}.payment-toolbar{display:flex;justify-content:space-between;align-items:center;color:var(--sc-muted);margin-bottom:12px}.payment-panel h3{font-size:14px;margin:22px 0 10px}.difference{color:var(--sc-warning)}.legacy-coverage{font-size:12px;color:var(--sc-muted);margin:18px 0 0}@media(max-width:767px){.payment-panel{padding:4px 14px}}
</style>
