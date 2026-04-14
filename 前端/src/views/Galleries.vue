<template>
  <div class="galleries-page">
    <!-- 搜索栏 -->
    <el-card shadow="hover" class="filter-card">
      <el-form :inline="true" @submit.prevent="loadData">
        <el-form-item label="状态">
          <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 140px">
            <el-option v-for="s in statusOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-autocomplete
            v-model="filters.keyword"
            :fetch-suggestions="fetchSuggestions"
            placeholder="搜索标题"
            clearable
            style="width: 240px"
            :trigger-on-focus="false"
            :debounce="300"
            @select="handleSelect"
            @keyup.enter="loadData"
            @clear="() => { filters.keyword = ''; loadData() }"
          >
            <template #default="{ item }">
              <div class="suggestion-item">
                <el-tag :type="item.type === 'tag' ? 'success' : 'info'" size="small" style="margin-right: 8px">
                  {{ item.type === 'tag' ? '标签' : '标题' }}
                </el-tag>
                <span>{{ item.type === 'tag' ? item.label : item.value }}</span>
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
              <div class="suggestion-item">
                <span>{{ item.label }}</span>
              </div>
            </template>
          </el-autocomplete>
          <el-tag v-if="filters.tag" closable type="success" style="margin-left: 8px" @close="clearTagFilter">
            {{ tagStore.translate(filters.tag) }}
          </el-tag>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData">
            <el-icon><Search /></el-icon> 搜索
          </el-button>
          <el-button @click="resetFilters">
            <el-icon><RefreshLeft /></el-icon> 重置
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 数据表格 -->
    <el-card shadow="hover" style="margin-top: 16px">
      <el-table :data="tableData" v-loading="loading" stripe style="width: 100%"
                @sort-change="handleSortChange">
        <el-table-column prop="gid" label="GID" width="110" />
        <el-table-column prop="title" label="标题" min-width="280" show-overflow-tooltip />
        <el-table-column prop="downloadStatus" label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.downloadStatus)" size="small">
              {{ row.downloadStatus }}
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
              <el-tag v-if="row.tags.length > 5" size="small" type="warning">
                +{{ row.tags.length - 5 }}
              </el-tag>
            </template>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="crawledAt" label="抓取时间" width="170" sortable="custom">
          <template #default="{ row }">
            {{ formatDate(row.crawledAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="showDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="loadData"
          @current-change="loadData"
        />
      </div>
    </el-card>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawerVisible" title="画廊详情" size="500px">
      <template v-if="currentRow">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="GID">{{ currentRow.gid }}</el-descriptions-item>
          <el-descriptions-item label="Token">{{ currentRow.token }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ currentRow.title }}</el-descriptions-item>
          <el-descriptions-item label="文件名">{{ currentRow.filename }}</el-descriptions-item>
          <el-descriptions-item label="画廊链接">
            <a :href="currentRow.galleryUrl" target="_blank" rel="noopener">{{ currentRow.galleryUrl }}</a>
          </el-descriptions-item>
          <el-descriptions-item label="搜索关键词">{{ currentRow.searchQuery || '-' }}</el-descriptions-item>
          <el-descriptions-item label="下载状态">
            <el-tag :type="statusTagType(currentRow.downloadStatus)">{{ currentRow.downloadStatus }}</el-tag>
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
            <el-popover
              v-for="tag in currentRow.tags" :key="tag"
              trigger="click" :width="320" placement="top"
              @show="loadTagDetail(tag)"
            >
              <template #reference>
                <el-tag size="small" class="tag-item tag-clickable" :title="tag">
                  {{ tagStore.translate(tag) }}
                </el-tag>
              </template>
              <div class="tag-detail-popover">
                <div class="tag-detail-header">
                  <strong>{{ tagDetailMap[tag]?.name || tagStore.translate(tag) }}</strong>
                  <span class="tag-detail-raw">{{ tag }}</span>
                </div>
                <el-divider style="margin: 8px 0" />
                <div v-if="tagDetailMap[tag]?.intro" class="tag-detail-intro">
                  {{ tagDetailMap[tag].intro }}
                </div>
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
import { ref, reactive, onMounted } from 'vue'
import { Search, RefreshLeft } from '@element-plus/icons-vue'
import api from '../api'
import { useTagStore } from '../stores/tagStore'

const tagStore = useTagStore()
tagStore.loadTranslations()

const loading = ref(false)
const tableData = ref([])
const drawerVisible = ref(false)
const currentRow = ref(null)

const statusOptions = ['未下载', '下载中', '已下载', '下载失败', '已入库', '阻断', '已忽略']

const filters = reactive({
  status: '',
  keyword: '',
  tag: '',
  tagInput: ''
})

const pagination = reactive({
  page: 1,
  size: 20,
  total: 0
})

const statusTagType = (status) => {
  const map = {
    '未下载': 'info',
    '下载中': '',
    '已下载': 'success',
    '下载失败': 'danger',
    '已入库': 'warning',
    '阻断': 'danger',
    '已忽略': 'info'
  }
  return map[status] || 'info'
}

const formatDate = (dateStr) => {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  return d.toLocaleString('zh-CN')
}

const loadData = async () => {
  loading.value = true
  try {
    const params = {
      page: pagination.page,
      size: pagination.size
    }
    if (filters.status) params.status = filters.status
    if (filters.tag) {
      params.tag = filters.tag
    } else if (filters.keyword) {
      params.keyword = filters.keyword
    }

    const res = await api.get('/dashboard/galleries', { params })
    tableData.value = res.data.records
    pagination.total = Number(res.data.total)
  } catch {
    // handled by interceptor
  } finally {
    loading.value = false
  }
}

const fetchSuggestions = async (queryString, cb) => {
  if (!queryString || queryString.length < 2) {
    cb([])
    return
  }
  try {
    const res = await api.get('/dashboard/suggestions', { params: { q: queryString, limit: 10 } })
    // 只返回标题类型的建议
    cb((res.data || []).filter(s => s.type === 'title'))
  } catch {
    cb([])
  }
}

const fetchTagSuggestions = async (queryString, cb) => {
  if (!queryString || queryString.length < 1) {
    cb([])
    return
  }
  try {
    const res = await api.get('/dashboard/suggestions', { params: { q: queryString, limit: 15 } })
    cb((res.data || []).filter(s => s.type === 'tag'))
  } catch {
    cb([])
  }
}

const handleSelect = (item) => {
  filters.keyword = item.value
  loadData()
}

const handleTagSelect = (item) => {
  filters.tag = item.value
  filters.tagInput = ''
  loadData()
}

const clearTagFilter = () => {
  filters.tag = ''
  filters.tagInput = ''
  loadData()
}

const resetFilters = () => {
  filters.status = ''
  filters.keyword = ''
  filters.tag = ''
  filters.tagInput = ''
  pagination.page = 1
  loadData()
}

/** 标签详情缓存 */
const tagDetailMap = reactive({})

const loadTagDetail = async (tag) => {
  if (tagDetailMap[tag]) return
  const detail = await tagStore.fetchDetail(tag)
  tagDetailMap[tag] = detail
}

const handleSortChange = () => {
  loadData()
}

const showDetail = (row) => {
  currentRow.value = row
  drawerVisible.value = true
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.filter-card :deep(.el-card__body) {
  padding-bottom: 2px;
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

.text-muted {
  color: #c0c4cc;
}

.suggestion-item {
  display: flex;
  align-items: center;
  line-height: 1.4;
}

.tag-clickable {
  cursor: pointer;
  transition: all 0.2s;
}

.tag-clickable:hover {
  transform: scale(1.05);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.15);
}

.tag-detail-popover .tag-detail-header {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.tag-detail-raw {
  font-size: 12px;
  color: #909399;
}

.tag-detail-intro {
  font-size: 13px;
  line-height: 1.6;
  color: #606266;
  max-height: 200px;
  overflow-y: auto;
}
</style>
