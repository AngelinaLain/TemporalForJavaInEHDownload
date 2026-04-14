<template>
  <div class="operations-page">
    <!-- 抓取工作流 -->
    <el-card shadow="hover" class="op-card">
      <template #header>
        <div class="card-header">
          <el-icon><Search /></el-icon>
          <span>启动抓取工作流</span>
        </div>
      </template>
      <el-form :model="searchForm" label-width="140px" @submit.prevent="startWorkflow">
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="关键词">
              <el-input
                v-model="searchForm.keyword"
                placeholder="可输入普通关键词，支持与标签组合搜索"
                clearable
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="搜索标签" required>
              <el-select-v2
                v-model="searchTags"
                :options="tagOptions"
                multiple
                filterable
                remote
                reserve-keyword
                :remote-method="searchTagMethod"
                placeholder="输入中/英文关键词搜索标签，支持多选"
                style="width: 100%"
                popper-class="tag-suggest-popper"
                :item-height="36"
                :height="350"
                @visible-change="handleTagDropdownVisible"
              >
                <template #default="{ item }">
                  <div class="tag-suggest-row">
                    <span>
                      <span class="tag-ns" :style="{ color: item.nsColor }">{{ item.ns }}：</span>
                      <span v-html="highlightMatch(item.translated, tagSearchQuery)"></span>
                    </span>
                    <span class="tag-original">- {{ item.original }}</span>
                  </div>
                </template>
              </el-select-v2>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="24">
            <el-form-item label="画廊类型">
              <el-checkbox-group v-model="selectedGalleryCats">
                <el-checkbox
                  v-for="cat in galleryCategories"
                  :key="cat.code"
                  :value="cat.code"
                >
                  {{ cat.label }}
                </el-checkbox>
              </el-checkbox-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="语言">
              <el-select v-model="searchForm.language" placeholder="不限" clearable style="width: 100%">
                <el-option label="中文" value="chinese" />
                <el-option label="日文" value="japanese" />
                <el-option label="英文" value="english" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="最低星级">
              <el-rate v-model="searchForm.minimumRating" :max="5" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="最少页数">
              <el-input-number v-model="searchForm.pageAtLeast" :min="0" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="最多页数">
              <el-input-number v-model="searchForm.pageAtMost" :min="0" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="搜索已删除画廊">
              <el-switch v-model="searchForm.searchExpungedGalleries" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="仅显示有种子">
              <el-switch v-model="searchForm.showOnlyWithTorrents" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="禁用语言过滤">
              <el-switch v-model="searchForm.disableLanguageFilter" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="禁用上传者过滤">
              <el-switch v-model="searchForm.disableUploaderFilter" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="禁用标签过滤">
              <el-switch v-model="searchForm.disableTagsFilter" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item>
          <el-button type="primary" :loading="loading.start" @click="startWorkflow">
            <el-icon><VideoPlay /></el-icon> 启动工作流
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 快捷操作 -->
    <el-card shadow="hover" class="op-card">
      <template #header>
        <div class="card-header">
          <el-icon><Setting /></el-icon>
          <span>快捷操作</span>
        </div>
      </template>
      <el-row :gutter="16">
        <el-col :xs="24" :sm="12" :md="8" :lg="6" v-for="action in quickActions" :key="action.key">
          <el-card shadow="hover" class="action-card" @click="action.handler">
            <div class="action-content" :class="{ 'is-loading': loading[action.key] }">
              <el-icon :size="32" :color="action.color"><component :is="action.icon" /></el-icon>
              <div class="action-label">{{ action.label }}</div>
              <div class="action-desc">{{ action.desc }}</div>
              <el-button :type="action.btnType" :loading="loading[action.key]"
                         size="small" style="margin-top: 12px" @click.stop="action.handler">
                执行
              </el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <!-- 合集构建 -->
    <el-card shadow="hover" class="op-card">
      <template #header>
        <div class="card-header">
          <el-icon><Collection /></el-icon>
          <span>构建 Komga 合集</span>
        </div>
      </template>
      <el-form :model="collectionForm" label-width="120px" @submit.prevent="buildCollection">
        <el-form-item label="合集名称" required>
          <el-input v-model="collectionForm.collectionName" placeholder="例如：纯爱 / Vanilla" />
        </el-form-item>
        <el-form-item label="标签列表" required>
          <div style="width: 100%">
            <el-tag v-for="tag in collectionForm.tags" :key="tag" closable size="large"
                    class="tag-item" @close="removeTag(tag)">
              {{ tagStore.translate(tag) }}
            </el-tag>
            <el-autocomplete
              v-if="tagInputVisible"
              ref="tagInputRef"
              v-model="tagInputValue"
              :fetch-suggestions="fetchCollectionTagSuggestions"
              size="small"
              style="width: 260px"
              placeholder="输入标签（中/英文联想）"
              :trigger-on-focus="false"
              :debounce="300"
              @select="handleCollectionTagSelect"
              @keyup.enter="addTag"
              @blur="addTag"
            >
              <template #default="{ item }">
                  <div class="tag-suggest-row">
                    <span>
                      <span class="tag-ns" :style="{ color: item.nsColor }">{{ item.ns }}：</span>
                      <span v-html="highlightMatch(item.translated, item.query)"></span>
                    </span>
                    <span class="tag-original">- {{ item.original }}</span>
                  </div>
                </template>
            </el-autocomplete>
            <el-button v-else size="small" @click="showTagInput">+ 新标签</el-button>
          </div>
        </el-form-item>
        <el-form-item label="匹配模式">
          <el-radio-group v-model="collectionForm.matchAllTags">
            <el-radio :value="false">包含任意一个标签</el-radio>
            <el-radio :value="true">包含所有标签</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button type="success" :loading="loading.collection" @click="buildCollection">
            <el-icon><FolderAdd /></el-icon> 构建合集
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 操作日志 -->
    <el-card shadow="hover" class="op-card" v-if="logs.length">
      <template #header>
        <div class="card-header">
          <el-icon><Document /></el-icon>
          <span>操作记录</span>
          <el-button size="small" text @click="logs = []" style="margin-left: auto">清空</el-button>
        </div>
      </template>
      <el-timeline>
        <el-timeline-item v-for="(log, i) in logs" :key="i"
                          :type="log.success ? 'success' : 'danger'"
                          :timestamp="log.time" placement="top">
          <span class="log-text">{{ log.message }}</span>
          <div v-if="log.detail" class="log-detail">{{ log.detail }}</div>
        </el-timeline-item>
      </el-timeline>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick, markRaw } from 'vue'
