<template>
  <section class="shipment-tracking">
    <header><strong>{{ shipment.carrier || shipment.carrierCode || '物流' }} · {{ shipment.trackingNo }}</strong><el-button text size="small" :loading="loading" @click="refresh">刷新轨迹</el-button></header>
    <p>发货 {{ shipment.createdAt }} · 出库单 {{ shipment.receiptId }} <el-tag size="small" type="info">模拟物流</el-tag></p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-timeline v-if="tracking?.events?.length"><el-timeline-item v-for="event in tracking.events" :key="event.eventId" :timestamp="event.occurredAt"><strong>{{ event.statusLabel || statusNames[event.status] || event.status }}</strong><span v-if="event.location"> · {{ event.location }}</span><p>{{ event.description }}</p></el-timeline-item></el-timeline>
    <p v-else-if="!loading">尚未登记物流节点</p>
    <details v-if="canFulfil"><summary>登记模拟物流节点</summary><el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="物流状态"><el-select v-model="form.status" :disabled="saving"><el-option v-for="(label, value) in statusNames" :key="value" :value="value" :label="label" /></el-select></el-form-item>
      <el-form-item label="发生时间"><el-date-picker v-model="form.occurredAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :disabled="saving" /></el-form-item>
      <el-form-item label="地点"><el-input v-model="form.location" maxlength="100" :disabled="saving" /></el-form-item>
      <el-form-item label="节点说明"><el-input v-model="form.description" maxlength="200" :disabled="saving" /></el-form-item>
      <el-button type="primary" :loading="saving" :disabled="!form.occurredAt || !form.description.trim()" @click="submit">保存节点</el-button>
    </el-form></details>
  </section>
</template>
<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getCommerceShipmentTracking, addCommerceShipmentEvent } from '@/api/commerce/orders'
const props = defineProps({ shipment: { type: Object, required: true }, shopId: { type: String, required: true }, capabilities: { type: Array, default: () => [] } })
const canFulfil = computed(() => props.capabilities.includes('FULFILMENT'))
const tracking = ref(null), loading = ref(false), saving = ref(false), error = ref('')
const statusNames = { ACCEPTED: '已揽收', IN_TRANSIT: '运输中', OUT_FOR_DELIVERY: '派送中', DELIVERED: '已送达', EXCEPTION: '物流异常' }
const form = reactive({ status: 'ACCEPTED', occurredAt: '', location: '', description: '' })
let generation = 0, fingerprint = '', requestKey = ''
async function refresh() {
  const shop = props.shopId, id = props.shipment.shipmentId, current = ++generation; loading.value = true; error.value = ''
  try { const result = await getCommerceShipmentTracking(shop, id); if (current === generation && shop === props.shopId) tracking.value = result.data }
  catch (failure) { if (current === generation) error.value = failure?.message || '物流轨迹读取失败' }
  finally { if (current === generation) loading.value = false }
}
async function submit() {
  if (saving.value || !canFulfil.value || !form.occurredAt || !form.description.trim()) return
  const shop = props.shopId, id = props.shipment.shipmentId, current = generation
  const body = { ...form, description: form.description.trim(), location: form.location.trim() }
  const nextFingerprint = JSON.stringify([shop, id, body]); if (nextFingerprint !== fingerprint) { fingerprint = nextFingerprint; requestKey = crypto.randomUUID() }
  saving.value = true; error.value = ''
  try { await addCommerceShipmentEvent(shop, id, { ...body, requestKey }); if (current !== generation || shop !== props.shopId || id !== props.shipment.shipmentId) return; form.description = ''; form.location = ''; await refresh(); ElMessage.success('物流节点已保存') }
  catch (failure) { if (current === generation && shop === props.shopId && id === props.shipment.shipmentId) error.value = failure?.message || '节点结果未确认，请重试原操作' }
  finally { saving.value = false }
}
watch(() => [props.shopId, props.shipment.shipmentId], () => { generation++; tracking.value = null; Object.assign(form, { status: 'ACCEPTED', occurredAt: '', location: '', description: '' }); void refresh() }, { immediate: true })
</script>
<style scoped>
.shipment-tracking{padding:16px 0;border-top:1px solid var(--sc-border)}.shipment-tracking header{display:flex;justify-content:space-between;align-items:center;gap:10px}.shipment-tracking p{font-size:12px;color:var(--sc-muted);margin:8px 0}.shipment-tracking .el-timeline{margin:20px 0;padding-left:16px}.shipment-tracking summary{cursor:pointer;font-size:13px}.shipment-tracking .el-form{margin-top:18px;max-width:420px}
</style>
