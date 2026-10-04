<template>
  <div class="ai-settings">
    <el-alert
      title="默认仅在本地生成视觉向量。只有选择“视觉 LLM”并同时开启供应商与任务的图片权限时，图片才会发送给模型服务。"
      type="info" :closable="false" show-icon class="notice"
    />

    <el-card shadow="hover">
      <template #header>
        <div class="header-row">
          <div><h3>模型供应商</h3><p>支持 Ollama、LM Studio、LocalAI、vLLM、OpenAI 等 OpenAI-compatible 接口。</p></div>
          <el-button type="primary" @click="openProvider()">新增供应商</el-button>
        </div>
      </template>
      <el-table :data="providers" v-loading="loading">
        <el-table-column prop="name" label="名称" min-width="130" />
        <el-table-column label="范围" width="90">
          <template #default="{ row }"><el-tag :type="row.scope === 'LOCAL' ? 'success' : 'warning'">{{ row.scope === 'LOCAL' ? '局域网' : '远程' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="Base URL" min-width="220" show-overflow-tooltip />
        <el-table-column prop="defaultModel" label="默认模型" min-width="140" />
        <el-table-column label="权限" min-width="180">
          <template #default="{ row }">
            <el-tag size="small" :type="row.allowTextMetadata ? 'success' : 'info'">文本</el-tag>
            <el-tag size="small" :type="row.allowVisualInput ? 'danger' : 'info'" class="tag-gap">图片</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="密钥" width="90">
          <template #default="{ row }">{{ row.hasApiKey ? '已配置' : '无' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="255" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :loading="testingId === row.id" @click="testProvider(row)">连接/模型</el-button>
            <el-button size="small" @click="openProvider(row)">编辑</el-button>
            <el-button size="small" type="danger" plain @click="removeProvider(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="hover" class="use-case-card">
      <template #header><h3>用例配置</h3></template>
      <el-tabs v-model="activeUseCase">
        <el-tab-pane v-for="item in useCases" :key="item.useCase" :name="item.useCase" :label="useCaseLabel(item.useCase)">
          <el-form label-width="150px" class="use-case-form">
            <el-form-item label="分析模式" v-if="item.useCase === 'VISUAL_DUPLICATE_REVIEW'">
              <el-select v-model="item.analysisMode" @change="modeChanged(item)">
                <el-option label="本地向量（不调用 LLM）" value="EMBEDDING_ONLY" />
                <el-option label="向量统计 + 文本 LLM" value="EMBEDDING_TEXT_LLM" />
                <el-option label="视觉 LLM 复核" value="VISION_LLM" />
              </el-select>
            </el-form-item>
            <el-form-item label="供应商" v-if="item.analysisMode !== 'EMBEDDING_ONLY'">
              <el-select v-model="item.providerId" clearable filterable @change="providerChanged(item)">
                <el-option v-for="provider in enabledProviders" :key="provider.id" :label="provider.name" :value="provider.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="模型" v-if="item.analysisMode !== 'EMBEDDING_ONLY'">
              <el-select v-model="item.model" filterable allow-create default-first-option>
                <el-option v-for="model in modelsByProvider[item.providerId] || []" :key="model" :label="model" :value="model" />
              </el-select>
              <el-button class="inline-button" :disabled="!item.providerId" @click="loadModels(item.providerId)">刷新模型</el-button>
            </el-form-item>
            <el-form-item label="允许发送图片" v-if="item.useCase === 'VISUAL_DUPLICATE_REVIEW'">
              <el-switch v-model="item.allowImageTransmission" :disabled="item.analysisMode !== 'VISION_LLM' || !selectedProvider(item)?.allowVisualInput" />
              <span class="field-help">还需要所选供应商开启图片权限；线上供应商建议保持关闭。</span>
            </el-form-item>
            <el-form-item label="携带标题/标签" v-if="item.useCase === 'VISUAL_DUPLICATE_REVIEW'">
              <el-switch v-model="item.includeMetadata" :disabled="!selectedProvider(item)?.allowTextMetadata && item.analysisMode !== 'EMBEDDING_ONLY'" />
            </el-form-item>
            <el-form-item label="抽样图片数" v-if="item.analysisMode === 'VISION_LLM'">
              <el-input-number v-model="item.samplePageCount" :min="1" :max="8" />
            </el-form-item>
            <template v-if="item.useCase === 'VISUAL_DUPLICATE_REVIEW'">
              <el-form-item label="模糊区间下限"><el-input-number v-model="item.ambiguousMinSimilarity" :min="0" :max="1" :step="0.01" :precision="2" /></el-form-item>
              <el-form-item label="高相似阈值"><el-input-number v-model="item.highSimilarity" :min="0" :max="1" :step="0.01" :precision="2" /></el-form-item>
            </template>
            <el-form-item label="提示词">
              <div class="prompt-field">
                <el-input v-model="item.prompt" type="textarea" :rows="7" maxlength="8000" show-word-limit />
                <el-button @click="restorePrompt(item)">恢复默认</el-button>
                <span class="field-help">当前版本：{{ item.promptVersion || 1 }}；保存修改会生成新版本。</span>
              </div>
            </el-form-item>
            <el-form-item><el-button type="primary" @click="saveUseCase(item)">保存用例配置</el-button></el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="providerDialog" :title="providerForm.id ? '编辑供应商' : '新增供应商'" width="620px">
      <el-form label-width="130px">
        <el-form-item label="名称"><el-input v-model="providerForm.name" /></el-form-item>
        <el-form-item label="范围">
          <el-radio-group v-model="providerForm.scope"><el-radio-button value="LOCAL">局域网</el-radio-button><el-radio-button value="REMOTE">远程</el-radio-button></el-radio-group>
        </el-form-item>
        <el-form-item label="Base URL"><el-input v-model="providerForm.baseUrl" placeholder="http://ollama:11434 或 https://api.openai.com" /></el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="providerForm.apiKey" type="password" show-password :placeholder="providerForm.hasApiKey ? '留空表示保持原密钥' : '本地服务不需要时可留空'" />
        </el-form-item>
        <el-form-item label="默认模型"><el-input v-model="providerForm.defaultModel" /></el-form-item>
        <el-form-item label="允许文本元数据"><el-switch v-model="providerForm.allowTextMetadata" /></el-form-item>
        <el-form-item label="允许视觉输入"><el-switch v-model="providerForm.allowVisualInput" /><span class="danger-help">开启后仍需在具体任务中再次授权。</span></el-form-item>
        <el-form-item label="超时（秒）"><el-input-number v-model="providerForm.requestTimeoutSeconds" :min="5" :max="600" /></el-form-item>
        <el-form-item label="最大并发"><el-input-number v-model="providerForm.maxConcurrency" :min="1" :max="32" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="providerForm.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="providerDialog = false">取消</el-button><el-button type="primary" @click="saveProvider">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const loading = ref(false)
