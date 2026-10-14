<template>
  <div class="login">
    <section class="login-intro" aria-labelledby="login-intro-title">
      <div class="login-brand">
        <svg viewBox="0 0 32 32" width="32" height="32" fill="none" aria-hidden="true"><path d="M16 3 28 10v13L16 30 4 23V10L16 3Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="m4 10 12 7 12-7M16 17v13M10 6.5l12 7" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/></svg>
        <span>铠思 · 供应链</span>
      </div>
      <h1 id="login-intro-title">采购、库存与交付，<br>在同一处有序协作。</h1>
      <p>从一张采购计划到每一次订单交付，<br class="login-desktop-break">让业务记录清晰，让团队衔接顺畅。</p>
      <div class="login-flow" aria-label="供应链业务流程">
        <div><span class="login-flow-symbol" aria-hidden="true">↗</span><strong>采购计划</strong><small>按需备货</small></div>
        <span class="login-flow-line" aria-hidden="true"></span>
        <div><span class="login-flow-symbol" aria-hidden="true">◇</span><strong>库存管理</strong><small>核对入库</small></div>
        <span class="login-flow-line" aria-hidden="true"></span>
        <div><span class="login-flow-symbol" aria-hidden="true">✓</span><strong>订单交付</strong><small>有据可查</small></div>
      </div>
    </section>
    <section class="login-access" aria-labelledby="login-title">
    <el-form
      ref="loginRef"
      :model="loginForm"
      :rules="loginRules"
      class="login-form"
      label-position="top"
      @submit.prevent="handleLogin"
    >
      <h2 id="login-title" class="title">登录工作空间</h2>
      <p class="login-description">使用已分配的账号，继续处理业务。</p>
      <el-form-item prop="username" label="账号">
        <el-input
          v-model="loginForm.username"
          type="text"
          size="large"
          autocomplete="username"
          placeholder="请输入账号"
          :disabled="loading"
        >
          <template #prefix
            ><svg-icon icon-class="user" class="el-input__icon input-icon"
          /></template>
        </el-input>
      </el-form-item>
      <el-form-item prop="password" label="密码">
        <el-input
          v-model="loginForm.password"
          type="password"
          size="large"
          autocomplete="current-password"
          placeholder="请输入密码"
          show-password
          :disabled="loading"
        >
          <template #prefix
            ><svg-icon icon-class="password" class="el-input__icon input-icon"
          /></template>
        </el-input>
      </el-form-item>
      <el-form-item prop="code" v-if="captchaEnabled" label="验证码">
        <el-input
          v-model="loginForm.code"
          size="large"
          autocomplete="off"
          placeholder="验证码"
          class="login-code-input"
          :disabled="loading"
        >
          <template #prefix
            ><svg-icon icon-class="validCode" class="el-input__icon input-icon"
          /></template>
        </el-input>
        <button type="button" class="login-code" aria-label="刷新验证码" :disabled="loading" @click="getCode"><img :src="codeUrl" alt="验证码" class="login-code-img" /></button>
      </el-form-item>
      <el-checkbox
        v-model="loginForm.rememberMe"
        class="login-remember"
        :disabled="loading"
        >记住密码</el-checkbox
      >
      <el-form-item style="width: 100%">
        <el-button
          :loading="loading"
          size="large"
          type="primary"
          style="width: 100%"
          native-type="submit"
        >
          <span v-if="!loading">登录工作空间</span>
          <span v-else>正在登录…</span>
        </el-button>
        <div style="float: right" v-if="register">
          <router-link class="link-type" :to="'/register'"
            >立即注册</router-link
          >
        </div>
      </el-form-item>
    </el-form>
    <a class="login-store-link" href="http://127.0.0.1:6001/" target="_blank" rel="noopener">进入顾客商城 <span aria-hidden="true">↗</span></a>
    </section>
    <!--  底部  -->
    <div class="el-login-footer">
      <span>Copyright © 2024 KS-inventory-system.top All Rights Reserved.</span>
    </div>
  </div>
