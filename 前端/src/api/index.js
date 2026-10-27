/* 统一发送后端请求、附带登录凭据，并区分业务失败与可选图片加载失败。 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000
})

api.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 处理未登录响应：后台可选媒体保留会话，普通业务请求清除凭据并跳转登录。
function handleUnauthorized(config) {
  // 单张图片加载失败只影响图片显示，不清除整页的登录状态。
  // 服务端仍会校验凭据；普通业务请求收到未登录响应时继续要求重新登录。
  if (config?.preserveSession) return
  localStorage.removeItem('token')
  router.push('/login')
}

api.interceptors.response.use(
  response => {
    // 图片请求返回二进制内容，直接交给图片组件，不按普通业务响应解析。
    if (response.config.responseType === 'blob' || response.config.responseType === 'arraybuffer') {
      return response
    }
    const res = response.data
    if (res.code !== 200) {
      if (!response.config?.silent) ElMessage.error(res.msg || '请求失败')
      if (res.code === 401) {
        handleUnauthorized(response.config)
      }
      return Promise.reject(new Error(res.msg))
    }
    return res
  },
  error => {
    if (error.code === 'ERR_CANCELED') {
      return Promise.reject(error)
    }
    if (error.response?.status === 401) {
      handleUnauthorized(error.config)
    }
    if (!error.config?.silent) ElMessage.error(error.message || '网络错误')
    return Promise.reject(error)
  }
)

export default api
