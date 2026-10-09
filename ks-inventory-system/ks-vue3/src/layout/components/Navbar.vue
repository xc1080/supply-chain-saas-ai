<template>
  <header class="navbar">
    <div class="navbar-location">
      <hamburger id="hamburger-container" :is-active="appStore.sidebar.opened" @toggleClick="appStore.toggleSideBar()" />
      <breadcrumb v-if="!settingsStore.topNav" id="breadcrumb-container" class="breadcrumb-container" />
      <top-nav v-else id="topmenu-container" class="topmenu-container" />
    </div>
    <div class="right-menu">
      <template v-if="appStore.device !== 'mobile'">
        <header-search id="header-search" class="right-menu-item" />
        <router-link to="/ai/assistant" class="assistant-shortcut"><el-icon aria-hidden="true"><ChatDotRound /></el-icon><span>智能选品</span></router-link>
        <screenfull id="screenfull" class="right-menu-item" />
        <el-tooltip content="布局大小" placement="bottom"><size-select id="size-select" class="right-menu-item" /></el-tooltip>
      </template>
      <el-dropdown @command="handleCommand" trigger="click" class="account-menu">
        <button type="button" class="account-trigger" aria-label="账户菜单">
          <img :src="userStore.avatar" alt="" width="32" height="32" class="user-avatar" />
          <span class="account-name">{{ userStore.name || '账户' }}</span>
          <el-icon aria-hidden="true"><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile">个人中心</el-dropdown-item>
            <el-dropdown-item v-if="settingsStore.showSettings" command="setLayout">外观与布局</el-dropdown-item>
            <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<script setup>
import { ElMessageBox } from 'element-plus'
import { ArrowDown, ChatDotRound } from '@element-plus/icons-vue'
import Breadcrumb from '@/components/Breadcrumb'
import TopNav from '@/components/TopNav'
import Hamburger from '@/components/Hamburger'
import Screenfull from '@/components/Screenfull'
import SizeSelect from '@/components/SizeSelect'
import HeaderSearch from '@/components/HeaderSearch'
import useAppStore from '@/store/modules/app'
import useUserStore from '@/store/modules/user'
import useSettingsStore from '@/store/modules/settings'

const appStore = useAppStore()
const userStore = useUserStore()
const settingsStore = useSettingsStore()
const router = useRouter()
const emit = defineEmits(['setLayout'])

function handleCommand(command) {
  if (command === 'profile') router.push('/user/profile')
  if (command === 'setLayout') emit('setLayout')
  if (command === 'logout') {
    ElMessageBox.confirm('确定退出当前账户吗？', '退出登录', {
      confirmButtonText: '退出登录', cancelButtonText: '取消', type: 'warning'
    }).then(() => userStore.logOut().then(() => { location.href = '/index' })).catch(() => {})
  }
}
</script>

<style lang="scss" scoped>
.navbar { height: var(--sc-navbar-height); padding: 0 24px 0 18px; display: flex; align-items: center; justify-content: space-between; gap: 20px; background: var(--sc-surface); color: var(--sc-text); border-bottom: 1px solid var(--sc-border); }
.navbar-location { display: flex; align-items: center; min-width: 0; gap: 14px; }
.breadcrumb-container { min-width: 0; overflow: hidden; white-space: nowrap; }
.topmenu-container { flex: 1; min-width: 0; }
.right-menu { display: flex; align-items: center; flex-shrink: 0; gap: 12px; }
.right-menu-item { display: inline-flex; align-items: center; justify-content: center; color: var(--sc-muted); font-size: 17px; }
.assistant-shortcut { display: inline-flex; align-items: center; gap: 7px; padding: 9px 12px; border-radius: 7px; background: var(--sc-primary-soft); color: var(--sc-primary); font-size: 12px; font-weight: 500; }
.assistant-shortcut:hover { color: var(--sc-primary); filter: brightness(.97); }
.account-menu { margin-left: 4px; padding-left: 16px; border-left: 1px solid var(--sc-border); }
.account-trigger { display: flex; align-items: center; gap: 10px; padding: 0; border: 0; background: transparent; color: var(--sc-text); cursor: pointer; }
.user-avatar { border-radius: 9px; object-fit: cover; }
.account-name { max-width: 100px; overflow: hidden; text-overflow: ellipsis; font-size: 12px; }
.account-trigger > .el-icon { color: var(--sc-muted); font-size: 12px; }
@media (max-width: 991px) { .navbar { padding: 0 16px 0 10px; gap: 12px; } .navbar-location { gap: 8px; } .account-menu { border: 0; padding-left: 0; } .account-name { display: none; } }
</style>
