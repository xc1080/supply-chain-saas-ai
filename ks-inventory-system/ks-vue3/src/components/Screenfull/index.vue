<template>
  <button type="button" class="fullscreen-button" :aria-label="isFullscreen ? '退出全屏' : '进入全屏'" @click="toggle">
    <svg-icon :icon-class="isFullscreen ? 'exit-fullscreen' : 'fullscreen'" aria-hidden="true" />
  </button>
</template>

<script setup>
import { useFullscreen } from '@vueuse/core'

const { isFullscreen, enter, exit, toggle } = useFullscreen();
</script>

<style lang='scss' scoped>
.fullscreen-button { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; padding: 0; border: 0; border-radius: 6px; background: transparent; color: var(--sc-muted); cursor: pointer; }
.fullscreen-button:hover { background: var(--sc-primary-soft); color: var(--sc-primary); }
.screenfull-svg {
  display: inline-block;
  cursor: pointer;
  fill: #5a5e66;
  width: 20px;
  height: 20px;
  vertical-align: 10px;
}
</style>
