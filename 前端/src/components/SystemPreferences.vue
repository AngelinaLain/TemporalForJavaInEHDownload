<template>
  <el-drawer :model-value="modelValue" title="个人设置" size="min(480px, 94vw)" class="system-preferences" :close-on-click-modal="!submitting" :close-on-press-escape="!submitting" :show-close="!submitting" @update:model-value="emit('update:modelValue', $event)" @closed="resetPasswordForm">
    <div class="settings-tabs" role="tablist" aria-label="个人设置">
      <button role="tab" :aria-selected="tab === 'appearance'" :class="{ active: tab === 'appearance' }" :disabled="submitting" @click="tab = 'appearance'"><el-icon><Setting /></el-icon>阅读与显示</button>
      <button role="tab" :aria-selected="tab === 'password'" :class="{ active: tab === 'password' }" :disabled="submitting" @click="tab = 'password'"><el-icon><Lock /></el-icon>修改密码</button>
    </div>

    <section v-if="tab === 'appearance'" class="settings-section">
      <p class="settings-kicker">APPEARANCE</p>
      <h2>选择适合你的字体大小</h2>
      <p class="settings-description">即时应用于本系统的文字和控件，偏好保存在当前浏览器。嵌入的监控页面保留各自字号。</p>
      <div class="font-presets" role="group" aria-label="字体大小">
        <button v-for="preset in FONT_PRESETS" :key="preset.value" :class="{ active: preferences.fontScale === preset.value }" :aria-pressed="preferences.fontScale === preset.value" @click="preferences.setFontScale(preset.value)">
          <span :style="{ fontSize: (15 * preset.value) + 'px' }">Aa</span><strong>{{ preset.label }}</strong><small>{{ preset.description }}</small><el-icon v-if="preferences.fontScale === preset.value"><CircleCheckFilled /></el-icon>
        </button>
      </div>
      <div class="reading-preview">
        <span>阅读效果预览</span><h3>在细节之间，找到想看的内容</h3><p>画廊标题、筛选条件和信息卡片会同步调整。你可以随时切换，不会改变当前检索条件。</p><div><span class="preview-tag">合集</span><span>143 页</span><span>评分 4.75</span></div>
      </div>
      <div class="settings-bottom"><span>当前 {{ Math.round(preferences.fontScale * 100) }}%</span><button class="text-button" @click="preferences.setFontScale(1)">恢复标准大小</button></div>
    </section>

    <section v-else class="settings-section">
      <p class="settings-kicker">ACCOUNT SECURITY</p>
      <h2>修改登录密码</h2>
      <p class="settings-description">修改后需要重新登录，其他已登录的会话也会失效。</p>
      <form class="password-form" @submit.prevent="changePassword">
        <label><span>当前密码</span><el-input v-model="passwordForm.currentPassword" name="current-password" type="password" show-password autocomplete="current-password" placeholder="请输入当前密码" :maxlength="256" :disabled="submitting" aria-label="当前密码" /></label>
        <label><span>新密码</span><el-input v-model="passwordForm.newPassword" name="new-password" type="password" show-password autocomplete="new-password" placeholder="至少 8 个字符" :maxlength="72" :disabled="submitting" aria-label="新密码" /><small>至少 8 个字符，UTF-8 编码不超过 72 字节。</small></label>
        <label><span>确认新密码</span><el-input v-model="passwordForm.confirmPassword" name="confirm-password" type="password" show-password autocomplete="new-password" placeholder="再次输入新密码" :maxlength="72" :disabled="submitting" aria-label="确认新密码" /></label>
        <p v-if="passwordError" class="password-error" role="alert"><el-icon><WarningFilled /></el-icon>{{ passwordError }}</p>
        <el-button type="primary" native-type="submit" :loading="submitting" class="password-submit">保存新密码</el-button>
      </form>
    </section>
  </el-drawer>
</template>

<script setup>
// 提供字号切换与修改密码；密码只保存在当前表单，成功后要求重新登录。
import { onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleCheckFilled, Lock, Setting, WarningFilled } from '@element-plus/icons-vue'
import api from '../api'
import { FONT_PRESETS, useUiPreferences } from '../stores/uiPreferences'

const props = defineProps({ modelValue: Boolean, initialTab: { type: String, default: 'appearance' } })
const emit = defineEmits(['update:modelValue'])
const preferences = useUiPreferences(), router = useRouter()
const tab = ref('appearance'), submitting = ref(false), passwordError = ref('')
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
let passwordController

