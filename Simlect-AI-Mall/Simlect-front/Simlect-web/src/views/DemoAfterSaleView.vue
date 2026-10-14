<template>
  <div class="after-sales-list">
    <header aria-label="售后列表操作"><el-button :loading="loading" @click="load">刷新</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section v-for="record in rows" :key="record.afterSalesId" class="card">
      <header><strong>{{ record.statusName || record.status }}</strong><span>{{ record.refundAmount == null ? '金额待确认' : `¥${Number(record.refundAmount).toFixed(2)}` }}</span></header>
      <p>{{ record.reason }}</p><p class="time">{{ record.createdAt }}</p>
      <RouterLink :to="'/order/' + record.orderId">查看处理进度</RouterLink>
    </section>
    <el-empty v-if="!loading && !error && !rows.length" description="暂无售后申请"><RouterLink to="/orders">查看我的订单</RouterLink></el-empty>
    <el-pagination v-if="total > 20" v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next" @current-change="load" />
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { afterSalesApi } from '@/api/modules';
const rows = ref<Record<string, any>[]>([]), total = ref(0), page = ref(1), loading = ref(false), error = ref('');
async function load() {
  if (loading.value) return;
  loading.value = true; error.value = '';
  try { const result = await afterSalesApi.list(page.value); rows.value = result.list || []; total.value = Number(result.totalCount || 0); }
  catch (failure: any) { error.value = failure?.info || '售后记录加载失败'; }
  finally { loading.value = false; }
}
onMounted(load);
</script>
<style scoped>
.after-sales-list > header { justify-content: flex-end; }
.after-sales-list{max-width:900px;margin:0 auto}.after-sales-list header{display:flex;align-items:center;justify-content:space-between;gap:20px}.after-sales-list h1{font-size:24px}.after-sales-list section{padding:22px;margin:18px 0}.after-sales-list p{line-height:1.7;font-size:14px}.time{color:#777}.after-sales-list a{font-size:14px;text-decoration:underline}
</style>
