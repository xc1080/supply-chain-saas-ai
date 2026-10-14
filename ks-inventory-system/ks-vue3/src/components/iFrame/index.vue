<template>
  <div v-loading="loading" :style="'height:' + height">
    <iframe
      :src="url"
      :title="title"
      frameborder="no"
      style="width: 100%; height: 100%"
      scrolling="auto" />
  </div>
</template>

<script setup>
const props = defineProps({
  title: { type: String, default: "嵌入页面" },
  src: {
    type: String,
    required: true
  }
})

const height = ref(document.documentElement.clientHeight - 94.5 + "px;")
const loading = ref(true)
const url = computed(() => props.src)

onMounted(() => {
  setTimeout(() => {
    loading.value = false;
  }, 300);
  window.onresize = function temp() {
    height.value = document.documentElement.clientHeight - 94.5 + "px;";
  };
})
</script>