// 清空表单中的密码和错误提示，避免关闭设置后仍保留敏感输入。
function resetPasswordForm() {
  Object.assign(passwordForm, { currentPassword: '', newPassword: '', confirmPassword: '' })
  passwordError.value = ''
}
// 提前检查长度、编码字节数及两次输入，减少无效改密请求。
function validatePassword() {
  if (!passwordForm.currentPassword) return '请输入当前密码'
  if ([...passwordForm.newPassword].length < 8) return '新密码至少需要 8 个字符'
  if (new TextEncoder().encode(passwordForm.newPassword).length > 72) return '新密码的 UTF-8 长度不能超过 72 字节'
  if (passwordForm.newPassword !== passwordForm.confirmPassword) return '两次输入的新密码不一致'
  if (passwordForm.newPassword === passwordForm.currentPassword) return '新密码不能与当前密码相同'
  return ''
}
// 防止重复提交；成功后清除本地凭据并重新登录，失败则留在当前表单显示原因。
async function changePassword() {
  if (submitting.value) return
  passwordError.value = validatePassword()
  if (passwordError.value) return
  submitting.value = true
  const request = new AbortController()
  passwordController = request
  try {
    const response = await api.post('/auth/change-password', { ...passwordForm }, { signal: request.signal, silent: true })
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    resetPasswordForm()
    emit('update:modelValue', false)
    ElMessage.success(response.data?.message || '密码已修改，请重新登录')
    await router.replace('/login')
  } catch (error) {
    if (error.code !== 'ERR_CANCELED') passwordError.value = error.response?.data?.msg || error.message || '密码修改失败，请稍后重试'
  } finally {
    if (passwordController === request) submitting.value = false
  }
}
watch(() => [props.modelValue, props.initialTab], ([visible, requested]) => {
  if (visible) { tab.value = requested === 'password' ? 'password' : 'appearance'; resetPasswordForm() }
})
watch(tab, () => { if (!submitting.value) resetPasswordForm() })
onUnmounted(() => { passwordController?.abort(); resetPasswordForm() })
</script>

<style scoped>
.settings-tabs { display: flex; padding: 4px; border-radius: 9px; background: var(--app-surface-muted); gap: 4px; margin-bottom: 27px; }
.settings-tabs button { flex: 1; border: 0; border-radius: 6px; padding: 10px 8px; display: flex; align-items: center; justify-content: center; gap: 7px; color: var(--app-text-muted); background: transparent; font-size: calc(12px * var(--app-font-scale)); cursor: pointer; }
.settings-tabs button.active { background: #fff; color: var(--app-accent); box-shadow: 0 1px 5px #20304b0c; font-weight: 600; }
.settings-kicker { color: var(--app-accent); font-size: calc(10px * var(--app-font-scale)); letter-spacing: .13em; font-weight: 700; margin: 0 0 9px; }
.settings-section h2 { font-size: calc(21px * var(--app-font-scale)); color: var(--app-text); line-height: 1.45; margin: 0; }
.settings-description { font-size: calc(12px * var(--app-font-scale)); color: var(--app-text-muted); line-height: 1.8; margin: 10px 0 23px; }
.font-presets { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px; }
.font-presets > button { display: flex; flex-direction: column; align-items: flex-start; position: relative; padding: 17px 15px; min-height: 121px; border: 1px solid var(--app-border); border-radius: 9px; background: #fff; cursor: pointer; color: var(--app-text); }
.font-presets > button.active { border-color: #86a2ed; background: #f6f8ff; box-shadow: 0 0 0 1px #9cb4f22b; }
.font-presets > button > span { color: #6e86b4; line-height: 1.25; height: 30px; }
.font-presets strong { margin-top: 7px; font-size: calc(12px * var(--app-font-scale)); font-weight: 600; }
.font-presets small { margin-top: 6px; color: var(--app-text-muted); font-size: calc(10px * var(--app-font-scale)); }
.font-presets .el-icon { position: absolute; top: 14px; right: 13px; color: var(--app-accent); }
.reading-preview { margin-top: 25px; border-radius: 10px; padding: 18px; background: var(--app-surface-muted); border: 1px solid #eaf0f8; }
.reading-preview > span { font-size: calc(10px * var(--app-font-scale)); color: var(--app-text-muted); }
.reading-preview h3 { font-size: calc(16px * var(--app-font-scale)); margin: 11px 0 8px; color: var(--app-text); line-height: 1.5; }
.reading-preview p { color: #71829b; font-size: calc(12px * var(--app-font-scale)); line-height: 1.8; margin: 0; }
.reading-preview > div { display: flex; gap: 13px; align-items: center; font-size: calc(10px * var(--app-font-scale)); color: var(--app-text-muted); margin-top: 15px; }
.preview-tag { background: #e8eeff; border-radius: 4px; color: #5f7dc2; padding: 3px 7px; }
.settings-bottom { display: flex; justify-content: space-between; gap: 12px; margin-top: 20px; font-size: calc(11px * var(--app-font-scale)); color: var(--app-text-muted); }
.text-button { background: transparent; border: 0; padding: 0; color: var(--app-accent); font: inherit; cursor: pointer; }
.password-form { display: flex; flex-direction: column; gap: 21px; margin-top: 29px; }
.password-form label { display: flex; flex-direction: column; gap: 8px; }
.password-form label > span { color: #60718c; font-size: calc(12px * var(--app-font-scale)); font-weight: 500; }
.password-form label small { color: var(--app-text-muted); font-size: calc(10px * var(--app-font-scale)); line-height: 1.7; }
.password-error { display: flex; align-items: flex-start; gap: 7px; color: #b45d55; background: #fff4f3; border-radius: 7px; padding: 10px 12px; font-size: calc(12px * var(--app-font-scale)); line-height: 1.7; margin: 0; }
.password-error .el-icon { margin-top: 4px; flex-shrink: 0; }
.password-submit { width: 100%; min-height: 38px; margin-top: 3px; }
button:focus-visible { outline: 3px solid #b5cafa; outline-offset: 3px; }
</style>
