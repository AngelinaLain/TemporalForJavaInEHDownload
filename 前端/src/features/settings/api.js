import api from '../../api'

// AI 配置的接口集中在此，表单校验、权限联动与提示仍由页面负责。
export const listProviders = () => api.get('/ai/config/providers')
export const createProvider = payload => api.post('/ai/config/providers', payload)
export const updateProvider = (id, payload) => api.put(`/ai/config/providers/${id}`, payload)
export const deleteProvider = id => api.delete(`/ai/config/providers/${id}`)
export const fetchProviderModels = id => api.post(`/ai/config/providers/${id}/models`)
export const listUseCases = () => api.get('/ai/config/use-cases')
export const getDefaultPrompt = useCase => api.get(`/ai/config/use-cases/${useCase}/default-prompt`)
export const updateUseCase = (useCase, payload) => api.put(`/ai/config/use-cases/${useCase}`, payload)
