<template>
  <div class="sidebar-logo-container" :class="{ collapse }">
    <router-link class="sidebar-logo-link" to="/index" aria-label="供应链工作台">
      <span class="brand-mark" aria-hidden="true">
        <svg viewBox="0 0 32 32" width="28" height="28" fill="none">
          <path d="M16 3 28 10v13L16 30 4 23V10L16 3Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
          <path d="m4 10 12 7 12-7M16 17v13M10 6.5l12 7" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
        </svg>
      </span>
      <span v-if="!collapse" class="brand-copy">
        <span class="brand-title">供应链工作台</span>
        <span class="brand-subtitle">智能家居演示空间</span>
      </span>
    </router-link>
  </div>
</template>

<script setup>
defineProps({ collapse: { type: Boolean, required: true } })
</script>

<style lang="scss" scoped>
.sidebar-logo-container { height: 80px; padding: 0 20px; border-bottom: 1px solid rgba(255, 255, 255, .07); overflow: hidden; }
.sidebar-logo-link { display: flex !important; align-items: center; height: 100%; gap: 12px; width: 100%; }
.brand-mark { display: grid; place-items: center; flex: 0 0 34px; color: #90baff; }
.brand-copy { display: flex; flex-direction: column; gap: 5px; white-space: nowrap; }
.brand-title { color: var(--sc-sidebar-title, #ecf3ff); font-size: 15px; font-weight: 650; letter-spacing: .1px; }
.brand-subtitle { color: var(--sc-sidebar-text); font-size: 10px; }
.collapse { padding: 0 10px; }
</style>
