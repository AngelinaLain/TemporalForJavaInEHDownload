<template>
  <div class="login-container">
    <div class="login-brand"><span><el-icon><Collection /></el-icon></span><strong>Gallery Import</strong></div>
    <main class="login-card">
      <div class="login-header"><p>WELCOME BACK</p><h1>登录你的画廊工作台</h1><span>管理内容、发现关联，继续你的资料整理。</span></div>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="handleLogin">
        <el-form-item label="用户名" prop="username"><el-input v-model="form.username" name="username" placeholder="输入管理员用户名" autocomplete="username" :prefix-icon="User" size="large" :disabled="loading" aria-label="用户名" /></el-form-item>
        <el-form-item label="密码" prop="password"><el-input v-model="form.password" name="password" placeholder="输入登录密码" type="password" show-password autocomplete="current-password" :prefix-icon="Lock" size="large" :disabled="loading" aria-label="密码" /></el-form-item>
        <el-button type="primary" :loading="loading" native-type="submit" class="login-submit" size="large">登录工作台<el-icon v-if="!loading"><ArrowRight /></el-icon></el-button>
      </el-form>
      <div class="login-note"><el-icon><Lock /></el-icon><span>请使用管理员账户登录</span></div>
    </main>
    <p class="login-footer">GALLERY IMPORT · 内容与发现，从这里开始</p>
  </div>
</template>

<script setup>
// 校验登录表单并取得登录凭据，成功后进入管理系统。
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Collection, Lock, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const router = useRouter(), formRef = ref(), loading = ref(false)
const form = reactive({ username: '', password: '' })
const rules = { username: [{ required: true, message: '请输入用户名', trigger: 'blur' }], password: [{ required: true, message: '请输入密码', trigger: 'blur' }] }
// 先校验必填项，再保存服务端签发的凭据并进入首页；请求期间禁止重复提交。
async function handleLogin() {
  if (loading.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const response = await api.post('/auth/login', form)
    localStorage.setItem('token', response.data.token)
    localStorage.setItem('username', response.data.username)
    form.password = ''
    ElMessage.success('登录成功')
    router.replace('/dashboard')
  } catch {
    // 表单与登录错误已由组件或统一接口处理显示，这里只恢复按钮状态。
  } finally { loading.value = false }
}
</script>

<style scoped>
.login-container { box-sizing: border-box; min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 45px 20px; background: radial-gradient(ellipse at 22% 12%, #e6edfe, transparent 40%), radial-gradient(ellipse at 88% 80%, #eaf2f8, transparent 40%), #f4f7fc; }
.login-brand { display: flex; align-items: center; gap: 9px; color: #405777; margin-bottom: 28px; }
.login-brand > span { background: #e5edff; color: #6888d9; display: grid; place-items: center; width: 31px; height: 31px; border-radius: 9px; font-size: calc(18px * var(--app-font-scale)); }
.login-brand strong { font-size: calc(16px * var(--app-font-scale)); letter-spacing: -.02em; font-weight: 650; }
.login-card { width: min(420px, 100%); box-sizing: border-box; border: 1px solid #e0e7f2; border-radius: 15px; padding: 34px 35px 25px; background: #fff; box-shadow: 0 12px 40px #253d6508; }
.login-header { margin-bottom: 29px; }
.login-header p { font-size: calc(10px * var(--app-font-scale)); letter-spacing: .14em; color: #7a95d9; font-weight: 700; margin: 0 0 10px; }
.login-header h1 { color: #2b3d57; font-size: calc(23px * var(--app-font-scale)); margin: 0; line-height: 1.45; font-weight: 650; letter-spacing: -.02em; }
.login-header > span { font-size: calc(11px * var(--app-font-scale)); color: #99a6b9; display: block; line-height: 1.8; margin-top: 9px; }
.login-submit { width: 100%; margin-top: 8px; justify-content: space-between; }
.login-submit .el-icon { margin-left: auto; }
.login-note { display: flex; justify-content: center; align-items: center; gap: 6px; color: #a3b0c2; font-size: calc(10px * var(--app-font-scale)); border-top: 1px solid #edf1f7; margin-top: 25px; padding-top: 18px; }
.login-footer { font-size: calc(9px * var(--app-font-scale)); color: #a7b3c5; letter-spacing: .04em; margin: 25px 0 0; text-align: center; }
@media (max-width: 480px) { .login-card { padding: 28px 23px 22px; }.login-header h1 { font-size: calc(21px * var(--app-font-scale)); } }
</style>