</template>

<script setup>
import { getCodeImg } from "@/api/login";
import Cookies from "js-cookie";
import { encrypt, decrypt } from "@/utils/jsencrypt";
import useUserStore from "@/store/modules/user";

const userStore = useUserStore();
const route = useRoute();
const router = useRouter();
const { proxy } = getCurrentInstance();

const loginForm = ref({
  username: "",
  password: "",
  rememberMe: false,
  code: "",
  uuid: "",
});

const loginRules = {
  username: [{ required: true, trigger: "blur", message: "请输入您的账号" }],
  password: [{ required: true, trigger: "blur", message: "请输入您的密码" }],
  code: [{ required: true, trigger: "change", message: "请输入验证码" }],
};

const codeUrl = ref("");
const loading = ref(false);
// 验证码开关
const captchaEnabled = ref(true);
// 注册开关
const register = ref(false);
const redirect = ref(undefined);

watch(
  route,
  (newRoute) => {
    redirect.value = newRoute.query && newRoute.query.redirect;
  },
  { immediate: true }
);

function handleLogin() {
  if (loading.value) return;
  proxy.$refs.loginRef.validate((valid) => {
    if (valid) {
      loading.value = true;
      // 勾选了需要记住密码设置在 cookie 中设置记住用户名和密码
      if (loginForm.value.rememberMe) {
        Cookies.set("username", loginForm.value.username, { expires: 30 });
        Cookies.set("password", encrypt(loginForm.value.password), {
          expires: 30,
        });
        Cookies.set("rememberMe", loginForm.value.rememberMe, { expires: 30 });
      } else {
        // 否则移除
        Cookies.remove("username");
        Cookies.remove("password");
        Cookies.remove("rememberMe");
      }
      // 调用action的登录方法
      userStore
        .login(loginForm.value)
        .then(() => {
          const query = route.query;
          const otherQueryParams = Object.keys(query).reduce((acc, cur) => {
            if (cur !== "redirect") {
              acc[cur] = query[cur];
            }
            return acc;
          }, {});
          router.push({ path: redirect.value || "/", query: otherQueryParams });
          proxy.$modal.msgSuccess("欢迎使用铠思运营系统");
        })
        .catch(() => {
          loading.value = false;
          // 重新获取验证码
          if (captchaEnabled.value) {
            getCode();
          }
        });
    }
  });
}

function getCode() {
  getCodeImg().then((res) => {
    captchaEnabled.value =
      res.captchaEnabled === undefined ? true : res.captchaEnabled;
    if (captchaEnabled.value) {
      codeUrl.value = "data:image/gif;base64," + res.img;
      loginForm.value.uuid = res.uuid;
    }
  });
}

function getCookie() {
  const username = Cookies.get("username");
  const password = Cookies.get("password");
  const rememberMe = Cookies.get("rememberMe");
  loginForm.value = {
    username: username === undefined ? loginForm.value.username : username,
    password:
      password === undefined ? loginForm.value.password : decrypt(password),
    rememberMe: rememberMe === undefined ? false : Boolean(rememberMe),
  };
}

getCode();
getCookie();
</script>

