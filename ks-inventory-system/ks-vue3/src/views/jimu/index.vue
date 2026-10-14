<template>
  <section class="report-workspace" v-loading="loading" element-loading-text="正在打开打印服务…">
    <el-empty v-if="error" description="打印服务暂时不可用">
      <el-button type="primary" @click="loadReport">重试</el-button>
    </el-empty>
    <iframe v-else-if="openUrl" :src="openUrl" title="打印模板与报表" class="report-frame" @load="loading = false" />
  </section>
</template>

<script setup name="Jimu">
import { ref } from 'vue'
import { getToken } from '@/utils/auth'
import { indexUrl } from '@/api/jimu/jiMuReport'

const openUrl = ref('')
const loading = ref(false)
const error = ref(false)

async function loadReport() {
  loading.value = true
  error.value = false
  try {
    const url = await indexUrl()
    openUrl.value = url + '?token=' + getToken()
  } catch {
    error.value = true
    loading.value = false
  }
}

loadReport()
</script>

<style scoped>
.report-workspace { min-height: 360px; height: calc(100dvh - 112px); }
.report-frame { display: block; width: 100%; height: 100%; border: 0; }
.report-workspace :deep(.el-empty) { height: 100%; }
</style>