import {
  Search, VideoPlay, Setting, Collection, Document, FolderAdd,
  RefreshRight, Message, Promotion, EditPen, Connection, DataBoard
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { useTagStore } from '../stores/tagStore'

const tagStore = useTagStore()
tagStore.loadTranslations()

const loading = reactive({
  start: false,
  retryFailed: false,
  testEmail: false,
  syncTags: false,
  refreshMeta: false,
  updateSize: false,
  collection: false,
  refreshTranslation: false
})

const logs = ref([])

const addLog = (message, success, detail) => {
  logs.value.unshift({
    message,
    success,
    detail: detail || '',
    time: new Date().toLocaleString('zh-CN')
  })
}

// ===== 搜索工作流 =====
const searchForm = reactive({
  keyword: '',
  language: null,
  minimumRating: 1,
  pageAtLeast: null,
  pageAtMost: null,
  searchExpungedGalleries: false,
  showOnlyWithTorrents: false,
  disableLanguageFilter: false,
  disableUploaderFilter: false,
  disableTagsFilter: false
})

const galleryCategories = [
  { code: 1, label: 'Misc / 其他' },
  { code: 2, label: 'Doujinshi / 同人志' },
  { code: 4, label: 'Manga / 漫画' },
  { code: 8, label: 'Artist CG / 艺术CG' },
  { code: 16, label: 'Game CG / 游戏CG' },
  { code: 32, label: 'Image Set / 图像集' },
  { code: 64, label: 'Cosplay' },
  { code: 128, label: 'Asian Porn / 亚洲成人视频' },
  { code: 256, label: 'Non-H / 无码作品' },
  { code: 512, label: 'Western / 西方作品' }
]

const selectedGalleryCats = ref(galleryCategories.map(cat => cat.code))

const calcFilterCats = () => {
  const selectedSet = new Set(selectedGalleryCats.value)
  return galleryCategories
    .filter(cat => !selectedSet.has(cat.code))
    .reduce((sum, cat) => sum + cat.code, 0)
}

const escapeHtml = (str) => str.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))