const testingId = ref(null)
const providers = ref([])
const useCases = ref([])
const activeUseCase = ref('VISUAL_DUPLICATE_REVIEW')
const modelsByProvider = reactive({})
const providerDialog = ref(false)
const emptyProvider = () => ({ id: null, name: '', scope: 'LOCAL', baseUrl: '', apiKey: '', defaultModel: '', allowTextMetadata: true, allowVisualInput: false, requestTimeoutSeconds: 120, maxConcurrency: 2, enabled: true, hasApiKey: false })
const providerForm = reactive(emptyProvider())
const enabledProviders = computed(() => providers.value.filter(item => item.enabled))

const load = async () => {
  loading.value = true
  try {
    const [providerResponse, useCaseResponse] = await Promise.all([api.get('/ai/config/providers'), api.get('/ai/config/use-cases')])
    providers.value = providerResponse.data || []
    useCases.value = useCaseResponse.data || []
  } finally { loading.value = false }
}
const openProvider = row => { Object.assign(providerForm, emptyProvider(), row || {}, { apiKey: '' }); providerDialog.value = true }
const saveProvider = async () => {
  const payload = { ...providerForm }; delete payload.id; delete payload.hasApiKey; delete payload.protocol
  if (providerForm.id) await api.put(`/ai/config/providers/${providerForm.id}`, payload)
  else await api.post('/ai/config/providers', payload)
  providerDialog.value = false; ElMessage.success('供应商配置已保存'); await load()
}
const removeProvider = async row => {
  await ElMessageBox.confirm(`删除供应商“${row.name}”？关联用例将取消选择该供应商。`, '确认删除', { type: 'warning' })
  await api.delete(`/ai/config/providers/${row.id}`); ElMessage.success('已删除'); await load()
}
const loadModels = async id => {
  if (!id) return
  testingId.value = id
  try {
    const response = await api.post(`/ai/config/providers/${id}/models`)
    modelsByProvider[id] = response.data?.models || []
    ElMessage.success(`连接成功，发现 ${modelsByProvider[id].length} 个模型`)
  } finally { testingId.value = null }
}
const testProvider = row => loadModels(row.id)
const selectedProvider = item => providers.value.find(provider => provider.id === item.providerId)
const providerChanged = item => { item.model = selectedProvider(item)?.defaultModel || ''; item.allowImageTransmission = item.allowImageTransmission && !!selectedProvider(item)?.allowVisualInput }
const modeChanged = item => { if (item.analysisMode !== 'VISION_LLM') item.allowImageTransmission = false }
const restorePrompt = async item => { const response = await api.get(`/ai/config/use-cases/${item.useCase}/default-prompt`); item.prompt = response.data?.prompt || '' }
const saveUseCase = async item => { await api.put(`/ai/config/use-cases/${item.useCase}`, item); ElMessage.success('用例配置已保存'); await load() }
const useCaseLabel = value => ({ SUMMARY: '画廊摘要', TAG_TRANSLATION: '标签翻译', VISUAL_DUPLICATE_REVIEW: '视觉重复复核' }[value] || value)
onMounted(load)
</script>

<style scoped>
.ai-settings { max-width: 1280px; margin: 0 auto; }
.notice, .use-case-card { margin-bottom: 16px; }
.header-row { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.header-row h3, .use-case-card h3 { margin: 0; }
.header-row p { margin: 7px 0 0; color: #606266; font-size: 14px; }
.tag-gap { margin-left: 6px; }
.use-case-card { margin-top: 16px; }
.use-case-form { max-width: 850px; padding-top: 12px; }
.use-case-form .el-select { width: 360px; }
.inline-button { margin-left: 10px; }
.prompt-field { width: 100%; display: grid; gap: 8px; }
.field-help { margin-left: 12px; color: #909399; font-size: 13px; }
.danger-help { margin-left: 12px; color: #e6a23c; font-size: 13px; }
@media (max-width: 700px) { .header-row { align-items: flex-start; flex-direction: column; } .use-case-form .el-select { width: 100%; } }
</style>
