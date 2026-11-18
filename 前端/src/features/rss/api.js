import api from '../../api'

// RSS 接口共用全局客户端，保留 Result 响应、错误处理和预览所需的较长超时。
export const listSubscriptions = () => api.get('/rss/subscriptions')
export const createSubscription = payload => api.post('/rss/subscriptions', payload)
export const updateSubscription = (id, payload) => api.put(`/rss/subscriptions/${id}`, payload)
export const deleteSubscription = id => api.delete(`/rss/subscriptions/${id}`)
export const previewSubscription = payload => api.post('/rss/subscriptions/preview', payload, { timeout: 100000 })
export const runSubscriptionAction = (id, operation, body) => api.post(`/rss/subscriptions/${id}/${operation}`, body)
export const listSubscriptionItems = (id, params) => api.get(`/rss/subscriptions/${id}/items`, { params })
export const listSubscriptionRuns = id => api.get(`/rss/subscriptions/${id}/runs`)
