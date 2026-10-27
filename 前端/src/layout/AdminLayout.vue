<template>
  <el-container class="admin-layout">
    <button v-if="isMobile && mobileOpen" class="sidebar-overlay" aria-label="关闭导航菜单" @click="mobileOpen = false" />
    <el-aside :width="collapsed ? '68px' : '230px'" class="sidebar" :class="{ 'mobile-open': mobileOpen }">
      <a class="logo" href="/dashboard" @click.prevent="router.push('/dashboard')">
        <span class="brand-symbol"><el-icon><Collection /></el-icon></span>
        <span v-if="!collapsed" class="brand-copy"><strong>Gallery Import</strong><small>你的画廊工作台</small></span>
      </a>
      <div class="navigation-scroll">
        <el-menu :default-active="route.path" router :collapse="collapsed" :collapse-transition="false" @select="mobileOpen = false">
          <el-menu-item-group v-for="group in navigation" :key="group.label">
            <template #title><span v-if="!collapsed">{{ group.label }}</span></template>
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.label }}</template>
            </el-menu-item>
          </el-menu-item-group>
        </el-menu>
      </div>
      <button class="sidebar-settings" :title="collapsed ? '显示设置' : ''" @click="openPreferences('appearance')"><el-icon><Setting /></el-icon><span v-if="!collapsed">显示与账户设置</span></button>
    </el-aside>

    <el-container class="content-shell">
      <el-header class="header">
        <div class="header-left"><button class="collapse-btn" :aria-label="isMobile ? '打开导航菜单' : isCollapse ? '展开导航' : '收起导航'" @click="toggleNavigation"><el-icon><Expand v-if="collapsed || isMobile" /><Fold v-else /></el-icon></button><span class="breadcrumb-root">工作台</span><span class="breadcrumb-separator">/</span><span class="page-title">{{ route.meta.title }}</span></div>
        <div class="header-right">
          <button class="display-button" title="调整字体大小" aria-label="显示设置" @click="openPreferences('appearance')"><span>Aa</span><small>{{ currentPreset.label }}</small></button>
          <i class="header-divider" />
          <el-dropdown @command="handleCommand">
            <button class="user-info" aria-label="账户菜单"><span class="user-avatar">{{ username.slice(0, 1).toUpperCase() || 'A' }}</span><span class="username">{{ username }}</span><el-icon><ArrowDown /></el-icon></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="appearance" :icon="Setting">字体与显示</el-dropdown-item><el-dropdown-item command="password" :icon="Lock">修改密码</el-dropdown-item><el-dropdown-item command="logout" :icon="SwitchButton" divided :disabled="loggingOut">{{ loggingOut ? '正在退出…' : '退出登录' }}</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main-content"><router-view /></el-main>
    </el-container>
    <SystemPreferences v-model="preferencesVisible" :initial-tab="preferencesTab" />
  </el-container>
</template>

<script setup>
// 管理系统的导航与页面容器，提供移动端菜单、显示设置和账户操作入口。
import { computed, markRaw, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Collection, Connection, DataAnalysis, Expand, Finished, Fold, FolderChecked, List, Lock, Monitor, Operation, Picture, Refresh, Setting, Share, SwitchButton } from '@element-plus/icons-vue'
import api from '../api'
import SystemPreferences from '../components/SystemPreferences.vue'
import { FONT_PRESETS, useUiPreferences } from '../stores/uiPreferences'

