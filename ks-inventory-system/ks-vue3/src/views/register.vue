<template>
  <div class="register">
    <el-form ref="registerRef" :model="registerForm" :rules="registerRules" class="register-form" label-position="top" @submit.prevent="handleRegister">
      <h3 class="title">注册铠思工作空间账号</h3>
      <el-form-item prop="username" label="账号">
        <el-input
          v-model="registerForm.username"
          name="username" autocomplete="username" :disabled="loading"
          type="text"
          size="large"
          placeholder="账号"
        >
          <template #prefix><svg-icon icon-class="user" class="el-input__icon input-icon" /></template>
        </el-input>
      </el-form-item>
      <el-form-item prop="password" label="密码">
        <el-input
          v-model="registerForm.password"
          name="password" autocomplete="new-password" :disabled="loading"
          type="password"
          size="large"
          placeholder="密码"
        >
          <template #prefix><svg-icon icon-class="password" class="el-input__icon input-icon" /></template>
        </el-input>
      </el-form-item>
      <el-form-item prop="confirmPassword" label="确认密码">
        <el-input
          v-model="registerForm.confirmPassword"
          name="confirmPassword" autocomplete="new-password" :disabled="loading"
          type="password"
          size="large"
          placeholder="确认密码"
        >
          <template #prefix><svg-icon icon-class="password" class="el-input__icon input-icon" /></template>
        </el-input>
      </el-form-item>
      <el-form-item prop="code" v-if="captchaEnabled" label="验证码">
        <el-input
          size="large"
          v-model="registerForm.code"
          name="code" :disabled="loading"
          autocomplete="off"
          placeholder="验证码"
          class="register-code-input"
        >
          <template #prefix><svg-icon icon-class="validCode" class="el-input__icon input-icon" /></template>
        </el-input>
        <button type="button" class="register-code" aria-label="刷新验证码" :disabled="loading" @click="getCode">
          <img :src="codeUrl" alt="验证码" width="100" height="40" class="register-code-img" />
        </button>
      </el-form-item>
      <el-form-item style="width:100%;">
        <el-button
          :loading="loading"
          size="large"
          type="primary"
          style="width:100%;"
          native-type="submit"
        >
          <span v-if="!loading">注册</span>
          <span v-else>正在注册…</span>
        </el-button>
        <div style="float: right;">
          <router-link class="link-type" :to="'/login'">使用已有账户登录</router-link>
        </div>
      </el-form-item>
    </el-form>
    <!--  底部  -->
    <div class="el-register-footer">
      <span>Copyright © 2018-2024 ruoyi.vip All Rights Reserved.</span>
    </div>
  </div>
</template>

<script setup>
import { ElMessageBox } from "element-plus";
import { getCodeImg, register } from "@/api/login";

const router = useRouter();
const { proxy } = getCurrentInstance();

const registerForm = ref({
  username: "",
  password: "",
  confirmPassword: "",
  code: "",
  uuid: ""
});

const equalToPassword = (rule, value, callback) => {
  if (registerForm.value.password !== value) {
    callback(new Error("两次输入的密码不一致"));
  } else {
    callback();
  }
};

const registerRules = {
  username: [
    { required: true, trigger: "blur", message: "请输入您的账号" },
    { min: 2, max: 20, message: "用户账号长度必须介于 2 和 20 之间", trigger: "blur" }
  ],
  password: [
    { required: true, trigger: "blur", message: "请输入您的密码" },
    { min: 5, max: 20, message: "用户密码长度必须介于 5 和 20 之间", trigger: "blur" },
    { pattern: /^[^<>"'|\\]+$/, message: "不能包含非法字符：< > \" ' \\\ |", trigger: "blur" }
  ],
  confirmPassword: [
    { required: true, trigger: "blur", message: "请再次输入您的密码" },
    { required: true, validator: equalToPassword, trigger: "blur" }
  ],
  code: [{ required: true, trigger: "change", message: "请输入验证码" }]
};

const codeUrl = ref("");
const loading = ref(false);
const captchaEnabled = ref(true);

function handleRegister() {
  proxy.$refs.registerRef.validate(valid => {
    if (valid) {
      loading.value = true;
      register(registerForm.value).then(res => {
        const username = registerForm.value.username;
        ElMessageBox.alert("账号 " + username + " 已注册，请登录。", "注册成功", {
          type: "success",
        }).then(() => {
          router.push("/login");
        }).catch(() => {});
      }).catch(() => {
        loading.value = false;
        if (captchaEnabled) {
          getCode();
        }
      });
    }
  });
}

function getCode() {
  getCodeImg().then(res => {
    captchaEnabled.value = res.captchaEnabled === undefined ? true : res.captchaEnabled;
    if (captchaEnabled.value) {
      codeUrl.value = "data:image/gif;base64," + res.img;
      registerForm.value.uuid = res.uuid;
    }
  });
}

getCode();
</script>

<style lang='scss' scoped>
.register {
  position: relative;
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100dvh;
  padding: 32px 20px 80px;
  background: var(--sc-bg);
}
.title {
  margin: 0px auto 30px auto;
  text-align: center;
  color: var(--sc-text);
  font-size: 22px;
  font-weight: 600;
}

.register-form {
  border: 1px solid var(--sc-border);
  border-radius: 10px;
  background: var(--sc-surface);
  width: 400px;
  max-width: 100%;
  padding: 32px 32px 12px;
  .el-input {
    height: 40px;
    input {
      height: 40px;
    }
  }
  .input-icon {
    height: 39px;
    width: 14px;
    margin-left: 0px;
  }
}
.register-tip {
  font-size: 13px;
  text-align: center;
  color: var(--sc-muted);
}
.register-code {
  display: flex; align-items: center; justify-content: center;
  flex: 0 0 100px; width: 100px; height: 40px; margin-left: 12px; padding: 0;
  border: 1px solid var(--sc-border); border-radius: 6px; background: var(--sc-surface-soft); cursor: pointer;
  img {
    cursor: pointer;
    vertical-align: middle;
  }
}
.el-register-footer {
  height: 40px;
  line-height: 40px;
  position: absolute;
  bottom: 0;
  width: 100%;
  text-align: center;
  color: var(--sc-muted);
  font-family: Arial;
  font-size: 12px;
  letter-spacing: 1px;
}
.register-code-img {
  height: 40px;
  width: 100%; object-fit: contain;
}
.register-code-input { width: calc(100% - 112px); }
.register-form :deep(.el-form-item__label) { font-weight: 500; color: var(--sc-text); }
</style>
