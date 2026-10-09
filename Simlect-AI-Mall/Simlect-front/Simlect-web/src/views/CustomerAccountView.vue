<template>
  <main class="customer-account ignore">
    <section class="account-card">
      <RouterLink class="account-brand" to="/"><BrandMark /><span>简选 · Simlect</span></RouterLink>
      <template v-if="registered">
        <h1>{{ auth.userInfo?.nickName }}</h1>
        <p class="account-note">账号 {{ auth.userInfo?.userName }}</p>
        <RouterLink class="account-link" to="/orders">查看我的订单</RouterLink>
        <el-button class="account-submit" size="large" :loading="busy" @click="logout">退出登录</el-button>
      </template>
      <template v-else>
        <h1>{{ registering ? '保存我的购物记录' : '登录账户' }}</h1>
        <p class="account-note">{{ registering ? '注册会绑定当前访客的订单，换设备登录后仍可查看。' : '使用已注册账号恢复订单。登录已有账号不会合并当前访客记录。' }}</p>
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="账号">
            <el-input v-model="form.userName" size="large" maxlength="32" autocomplete="username" placeholder="4至32位英文或数字，可含 . _ -" />
          </el-form-item>
          <el-form-item v-if="registering" label="昵称">
            <el-input v-model="form.nickName" size="large" maxlength="40" autocomplete="nickname" placeholder="如何称呼你" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" size="large" type="password" show-password maxlength="64" :autocomplete="registering ? 'new-password' : 'current-password'" placeholder="至少10字" />
          </el-form-item>
          <el-form-item v-if="registering" label="确认密码">
            <el-input v-model="form.confirmPassword" size="large" type="password" show-password maxlength="64" autocomplete="new-password" />
          </el-form-item>
          <p v-if="error" role="alert" class="account-error">{{ error }}</p>
          <el-button type="primary" native-type="submit" class="account-submit" size="large" :loading="busy">{{ registering ? '注册并保存记录' : '登录' }}</el-button>
        </el-form>
        <div class="account-options">
          <RouterLink :to="{ path: registering ? '/login' : '/register', query: route.query }">{{ registering ? '已有账户，去登录' : '创建账户' }}</RouterLink>
          <button type="button" :disabled="busy" @click="continueAsGuest">继续访客浏览</button>
        </div>
      </template>
      <RouterLink class="account-home" to="/">返回商城</RouterLink>
    </section>
    <AppFooter />
  </main>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import BrandMark from '@/components/common/BrandMark.vue';
import AppFooter from '@/components/layout/AppFooter.vue';
import { useAuthStore } from '@/stores/auth';
import { resolveSafeRedirect } from '@/utils/navigation';

const auth = useAuthStore();
const route = useRoute();
const registering = computed(() => route.path === '/register');
const registered = computed(() => auth.userInfo?.identityType === 'ACCOUNT');
const form = reactive({ userName: '', nickName: '', password: '', confirmPassword: '' });
const busy = ref(false);
const error = ref('');

async function submit() {
  if (busy.value) return;
  error.value = '';
  if (registering.value && form.password !== form.confirmPassword) { error.value = '两次密码不一致'; return; }
  busy.value = true;
  try {
    if (registering.value) await auth.register(form);
    else await auth.login(form);
    // Clear all mounted account-specific views, cached carts and chat state.
    window.location.replace(resolveSafeRedirect(route.query.redirect));
  } catch (failure: any) {
    error.value = failure?.info || '账户服务暂不可用，请重试';
  } finally { busy.value = false; }
}

async function continueAsGuest() {
  busy.value = true;
  try {
    await auth.ensureSession();
    window.location.replace(resolveSafeRedirect(route.query.redirect));
  } finally { busy.value = false; }
}

async function logout() {
  busy.value = true;
  try {
    await auth.logout();
    window.location.replace('/login');
  } finally { busy.value = false; }
}
</script>

<style scoped lang="scss">
@use '@/styles/variables' as *;
.customer-account { min-height: 100vh; padding: 64px 20px 0; background: $color-bg; }
.account-card { max-width: 430px; margin: 0 auto 48px; padding: 32px; background: $color-card; border: 1px solid $color-border-light; border-radius: $radius-card; }
.account-brand { display: flex; align-items: center; gap: 12px; color: $color-primary; font-weight: 600; text-decoration: none; }
.account-brand :deep(svg) { width: 36px; height: 36px; }
h1 { margin: 28px 0 12px; font-size: 24px; }
.account-note { margin: 0 0 24px; font-size: 13px; line-height: 1.7; color: $color-text-muted; }
.account-submit { width: 100%; }
.account-options { display: flex; justify-content: space-between; margin-top: 22px; font-size: 13px; }
.account-options a, .account-link { color: $color-primary; }
.account-options button { border: 0; background: transparent; color: $color-text-muted; cursor: pointer; }
.account-error { color: $color-price; font-size: 13px; }
.account-home { display: block; margin-top: 24px; text-align: center; color: $color-text-muted; font-size: 13px; }
.account-link { display: block; margin: 28px 0; }
</style>