<style lang="scss" scoped>
.login {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(380px, 1fr);
  min-height: 100dvh;
  padding: 64px max(6vw, 24px) 84px;
  gap: clamp(48px, 9vw, 140px);
  align-items: center;
  color: var(--sc-text);
  background: var(--sc-bg);
}
.login-intro { max-width: 580px; margin-left: auto; }
.login-brand { display: flex; align-items: center; gap: 12px; margin-bottom: 64px; font-size: 18px; font-weight: 600; }
.login-brand svg { color: var(--sc-primary); }
.login-intro h1 { margin: 0; font-size: clamp(30px, 3.2vw, 46px); line-height: 1.5; letter-spacing: -.8px; font-weight: 650; }
.login-intro > p { margin: 22px 0 42px; color: var(--sc-muted); font-size: 15px; line-height: 1.9; }
.login-flow { display: flex; align-items: center; padding-top: 28px; border-top: 1px solid var(--sc-border); }
.login-flow > div { display: grid; grid-template-columns: 28px auto; column-gap: 10px; align-items: center; }
.login-flow-symbol { grid-row: span 2; color: var(--sc-primary); font-size: 24px; }
.login-flow strong { font-size: 13px; font-weight: 600; }
.login-flow small { color: var(--sc-muted); font-size: 12px; margin-top: 4px; }
.login-flow-line { flex: 1; min-width: 12px; height: 1px; background: var(--sc-border); margin: 0 18px; }
.login-access { width: min(100%, 420px); }
.title {
  margin: 0;
  color: var(--sc-text);
  font-size: 24px;
  font-weight: 650;
}
.login-description { margin: 9px 0 30px; color: var(--sc-muted); font-size: 13px; line-height: 1.7; }
.login-form {
  border: 1px solid var(--sc-border);
  border-radius: 12px;
  background: var(--sc-surface);
  width: 100%;
  padding: 36px;
  :deep(.el-form-item__label) { padding-bottom: 7px; color: var(--sc-text); font-size: 13px; font-weight: 500; }
  :deep(.el-form-item) { margin-bottom: 22px; }
  :deep(.el-form-item:last-child) { margin-bottom: 0; }
  :deep(.el-input__wrapper) { border-radius: 6px; }
  :deep(.el-button) { min-height: 44px; border-radius: 6px; font-weight: 600; transition: transform .18s ease, opacity .18s ease; }
  :deep(.el-button:not(.is-loading):active) { transform: translateY(1px); }
  .el-input {
    height: 45px;
    input {
      height: 45px;
    }
  }
  .input-icon {
    height: 39px;
    width: 14px;
    margin-left: 0px;
  }
}
.login-remember { margin-bottom: 22px; }
.login-code-input { width: calc(100% - 112px); }
.login-code {
  display: flex; justify-content: center; align-items: center;
  width: 100px; height: 45px; margin-left: 12px; padding: 0;
  border: 1px solid var(--sc-border); border-radius: 6px; background: var(--sc-surface-soft); cursor: pointer;
}
.login-store-link { display: flex; align-items: center; justify-content: center; gap: 8px; margin-top: 22px; color: var(--sc-muted); font-size: 13px; }
.login-store-link:hover { color: var(--sc-primary); }
.el-login-footer {
  position: absolute;
  bottom: 24px;
  left: 16px; right: 16px;
  text-align: center;
  color: var(--sc-muted);
  font-family: Arial;
  font-size: 11px;
  line-height: 1.5;
}
.login-code-img { width: 100%; height: 38px; object-fit: contain; }
@media (max-width: 1000px) { .login { gap: 40px; padding: 48px 32px 84px; grid-template-columns: minmax(0, 1fr) minmax(340px, 1fr); } .login-intro h1 { font-size: 30px; } .login-flow-line { margin: 0 10px; } .login-flow-symbol { display: none; } .login-flow > div { display: flex; flex-direction: column; align-items: flex-start; } }
@media (max-width: 760px) { .login { display: flex; flex-direction: column; justify-content: center; align-items: stretch; gap: 26px; padding: 32px 20px 84px; position: relative; } .login-intro, .login-access { width: 100%; max-width: 420px; margin: 0 auto; } .login-brand { margin-bottom: 22px; font-size: 16px; } .login-intro h1 { font-size: 25px; line-height: 1.5; } .login-intro > p { margin: 12px 0 0; font-size: 13px; } .login-desktop-break, .login-flow { display: none; } .login-form { padding: 26px 24px; } .title { font-size: 22px; } }
</style>
