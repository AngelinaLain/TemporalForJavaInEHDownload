import api from '../../api'

// 页面保留筛选与取消请求的控制权；此处只定义画廊查询、建议和媒体索引接口。
export const searchGalleries = (params, options) => api.get('/dashboard/galleries', { ...options, params })
export const getGallerySuggestions = (params, options) => api.get('/dashboard/suggestions', { ...options, params })
export const getGalleryKomgaLink = gid => api.get('/dashboard/komga-link/' + gid)
export const getGalleryMedia = (gid, params, options) => api.get('/dashboard/gallery-media/' + gid, { ...options, params })
