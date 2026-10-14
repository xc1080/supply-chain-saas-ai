<template>
  <div class="icon-body">
    <el-input
      v-model="iconName"
      class="icon-search"
      clearable
      placeholder="请输入图标名称"
      aria-label="搜索图标"
      @clear="filterIcons"
      @input="filterIcons"
    >
      <template #suffix><i class="el-icon-search el-input__icon" /></template>
    </el-input>
    <div class="icon-list">
      <div class="list-container">
        <button v-for="(item, index) in iconList" type="button" class="icon-item-wrapper" :key="index" :aria-label="'选择图标 ' + item" :aria-pressed="activeIcon === item" @click="selectedIcon(item)">
          <span :class="['icon-item', { active: activeIcon === item }]">
            <svg-icon :icon-class="item" class-name="icon" style="height: 25px;width: 16px;"/>
            <span>{{ item }}</span>
          </span>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import icons from './requireIcons'

const props = defineProps({
  activeIcon: {
    type: String
  }
});

const iconName = ref('');
const iconList = ref(icons);
const emit = defineEmits(['selected']);

function filterIcons() {
  iconList.value = icons
  if (iconName.value) {
    iconList.value = icons.filter(item => item.indexOf(iconName.value) !== -1)
  }
}

function selectedIcon(name) {
  emit('selected', name)
  document.body.click()
}

function reset() {
  iconName.value = ''
  iconList.value = icons
}

defineExpose({
  reset
})
</script>

<style lang='scss' scoped>
   .icon-body {
    width: 100%;
    padding: 10px;
    .icon-search {
      position: relative;
      margin-bottom: 5px;
    }
    .icon-list {
      height: 200px;
      overflow: auto;
      .list-container {
        display: flex;
        flex-wrap: wrap;
        .icon-item-wrapper {
          width: calc(100% / 3);
          min-height: 32px;
          line-height: 32px;
          padding: 0;
          border: 0;
          background: transparent;
          color: var(--sc-text);
          text-align: left;
          font: inherit;
          cursor: pointer;
          display: flex;
          .icon-item {
            display: flex;
            max-width: 100%;
            height: 100%;
            padding: 0 5px;
            &:hover {
              background: var(--sc-surface-soft);
              border-radius: 5px;
            }
            .icon {
              flex-shrink: 0;
            }
            span {
              display: inline-block;
              vertical-align: -0.15em;
              fill: currentColor;
              padding-left: 2px;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
            }
          }
          .icon-item.active {
            background: var(--sc-primary-soft);
            color: var(--sc-primary);
            border-radius: 5px;
          }
        }
      }
    }
  }
</style>
