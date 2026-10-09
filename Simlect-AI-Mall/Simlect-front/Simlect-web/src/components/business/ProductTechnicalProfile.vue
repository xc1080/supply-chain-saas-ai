<template>
  <section v-if="profile" class="technical-profile">
    <h3>型号与兼容资料</h3>
    <p class="model">{{ profile.brand }} {{ profile.model }} <span v-if="profile.protocols?.length">· {{ profile.protocols.join(' / ') }}</span></p>
    <p v-if="profile.region" class="region">{{ profile.region }}</p>
    <ul><li v-for="fact in profile.facts || []" :key="fact">{{ fact }}</li></ul>
    <p v-if="profile.gatewayRequired === true">需要配套网关<span v-if="profile.compatibleGatewayModels?.length">：{{ profile.compatibleGatewayModels.join('、') }}</span>。购买前核对完整型号及区域版本。</p>
    <p v-if="profile.compatibilityStatus === 'unknown'">尚不能仅凭协议名称确认型号兼容性。</p>
    <details v-if="profile.sources?.length"><summary>厂商资料（{{ profile.sources.length }} 条）</summary>
      <ul><li v-for="source in profile.sources" :key="source.url"><a :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.title }} ↗</a></li></ul>
    </details>
  </section>
</template>
<script setup lang="ts">
defineProps<{ profile?: { brand?: string; model?: string; region?: string; protocols?: string[]; facts?: string[]; gatewayRequired?: boolean; compatibleGatewayModels?: string[]; compatibilityStatus?: string; sources?: { title: string; url: string }[] } | null }>();
</script>
<style scoped>
.technical-profile{padding:20px 0;line-height:1.8;border-bottom:1px solid #e8e8e8;margin-bottom:20px}.technical-profile h3{font-size:17px;margin:0 0 10px}.model{font-weight:600}.region{font-size:13px;color:#6d747b}.technical-profile ul{padding-left:20px}.technical-profile a{color:#536e8a;text-decoration:underline}.technical-profile summary{cursor:pointer;font-size:13px}
</style>