const route = useRoute(), router = useRouter(), preferences = useUiPreferences()
const isCollapse = ref(false), isMobile = ref(false), mobileOpen = ref(false), loggingOut = ref(false)
const preferencesVisible = ref(false), preferencesTab = ref('appearance')
const collapsed = computed(() => !isMobile.value && isCollapse.value)
const currentPreset = computed(() => FONT_PRESETS.find(preset => preset.value === preferences.fontScale) || FONT_PRESETS[1])
const username = (() => { try { return localStorage.getItem('username') || '管理员' } catch { return '管理员' } })()
const item = (path, label, icon) => ({ path, label, icon: markRaw(icon) })
const navigation = [
  { label: '内容与发现', items: [item('/dashboard', '数据概览', DataAnalysis), item('/galleries', '画廊列表', List), item('/collections', '画廊合集', Collection), item('/tag-network', '关系图谱', Share)] },
  { label: '归档与审核', items: [item('/dedupe-reviews', '去重审核', Finished), item('/visual-dedup', '视觉指纹', Picture), item('/archive-sync', '群晖归档同步', FolderChecked), item('/komga-import-reviews', 'Komga 入库复核', Refresh)] },
  { label: '自动化与系统', items: [item('/operations', '工作流操作', Operation), item('/workflows', '流程监控', Connection), item('/monitoring', '监控大盘', Monitor), item('/ai-settings', 'AI 设置', Setting)] }
]
let mediaQuery
// 根据屏幕宽度切换侧栏模式，并收起可能残留的移动端抽屉。
function checkScreen() { isMobile.value = mediaQuery.matches; mobileOpen.value = false }
function toggleNavigation() { if (isMobile.value) mobileOpen.value = !mobileOpen.value; else isCollapse.value = !isCollapse.value }
// 从统一入口打开显示或密码设置，同时收起移动导航。
function openPreferences(tab) { preferencesTab.value = tab; preferencesVisible.value = true; mobileOpen.value = false }
// 处理账户菜单；退出时通知服务端注销，再清除本地会话。
async function handleCommand(command) {
  if (command === 'appearance' || command === 'password') return openPreferences(command)
  if (command !== 'logout' || loggingOut.value) return
  loggingOut.value = true
  try { await api.post('/auth/logout', null, { silent: true, timeout: 5000 }) }
  catch { /* 退出接口暂不可达时也清除本地凭据，完成用户要求的退出操作。 */ }
  finally {
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    loggingOut.value = false
    router.replace('/login')
  }
}
// 其他标签页修改字号时同步恢复，让同一浏览器内的页面保持一致。
function onStorage(event) { if (event.key === 'gallery-ui-preferences-v1') preferences.restore() }
onMounted(() => {
  mediaQuery = window.matchMedia('(max-width: 900px)')
  checkScreen()
  mediaQuery.addEventListener('change', checkScreen)
  window.addEventListener('storage', onStorage)
})
onUnmounted(() => { mediaQuery?.removeEventListener('change', checkScreen); window.removeEventListener('storage', onStorage) })
</script>

