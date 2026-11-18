import api from '../../api'

// 合集页面、RSS 目标选择与资料库筛选共用这些接口；可选请求配置原样交给全局客户端。
export const listCollections = options => api.get('/collections', options)
export const createCollection = payload => api.post('/collections', payload)
export const updateCollection = (id, payload) => api.put(`/collections/${id}`, payload)
export const deleteCollection = id => api.delete(`/collections/${id}`)
export const listCollectionItems = id => api.get(`/collections/${id}/items`)
export const addCollectionItems = (id, payload) => api.post(`/collections/${id}/items`, payload)
export const removeCollectionItem = (id, gid) => api.delete(`/collections/${id}/items/${gid}`)
export const getCollectionSuggestions = (id, params) => api.get(`/collections/${id}/suggestions`, { params })
export const searchCollectionGalleries = params => api.get('/collections/gallery-search', { params })
export const getSeriesSyncStatus = () => api.get('/collections/komga-series-sync/status')
export const startSeriesSync = () => api.post('/collections/komga-series-sync')