const highlightMatch = (text, query) => {
  if (!query) return escapeHtml(text)
  const lt = text.toLowerCase()
  const lq = query.toLowerCase()
  const idx = lt.indexOf(lq)
  if (idx === -1) return escapeHtml(text)
  return escapeHtml(text.substring(0, idx)) + '<b>' + escapeHtml(text.substring(idx, idx + query.length)) + '</b>' + escapeHtml(text.substring(idx + query.length))
}

const buildSuggestionItem = (key, val, query) => {
  const colonIdx = key.indexOf(':')
  const ns = colonIdx > -1 ? key.substring(0, colonIdx) : 'other'
  const original = colonIdx > -1 ? key.substring(colonIdx + 1) : key
  const nsInfo = tagStore.getNsInfo(ns)
  return { value: key, label: val, ns: nsInfo.label, nsColor: nsInfo.color, translated: val, original, query }
}

const searchTags = ref([])
const tagOptions = ref([])
const tagSearchQuery = ref('')
const tagLabelCache = reactive({})

const getSelectedOptions = () => {
  return searchTags.value.map(v => {
    const label = tagLabelCache[v] || tagStore.translate(v) || v
    return buildSuggestionItem(v, label, '')
  })
}

const searchTagMethod = (query) => {
  tagSearchQuery.value = query
  if (!query) {
    tagOptions.value = getSelectedOptions()
    return
  }
  const lw = query.toLowerCase()
  const map = tagStore.translations
  const results = []
  for (const [key, val] of Object.entries(map)) {
    if (key.toLowerCase().includes(lw) || val.toLowerCase().includes(lw)) {
      tagLabelCache[key] = val
      results.push(buildSuggestionItem(key, val, query))
    }
  }
  const selectedExtras = getSelectedOptions().filter(o => !results.some(r => r.value === o.value))
  tagOptions.value = [...selectedExtras, ...results]
}

const handleTagDropdownVisible = (visible) => {
  if (visible && !tagSearchQuery.value) {
    tagOptions.value = getSelectedOptions()
  }
}

const fetchCollectionTagSuggestions = (query, cb) => {
  if (!query || query.length < 1) return cb([])
  const lw = query.toLowerCase()
  const map = tagStore.translations
  const results = []
  for (const [key, val] of Object.entries(map)) {
    if (results.length >= 15) break
    if (key.toLowerCase().includes(lw) || val.toLowerCase().includes(lw)) {
      results.push(buildSuggestionItem(key, val, query))
    }
  }
  cb(results)
}

const handleCollectionTagSelect = (item) => {
  tagInputValue.value = item.value
  addTag()
}

const startWorkflow = async () => {
  const keywordText = searchForm.keyword.trim()
  const mergedKeyword = [...searchTags.value, keywordText].filter(Boolean).join(' ').trim()

  if (!mergedKeyword) {
    ElMessage.warning('请至少输入关键词或选择一个搜索标签')
    return
  }

  loading.start = true
  try {
    const payload = {
      ...searchForm,
      keyword: mergedKeyword,
      filterCats: calcFilterCats()
    }
    const res = await api.post('/temporal/eh/start', payload)
    addLog('抓取工作流已启动', true, `workflowId: ${res.data.workflowId}`)
    ElMessage.success('工作流已启动')
  } catch (e) {
    addLog('启动工作流失败', false, e.message)
  } finally {
    loading.start = false
  }
}

