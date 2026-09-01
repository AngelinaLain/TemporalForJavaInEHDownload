<template>
  <div class="galleries-page">
    <el-card shadow="hover" class="filter-card">
      <el-form :inline="true" @submit.prevent="searchFromFilters">
        <el-form-item label="状态">
          <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 140px"
                     @change="searchFromFilters">
            <el-option v-for="status in statusOptions" :key="status.value"
                       :label="status.label" :value="status.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="作品版本">
          <el-select v-model="filters.dedupe" style="width: 150px" @change="searchFromFilters">
            <el-option label="只看首选版本" value="preferred" />
            <el-option label="查看所有版本" value="all" />
            <el-option label="仅看重复版本" value="duplicates" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-autocomplete
            v-model="filters.keyword"
            :fetch-suggestions="fetchSuggestions"
            placeholder="搜索标题或文件名"
            clearable
            style="width: 240px"
            :trigger-on-focus="false"
            :debounce="300"
            @select="handleSelect"
            @keyup.enter="searchFromFilters"
            @clear="clearKeywordFilter"
          >
            <template #default="{ item }">
              <div class="suggestion-item">
                <el-tag size="small" type="info" style="margin-right: 8px">标题</el-tag>
                <span>{{ item.value }}</span>
              </div>
            </template>
          </el-autocomplete>
        </el-form-item>
        <el-form-item label="标签">
          <el-autocomplete
            v-model="filters.tagInput"
            :fetch-suggestions="fetchTagSuggestions"
            placeholder="搜索标签（中/英文）"
            clearable
            style="width: 240px"
            :trigger-on-focus="false"
            :debounce="300"
            @select="handleTagSelect"
            @clear="clearTagFilter"
          >
            <template #default="{ item }">
              <div class="suggestion-item">{{ item.label }}</div>
            </template>
          </el-autocomplete>
          <el-tag v-if="filters.tag" closable type="success" style="margin-left: 8px" @close="clearTagFilter">
            {{ tagStore.translate(filters.tag) }}
          </el-tag>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="searchFromFilters">
            <el-icon><Search /></el-icon> 搜索
          </el-button>
          <el-button @click="resetFilters">
            <el-icon><RefreshLeft /></el-icon> 重置
          </el-button>
        </el-form-item>
      </el-form>
      <div v-if="recentKeywords.length" class="search-history">
        <span>最近搜索：</span>
        <el-tag v-for="keyword in recentKeywords" :key="keyword" closable class="history-tag"
                @click="useRecentKeyword(keyword)" @close="removeRecentKeyword(keyword)">
          {{ keyword }}
        </el-tag>
      </div>
    </el-card>

    <el-card shadow="hover" style="margin-top: 16px">
      <el-skeleton v-if="firstLoad" :rows="10" animated style="padding: 8px 0" />
      <template v-else>
        <el-table :data="tableData" v-loading="loading" stripe style="width: 100%"
                  :default-sort="{ prop: sort.prop, order: sort.order === 'asc' ? 'ascending' : 'descending' }"
                  @sort-change="handleSortChange">
          <el-table-column prop="gid" label="GID" width="110" sortable="custom" />
          <el-table-column prop="title" label="标题" min-width="280" show-overflow-tooltip sortable="custom" />
          <el-table-column label="作品版本" width="145" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.duplicateOfGid" type="warning" size="small">重复 → {{ row.duplicateOfGid }}</el-tag>
              <el-tag v-else-if="row.dedupeKey" type="success" size="small">首选版本</el-tag>
              <span v-else class="text-muted">未识别</span>
            </template>
          </el-table-column>
          <el-table-column prop="downloadStatus" label="状态" width="110" align="center" sortable="custom">
            <template #default="{ row }">
              <el-tag :type="getStatusMeta(row.downloadStatus).type" size="small">
                {{ getStatusMeta(row.downloadStatus).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="fileSizeMb" label="文件大小" width="120" align="right" sortable="custom">
            <template #default="{ row }">
              {{ row.fileSizeMb ? row.fileSizeMb.toFixed(1) + ' MB' : '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="tags" label="标签" min-width="200">
            <template #default="{ row }">
              <template v-if="row.tags && row.tags.length">
                <el-tag v-for="tag in row.tags.slice(0, 5)" :key="tag" size="small"
                        class="tag-item" type="info" :title="tag">
                  {{ tagStore.translate(tag) }}
                </el-tag>
                <el-tag v-if="row.tags.length > 5" size="small" type="warning">+{{ row.tags.length - 5 }}</el-tag>
              </template>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="crawledAt" label="抓取时间" width="170" sortable="custom">
            <template #default="{ row }">{{ formatDate(row.crawledAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="showDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-wrapper">
          <el-pagination
            v-model:current-page="pagination.page"
            v-model:page-size="pagination.size"
            :page-sizes="[20, 50, 100]"
            :total="pagination.total"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="handlePageSizeChange"
            @current-change="() => loadData()"
          />
        </div>
      </template>
    </el-card>

    <el-drawer v-model="drawerVisible" title="画廊详情" size="500px">
      <template v-if="currentRow">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="GID">{{ currentRow.gid }}</el-descriptions-item>
          <el-descriptions-item label="Token">{{ currentRow.token }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ currentRow.title }}</el-descriptions-item>
          <el-descriptions-item label="原始标题">{{ currentRow.originalTitle || '-' }}</el-descriptions-item>
          <el-descriptions-item label="作品版本">
            <el-tag v-if="currentRow.duplicateOfGid" type="warning">
              重复版本，首选 GID：{{ currentRow.duplicateOfGid }}
            </el-tag>
            <el-tag v-else-if="currentRow.dedupeKey" type="success">
              首选版本（置信度 {{ currentRow.dedupeConfidence || '-' }}%）
            </el-tag>
            <span v-else class="text-muted">元数据不足，未自动归组</span>
          </el-descriptions-item>
          <el-descriptions-item label="评分 / 页数">
            {{ currentRow.rating ?? '-' }} / {{ currentRow.pageCount ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="匹配分数 / 算法版本">
            {{ currentRow.dedupeMatchScore ?? '-' }} / V{{ currentRow.dedupeAlgorithmVersion ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="匹配理由">
            {{ currentRow.dedupeMatchReason || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="文件名">{{ currentRow.filename }}</el-descriptions-item>
          <el-descriptions-item label="画廊链接">
            <a :href="currentRow.galleryUrl" target="_blank" rel="noopener">{{ currentRow.galleryUrl }}</a>
          </el-descriptions-item>
          <el-descriptions-item label="搜索关键词">{{ currentRow.searchQuery || '-' }}</el-descriptions-item>
          <el-descriptions-item label="下载状态">
            <el-tag :type="getStatusMeta(currentRow.downloadStatus).type">
              {{ getStatusMeta(currentRow.downloadStatus).label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="文件大小">
            {{ currentRow.fileSizeMb ? currentRow.fileSizeMb.toFixed(2) + ' MB' : '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="Komga Book ID">{{ currentRow.komgaBookId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="抓取时间">{{ formatDate(currentRow.crawledAt) }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="currentRow.tags && currentRow.tags.length" style="margin-top: 16px">
          <h4>标签</h4>
          <div class="tag-cloud">
            <el-popover v-for="tag in currentRow.tags" :key="tag" trigger="click" :width="320" placement="top"
                        @show="loadTagDetail(tag)">
              <template #reference>
                <el-tag size="small" class="tag-item tag-clickable" :title="tag">{{ tagStore.translate(tag) }}</el-tag>
              </template>
              <div class="tag-detail-popover">
                <div class="tag-detail-header">
                  <strong>{{ tagDetailMap[tag]?.name || tagStore.translate(tag) }}</strong>
                  <span class="tag-detail-raw">{{ tag }}</span>
                </div>
                <el-divider style="margin: 8px 0" />
                <div v-if="tagDetailMap[tag]?.intro" class="tag-detail-intro">{{ tagDetailMap[tag].intro }}</div>
                <div v-else class="tag-detail-intro text-muted">暂无描述信息</div>
              </div>
            </el-popover>
          </div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search, RefreshLeft } from '@element-plus/icons-vue'
import api from '../api'
import { useTagStore } from '../stores/tagStore'
import { getStatusMeta, normalizeStatusValue, STATUS_OPTIONS } from '../constants/status'

const HISTORY_KEY = 'gallery-search-history-v1'
const SORT_FIELDS = new Set(['gid', 'title', 'downloadStatus', 'fileSizeMb', 'crawledAt'])
const route = useRoute()
const router = useRouter()
const tagStore = useTagStore()
tagStore.loadTranslations()

const loading = ref(false)
const firstLoad = ref(true)
const tableData = ref([])
const drawerVisible = ref(false)
const currentRow = ref(null)
const statusOptions = STATUS_OPTIONS
const filters = reactive({ status: '', keyword: '', tag: '', tagInput: '', dedupe: 'preferred' })
const pagination = reactive({ page: 1, size: 20, total: 0 })
const sort = reactive({ prop: 'crawledAt', order: 'desc' })
const tagDetailMap = reactive({})
const recentKeywords = ref(readRecentKeywords())

let listController = null
let titleSuggestionController = null
let tagSuggestionController = null

function readRecentKeywords() {
  try {
    const value = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]')
    return Array.isArray(value) ? value.filter(item => typeof item === 'string').slice(0, 8) : []
  } catch {
    return []
  }
}

const formatDate = dateStr => dateStr ? new Date(dateStr).toLocaleString('zh-CN') : '-'

const positiveInteger = (value, fallback, max) => {
  const number = Number.parseInt(value, 10)
  return Number.isInteger(number) && number > 0 ? Math.min(number, max) : fallback
}

const applyRouteQuery = query => {
  const requestedStatus = normalizeStatusValue(typeof query.status === 'string' ? query.status : '')
  filters.status = statusOptions.some(status => status.value === requestedStatus) ? requestedStatus : ''
  filters.keyword = typeof query.q === 'string' ? query.q : ''
  filters.tag = typeof query.tag === 'string' ? query.tag : ''
  filters.dedupe = ['preferred', 'all', 'duplicates'].includes(query.dedupe) ? query.dedupe : 'preferred'
  filters.tagInput = ''
  pagination.page = positiveInteger(query.page, 1, Number.MAX_SAFE_INTEGER)
  pagination.size = positiveInteger(query.size, 20, 100)
  sort.prop = SORT_FIELDS.has(query.sort) ? query.sort : 'crawledAt'
  sort.order = query.order === 'asc' ? 'asc' : 'desc'
}

const buildRouteQuery = () => {
  const query = {
    page: String(pagination.page),
    size: String(pagination.size),
    sort: sort.prop,
    order: sort.order
  }
  if (filters.status) query.status = filters.status
  if (filters.keyword.trim()) query.q = filters.keyword.trim()
  if (filters.tag) query.tag = filters.tag
  if (filters.dedupe !== 'preferred') query.dedupe = filters.dedupe
  return query
}

const routeMatchesState = () => {
  const expected = buildRouteQuery()
  const actual = route.query
  const keys = new Set([...Object.keys(expected), ...Object.keys(actual)])
  return [...keys].every(key => String(actual[key] ?? '') === String(expected[key] ?? ''))
}

const syncRoute = () => {
  if (!routeMatchesState()) {
    void router.replace({ query: buildRouteQuery() })
  }
}

const persistHistory = () => {
  const keyword = filters.keyword.trim()
  if (!keyword) return
  recentKeywords.value = [keyword, ...recentKeywords.value.filter(item => item !== keyword)].slice(0, 8)
  localStorage.setItem(HISTORY_KEY, JSON.stringify(recentKeywords.value))
}

const loadData = async ({ updateUrl = true } = {}) => {
  if (listController) listController.abort()
  const controller = new AbortController()
  listController = controller
  loading.value = true

  if (updateUrl) syncRoute()
  const params = {
    page: pagination.page,
    size: pagination.size,
    sortBy: sort.prop,
    sortOrder: sort.order
  }
  if (filters.status) params.status = filters.status
  if (filters.keyword.trim()) params.keyword = filters.keyword.trim()
  if (filters.tag) params.tag = filters.tag
  params.dedupe = filters.dedupe

  try {
    const res = await api.get('/dashboard/galleries', { params, signal: controller.signal })
    if (controller !== listController) return
    tableData.value = res.data.records
    pagination.total = Number(res.data.total)
    firstLoad.value = false
  } catch (error) {
    if (error.code !== 'ERR_CANCELED') {
      // 错误提示由 API 拦截器统一处理。
    }
  } finally {
    if (controller === listController) loading.value = false
  }
}

const searchFromFilters = () => {
  pagination.page = 1
  persistHistory()
  void loadData()
}

const clearKeywordFilter = () => {
  filters.keyword = ''
  pagination.page = 1
  void loadData()
}

const fetchSuggestions = async (queryString, cb) => {
  if (!queryString || queryString.trim().length < 2) return cb([])
  titleSuggestionController?.abort()
  const controller = new AbortController()
  titleSuggestionController = controller
  try {
    const res = await api.get('/dashboard/suggestions', {
      params: { q: queryString, limit: 10, type: 'title' },
      signal: controller.signal
    })
    if (controller === titleSuggestionController) cb(res.data || [])
  } catch (error) {
    if (controller === titleSuggestionController && error.code !== 'ERR_CANCELED') cb([])
  }
}

const fetchTagSuggestions = async (queryString, cb) => {
  if (!queryString || queryString.trim().length < 1) return cb([])
  tagSuggestionController?.abort()
  const controller = new AbortController()
  tagSuggestionController = controller
  try {
    const res = await api.get('/dashboard/suggestions', {
      params: { q: queryString, limit: 15, type: 'tag' },
      signal: controller.signal
    })
    if (controller === tagSuggestionController) cb(res.data || [])
  } catch (error) {
    if (controller === tagSuggestionController && error.code !== 'ERR_CANCELED') cb([])
  }
}

const handleSelect = item => {
  filters.keyword = item.value
  searchFromFilters()
}

const handleTagSelect = item => {
  filters.tag = item.value
  filters.tagInput = ''
  searchFromFilters()
}

const clearTagFilter = () => {
  filters.tag = ''
  filters.tagInput = ''
  pagination.page = 1
  void loadData()
}

const resetFilters = () => {
  filters.status = ''
  filters.keyword = ''
  filters.tag = ''
  filters.tagInput = ''
  filters.dedupe = 'preferred'
  pagination.page = 1
  void loadData()
}

const useRecentKeyword = keyword => {
  filters.keyword = keyword
  searchFromFilters()
}

const removeRecentKeyword = keyword => {
  recentKeywords.value = recentKeywords.value.filter(item => item !== keyword)
  localStorage.setItem(HISTORY_KEY, JSON.stringify(recentKeywords.value))
}

const handlePageSizeChange = () => {
  pagination.page = 1
  void loadData()
}

const handleSortChange = ({ prop, order }) => {
  sort.prop = SORT_FIELDS.has(prop) ? prop : 'crawledAt'
  sort.order = order === 'ascending' ? 'asc' : 'desc'
  pagination.page = 1
  void loadData()
}

const loadTagDetail = async tag => {
  if (tagDetailMap[tag]) return
  tagDetailMap[tag] = await tagStore.fetchDetail(tag)
}

const showDetail = row => {
  currentRow.value = row
  drawerVisible.value = true
}

watch(() => route.fullPath, () => {
  if (routeMatchesState()) return
  applyRouteQuery(route.query)
  void loadData({ updateUrl: false })
})

onMounted(() => {
  applyRouteQuery(route.query)
  void loadData({ updateUrl: false })
})

onUnmounted(() => {
  listController?.abort()
  titleSuggestionController?.abort()
  tagSuggestionController?.abort()
})
</script>

<style scoped>
.filter-card :deep(.el-card__body) {
  padding-bottom: 12px;
}

.search-history {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  color: #909399;
  font-size: 13px;
}

.history-tag {
  cursor: pointer;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.tag-item {
  margin: 2px 4px 2px 0;
}

.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag-clickable {
  cursor: pointer;
}

.tag-detail-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tag-detail-raw,
.text-muted {
  color: #909399;
  font-size: 12px;
}

.tag-detail-intro {
  line-height: 1.6;
  white-space: pre-wrap;
}
</style>
