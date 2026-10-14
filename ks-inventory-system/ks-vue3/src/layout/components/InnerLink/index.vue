<template>
  <div :style="'height:' + height">
    <iframe
      :id="iframeId"
      style="width: 100%; height: 100%"
      :src="src"
      :title="title"
      frameborder="no"
    ></iframe>
  </div>
</template>

<script setup>
const props = defineProps({
  src: {
    type: String,
    default: "/"
  },
  title: { type: String, default: "嵌入页面" },
  iframeId: {
    type: String
  }
});

const height = ref(document.documentElement.clientHeight - 94.5 + "px");
</script>
