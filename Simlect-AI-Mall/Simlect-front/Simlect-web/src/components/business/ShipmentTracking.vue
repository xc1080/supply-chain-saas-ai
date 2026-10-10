<template>
  <div class="shipment-track">
    <div class="track-heading"><strong>{{ shipment.carrier || shipment.carrierCode || '物流' }} · {{ shipment.trackingNo }}</strong><el-button text size="small" :loading="loading" @click="load">刷新轨迹</el-button></div>
    <p>{{ shipment.createdAt }} · 出库单 {{ shipment.receiptId }} <el-tag size="small" type="info">模拟物流</el-tag></p>
    <p v-for="line in shipment.items || []" :key="line.productId">{{ items.find(item => String(item.productId) === String(line.productId))?.productName || line.productId }} × {{ line.quantity }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-timeline v-if="tracking?.events?.length"><el-timeline-item v-for="event in tracking.events" :key="event.eventId" :timestamp="event.occurredAt"><strong>{{ event.statusLabel || event.status }}</strong><span v-if="event.location"> · {{ event.location }}</span><p>{{ event.description }}</p></el-timeline-item></el-timeline>
    <p v-else-if="!loading">尚无物流节点，等待商家登记。</p>
  </div>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue';
import { orderApi } from '@/api/modules';
const props = defineProps<{ shipment: Record<string, any>; items: Record<string, any>[] }>();
const tracking = ref<Record<string, any> | null>(null), loading = ref(false), error = ref('');
let generation = 0;
async function load() {
  const id = String(props.shipment.shipmentId), current = ++generation; loading.value = true; error.value = '';
  try { const result = await orderApi.shipmentTracking(id); if (current === generation) tracking.value = result; }
  catch (failure: any) { if (current === generation) error.value = failure?.message || failure?.info || '轨迹暂未能读取'; }
  finally { if (current === generation) loading.value = false; }
}
watch(() => props.shipment.shipmentId, () => { tracking.value = null; void load(); }, { immediate: true });
</script>
<style scoped>
.shipment-track{padding:12px 0;border-bottom:1px solid #eceef1}.shipment-track:last-child{border-bottom:0}.track-heading{display:flex;justify-content:space-between;align-items:center;gap:10px}.shipment-track p{font-size:12px;color:#667085;margin:7px 0}.shipment-track .el-timeline{padding-left:15px;margin-top:20px}
</style>