<style scoped>
.admin-layout { height: 100vh; width: 100%; overflow: hidden; }
.sidebar { display: flex; flex-direction: column; background: #fff; border-right: 1px solid var(--app-border); transition: width .2s ease, transform .2s ease; flex-shrink: 0; overflow: hidden; z-index: 15; }
.logo { height: 78px; min-height: 78px; padding: 0 19px; box-sizing: border-box; display: flex; align-items: center; gap: 10px; text-decoration: none; color: var(--app-text); }
.brand-symbol { display: grid; place-items: center; height: 31px; width: 31px; border-radius: 9px; background: #eaf0ff; color: #577de0; flex-shrink: 0; font-size: calc(19px * var(--app-font-scale)); }
.brand-copy { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.brand-copy strong { font-size: calc(14px * var(--app-font-scale)); font-weight: 700; letter-spacing: -.02em; white-space: nowrap; }
.brand-copy small { font-size: calc(9px * var(--app-font-scale)); color: #a2aec0; white-space: nowrap; letter-spacing: .04em; }
.navigation-scroll { flex: 1; min-height: 0; overflow-y: auto; overflow-x: hidden; padding: 0 11px 18px; }
.sidebar :deep(.el-menu) { border-right: 0; background: none; }
.sidebar :deep(.el-menu-item-group__title) { padding: 21px 15px 9px; font-size: calc(10px * var(--app-font-scale)); color: #a1adc0; letter-spacing: .03em; line-height: 1.5; }
.sidebar :deep(.el-menu-item-group:first-child .el-menu-item-group__title) { padding-top: 9px; }
.sidebar :deep(.el-menu-item) { display: flex; min-height: 41px; height: auto; padding-top: 10px; padding-bottom: 10px; border-radius: 7px; margin-bottom: 4px; line-height: 1.6; color: #73839d; font-size: calc(12px * var(--app-font-scale)); }
.sidebar :deep(.el-menu-item .el-icon) { color: #97a6bc; font-size: calc(16px * var(--app-font-scale)); width: 19px; margin-right: 10px; }
.sidebar :deep(.el-menu-item:hover) { background: #f6f8fc; }
.sidebar :deep(.el-menu-item.is-active) { color: #4169e1; background: #eff4ff; font-weight: 600; }
.sidebar :deep(.el-menu-item.is-active .el-icon) { color: #6486e3; }
.sidebar :deep(.el-menu--collapse) { width: 45px; }
.sidebar :deep(.el-menu--collapse .el-menu-item) { justify-content: center; padding-left: 0 !important; padding-right: 0 !important; }
.sidebar :deep(.el-menu--collapse .el-menu-item .el-icon) { margin-right: 0; }
.sidebar :deep(.el-menu--collapse .el-menu-item-group__title) { padding: 10px 0; }
.sidebar-settings { display: flex; align-items: center; gap: 10px; padding: 17px 22px; border: 0; border-top: 1px solid #edf1f6; background: #fff; color: #8e9db3; cursor: pointer; font-size: calc(11px * var(--app-font-scale)); line-height: 1.6; white-space: nowrap; }
.sidebar-settings .el-icon { font-size: calc(16px * var(--app-font-scale)); flex-shrink: 0; }
.sidebar-settings:hover { background: #fafbfd; color: var(--app-accent); }
.content-shell { min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.header { min-height: 61px; height: auto; padding: 12px 26px; box-sizing: border-box; display: flex; align-items: center; justify-content: space-between; background: #ffffffed; border-bottom: 1px solid var(--app-border); gap: 15px; }
.header-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.collapse-btn { display: grid; place-items: center; width: 28px; height: 28px; background: none; border: 0; border-radius: 5px; color: #90a0b7; padding: 0; cursor: pointer; font-size: calc(17px * var(--app-font-scale)); }
.collapse-btn:hover { background: #f0f4fb; color: var(--app-accent); }
.breadcrumb-root { color: #9daabd; font-size: calc(11px * var(--app-font-scale)); white-space: nowrap; }
.breadcrumb-separator { color: #d7deea; font-size: calc(12px * var(--app-font-scale)); }
.page-title { color: #64758e; font-size: calc(12px * var(--app-font-scale)); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.header-right { display: flex; align-items: center; gap: 17px; flex-shrink: 0; }
.display-button { display: flex; align-items: center; gap: 7px; background: none; border: 0; padding: 4px; cursor: pointer; color: #7890b5; }
.display-button > span { font-size: calc(16px * var(--app-font-scale)); font-weight: 500; }
.display-button small { font-size: calc(10px * var(--app-font-scale)); color: #96a4b9; }
.header-divider { height: 17px; width: 1px; background: #e4eaf3; }
.user-info { display: flex; align-items: center; gap: 8px; color: #7b8ca6; font-size: calc(11px * var(--app-font-scale)); border: 0; background: none; padding: 0; cursor: pointer; }
.user-avatar { height: 27px; width: 27px; background: #edf2fd; color: #7393cf; display: grid; place-items: center; border-radius: 8px; font-size: calc(11px * var(--app-font-scale)); font-weight: 600; }
.user-info > .el-icon { font-size: calc(10px * var(--app-font-scale)); color: #a3afc1; margin-left: 2px; }
.username { max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.main-content { background: var(--app-background); min-height: 0; padding: 24px 26px 32px; overflow: auto; }
.sidebar-overlay { display: none; }
@media (max-width: 900px) {
  .sidebar { position: fixed; top: 0; left: 0; bottom: 0; transform: translateX(-100%); box-shadow: 12px 0 40px #24385517; z-index: 1002; }
  .sidebar.mobile-open { transform: translateX(0); }
  .sidebar-overlay { display: block; position: fixed; inset: 0; z-index: 1001; background: #1c2b4555; border: 0; }
  .header { padding: 12px 18px; }
  .main-content { padding: 20px 18px 28px; }
  .header-right { gap: 12px; }
}
@media (max-width: 520px) {
  .breadcrumb-root, .breadcrumb-separator, .display-button small, .username { display: none; }
  .main-content { padding: 17px 12px 24px; }
  .header { padding-left: 12px; padding-right: 15px; }
}
</style>
