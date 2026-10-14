<template>
  <main class="access-error" aria-labelledby="access-error-title">
    <el-icon class="access-error__icon" aria-hidden="true"><Lock /></el-icon>
    <p class="access-error__code">401</p>
    <h1 id="access-error-title">暂无访问权限</h1>
    <p class="access-error__message">如需使用此页面，请联系管理员分配权限。</p>
    <div class="access-error__actions">
      <el-button icon="ArrowLeft" @click="back">返回上一页</el-button>
      <router-link to="/index" class="access-error__home">回到工作台</router-link>
    </div>
  </main>
</template>

<script setup>
const { proxy } = getCurrentInstance();

function back() {
  if (proxy.$route.query.noGoBack) {
    proxy.$router.push({ path: "/" });
  } else {
    proxy.$router.go(-1);
  }
}
</script>

<style lang="scss" scoped>
.access-error { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 75dvh; max-width: 640px; margin: 0 auto; padding: 48px 24px; text-align: center; color: var(--sc-text); }
.access-error__icon { width: 72px; height: 72px; margin-bottom: 20px; border-radius: 16px; background: var(--sc-primary-soft); color: var(--sc-primary); font-size: 36px; }
.access-error__code { margin: 0 0 8px; color: var(--sc-muted); font-size: 14px; font-variant-numeric: tabular-nums; }
.access-error h1 { margin: 0; font-size: clamp(24px, 4vw, 32px); line-height: 1.35; font-weight: 600; }
.access-error__message { margin: 16px 0 24px; color: var(--sc-muted); font-size: 14px; line-height: 1.7; }
.access-error__actions { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 16px; }
.access-error__home { color: var(--sc-primary); font-size: 14px; }
.access-error__home:hover { text-decoration: underline; }
</style>