// ===== 快捷操作 =====
const quickActions = [
  {
    key: 'retryFailed', label: '重试失败下载', desc: '重试所有标记为"下载失败"的画廊',
    icon: markRaw(RefreshRight), color: '#E6A23C', btnType: 'warning',
    handler: () => execAction('retryFailed', '/temporal/eh/retry-failed', '重试失败下载')
  },
  {
    key: 'testEmail', label: '测试邮件发送', desc: '测试 Microsoft Graph API 邮件功能',
    icon: markRaw(Message), color: '#409EFF', btnType: 'primary',
    handler: () => execAction('testEmail', '/temporal/eh/test-email', '测试邮件')
  },
  {
    key: 'syncTags', label: '同步标签到 Komga', desc: '将数据库标签同步覆盖到 Komga',
    icon: markRaw(Promotion), color: '#67C23A', btnType: 'success',
    handler: () => execAction('syncTags', '/temporal/eh/sync-tags', '同步标签')
  },
  {
    key: 'refreshMeta', label: '批量刷新元数据', desc: '刷新已入库书籍的 Komga 元数据',
    icon: markRaw(EditPen), color: '#764ba2', btnType: 'primary',
    handler: () => execAction('refreshMeta', '/temporal/eh/batch-refresh-metadata', '批量刷新元数据')
  },
  {
    key: 'updateSize', label: '批量更新文件大小', desc: '补全缺失的画廊文件大小',
    icon: markRaw(DataBoard), color: '#F56C6C', btnType: 'danger',
    handler: () => execAction('updateSize', '/temporal/eh/batch-update-filesize', '批量更新文件大小')
  },
  {
    key: 'refreshTranslation', label: '刷新标签翻译缓存', desc: '重新拉取 EhTagTranslation 数据',
    icon: markRaw(Connection), color: '#8B5CF6', btnType: 'primary',
    handler: () => execAction('refreshTranslation', '/dashboard/tag-translations/refresh', '刷新标签翻译')
  }
]

const execAction = async (key, url, label) => {
  try {
    await ElMessageBox.confirm(`确定要执行「${label}」操作？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  loading[key] = true
  try {
    const res = await api.post(url)
    const msg = res.data?.message || res.data || '操作成功'
    addLog(`${label} 执行成功`, true, typeof msg === 'string' ? msg : JSON.stringify(msg))
    ElMessage.success(`${label} 执行成功`)
  } catch (e) {
    addLog(`${label} 执行失败`, false, e.message)
  } finally {
    loading[key] = false
  }
}

// ===== 合集构建 =====
const collectionForm = reactive({
  collectionName: '',
  tags: [],
  matchAllTags: false
})

const tagInputVisible = ref(false)
const tagInputValue = ref('')
const tagInputRef = ref()

const showTagInput = () => {
  tagInputVisible.value = true
  nextTick(() => tagInputRef.value?.focus())
}

const addTag = () => {
  const val = tagInputValue.value.trim()
  if (val && !collectionForm.tags.includes(val)) {
    collectionForm.tags.push(val)
  }
  tagInputVisible.value = false
  tagInputValue.value = ''
}

const removeTag = (tag) => {
  collectionForm.tags = collectionForm.tags.filter(t => t !== tag)
}

const buildCollection = async () => {
  if (!collectionForm.collectionName.trim()) {
    ElMessage.warning('请输入合集名称')
    return
  }
  if (!collectionForm.tags.length) {
    ElMessage.warning('请至少添加一个标签')
    return
  }
  loading.collection = true
  try {
    const res = await api.post('/temporal/eh/collections/build-by-tags', collectionForm)
    addLog('合集构建成功', true, res.data)
    ElMessage.success('合集构建成功')
  } catch (e) {
    addLog('合集构建失败', false, e.message)
  } finally {
    loading.collection = false
  }
}
</script>

<style scoped>
.operations-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.op-card :deep(.el-card__header) {
  padding: 14px 20px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 16px;
}

.action-card {
  margin-bottom: 16px;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
}

.action-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.action-content {
  text-align: center;
  padding: 8px 0;
}

.action-label {
  font-size: 15px;
  font-weight: 600;
  margin-top: 10px;
  color: #303133;
}

.action-desc {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.tag-item {
  margin: 4px 6px 4px 0;
}

.log-text {
  font-weight: 500;
}

.log-detail {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  word-break: break-all;
}
</style>

<style>
.tag-suggest-popper .el-autocomplete-suggestion li {
  padding: 6px 12px;
  line-height: 1.6;
}
.tag-suggest-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}
.tag-suggest-row .tag-ns {
  font-weight: 600;
  margin-right: 2px;
}
.tag-suggest-row .tag-original {
  color: #999;
  font-size: 12px;
  margin-left: 16px;
  white-space: nowrap;
}
</style>
