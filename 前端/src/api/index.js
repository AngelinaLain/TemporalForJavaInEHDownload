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

function handleUnauthorized(config) {
  // A failed optional image must not terminate the user's current page/session.
  // Protected APIs still validate the token; foreground business requests retain normal re-login behavior.
  if (config?.preserveSession) return
  localStorage.removeItem('token')
  router.push('/login')
}

api.interceptors.response.use(
  response => {
    // Authenticated media is loaded as a Blob; do not treat image bytes as a Result envelope.
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
