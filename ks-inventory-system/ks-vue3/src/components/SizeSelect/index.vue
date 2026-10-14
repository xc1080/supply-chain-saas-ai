<template>
  <div>
    <el-dropdown trigger="click" @command="handleSetSize">
      <button type="button" class="size-icon--style" aria-label="调整界面密度">
        <svg-icon class-name="size-icon" icon-class="size" aria-hidden="true" />
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item v-for="item of sizeOptions" :key="item.value" :disabled="size === item.value" :command="item.value">
            {{ item.label }}
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup>
import useAppStore from "@/store/modules/app";

const appStore = useAppStore();
const size = computed(() => appStore.size);
const route = useRoute();
const router = useRouter();
const { proxy } = getCurrentInstance();
const sizeOptions = ref([
  { label: "较大", value: "large" },
  { label: "默认", value: "default" },
  { label: "稍小", value: "small" },
]);

function handleSetSize(size) {
  proxy.$modal.loading("正在设置布局大小，请稍候...");
  appStore.setSize(size);
  setTimeout("window.location.reload()", 1000);
}
</script>

<style lang='scss' scoped>
.size-icon--style {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-size: 18px;
}
.size-icon--style:hover { background: var(--sc-primary-soft); color: var(--sc-primary); }
.size-icon--style:focus-visible { outline: 2px solid var(--sc-primary); outline-offset: 2px; }
</style>
