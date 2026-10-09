<template>
  <main id="workspace-main" class="app-main" tabindex="-1">
    <router-view v-slot="{ Component, route }">
      <!-- <transition name="fade-transform" mode="out-in"> -->
        <keep-alive :include="tagsViewStore.cachedViews">
          <component v-if="!route.meta.link" :is="Component" :key="route.path"/>
        </keep-alive>
      <!-- </transition> -->
    </router-view>
    <iframe-toggle />
  </main>
</template>

<script setup>
import iframeToggle from "./IframeToggle/index"
import useTagsViewStore from '@/store/modules/tagsView'

const tagsViewStore = useTagsViewStore()
</script>

<style lang="scss" scoped>
.app-main {
  min-height: calc(100vh - var(--sc-navbar-height));
  width: 100%;
  position: relative;
  overflow: hidden;
}

.fixed-header + .app-main {
  padding-top: var(--sc-navbar-height);
}

.hasTagsView {
  .app-main {
    min-height: calc(100vh - var(--sc-navbar-height) - var(--sc-tabs-height));
  }

  .fixed-header + .app-main {
    padding-top: calc(var(--sc-navbar-height) + var(--sc-tabs-height));
  }
}
</style>

<style lang="scss">
// fix css style bug in open el-dialog
.el-popup-parent--hidden {
  .fixed-header {
    padding-right: 6px;
  }
}

::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

::-webkit-scrollbar-track {
  background-color: var(--sc-bg);
}

::-webkit-scrollbar-thumb {
  background-color: var(--sc-border);
  border-radius: 3px;
}
</style>

