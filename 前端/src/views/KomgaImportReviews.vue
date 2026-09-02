<template>
  <div class="review-page">
    <el-card shadow="hover" class="toolbar">
      <div class="toolbar-content">
        <div>
          <h3>Komga 入库复核</h3>
          <p>这里的补偿只重新触发 Komga 扫描和确认，不会重新下载已经上传到群晖的文件。</p>
        </div>
        <div class="toolbar-actions">
          <el-tag type="danger">失败 {{ failedCount }}</el-tag>
          <el-tag type="primary">等待 {{ waitingCount }}</el-tag>
          <el-select v-model="status" style="width: 170px" @change="reloadFromFirstPage">
            <el-option label="Komga 入库失败" value="KOMGA_IMPORT_FAILED" />
            <el-option label="等待 Komga" value="WAITING_KOMGA" />
            <el-option label="全部记录" value="ALL" />
          </el-select>
          <el-button :loading="loading" @click="loadData">刷新</el-button>
        </div>
      </div>
    </el-card>

    <el-skeleton v-if="firstLoad" :rows="8" animated class="skeleton" />
    <el-empty v-else-if="!records.length" description="当前没有 Komga 复核记录" />

    <el-card v-for="item in records" :key="item.gid" shadow="hover" class="review-card">
      <template #header>
        <div class="case-header">
          <div class="case-title">
            <el-tag :type="item.downloadStatus === 'KOMGA_IMPORT_FAILED' ? 'danger' : 'primary'">
              {{ getStatusMeta(item.downloadStatus).label }}
            </el-tag>
            <strong>{{ item.title || '-' }}</strong>
            <span class="gid">GID {{ item.gid }}</span>
          </div>
          <el-button
            v-if="item.downloadStatus === 'KOMGA_IMPORT_FAILED'"
            type="primary"
            :loading="retryingGid === item.gid"
            @click="retry(item)"
          >
            仅重试 Komga
          </el-button>
        </div>
      </template>

      <el-descriptions :column="2" border>
        <el-descriptions-item label="文件名">{{ item.filename || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Book ID">{{ item.komgaBookId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="确认次数">{{ item.confirmationAttempts ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="最后确认">{{ formatDate(item.lastConfirmationAt) }}</el-descriptions-item>
        <el-descriptions-item label="候选 Book ID" :span="2">
          <span class="candidate-ids">{{ item.candidateBookIds || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="最近原因" :span="2">
          <span class="reason">{{ item.confirmationReason || '-' }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <a v-if="item.galleryUrl" class="gallery-link" :href="item.galleryUrl" target="_blank" rel="noopener">
        打开 EH 画廊 ↗
      </a>
    </el-card>

    <div v-if="pagination.total" class="pagination-wrapper">
      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :page-sizes="[10, 20, 50]"
        :total="pagination.total"
        layout="total, sizes, prev, pager, next"
        @size-change="reloadFromFirstPage"
        @current-change="loadData"
      />
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { getStatusMeta } from '../constants/status'

const records = ref([])
const loading = ref(false)
const firstLoad = ref(true)
const retryingGid = ref(null)
const status = ref('KOMGA_IMPORT_FAILED')
const failedCount = ref(0)
const waitingCount = ref(0)
const pagination = reactive({ page: 1, size: 20, total: 0 })

const loadData = async () => {
  loading.value = true
  try {
    const res = await api.get('/komga-import-reviews', {
      params: { page: pagination.page, size: pagination.size, status: status.value }
    })
    records.value = res.data.records || []
    pagination.total = Number(res.data.total || 0)
    failedCount.value = Number(res.data.failedCount || 0)
    waitingCount.value = Number(res.data.waitingCount || 0)
    firstLoad.value = false
  } finally {
    loading.value = false
  }
}

const reloadFromFirstPage = () => {
  pagination.page = 1
  void loadData()
}

const retry = async item => {
  try {
    await ElMessageBox.confirm(
      `GID ${item.gid} 将只重试 Komga 扫描确认，不会重新下载文件，是否继续？`,
      '确认 Komga 补偿',
      { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  retryingGid.value = item.gid
  try {
    const res = await api.post(`/komga-import-reviews/${item.gid}/retry`)
    ElMessage.success(`补偿流程已启动：${res.data.workflowId}`)
    await loadData()
  } finally {
    retryingGid.value = null
  }
}

const formatDate = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'

onMounted(loadData)
</script>

<style scoped>
.review-page { max-width: 1440px; margin: 0 auto; }
.toolbar-content, .toolbar-actions, .case-header, .case-title { display: flex; align-items: center; }
.toolbar-content { justify-content: space-between; gap: 24px; }
.toolbar h3 { margin: 0 0 8px; color: #303133; }
.toolbar p { margin: 0; color: #606266; font-size: 14px; }
.toolbar-actions { gap: 10px; flex-shrink: 0; }
.skeleton, .review-card { margin-top: 16px; }
.case-header { justify-content: space-between; gap: 16px; }
.case-title { gap: 10px; min-width: 0; }
.case-title strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gid { color: #909399; font-size: 13px; white-space: nowrap; }
.candidate-ids { font-family: monospace; word-break: break-all; }
.reason { color: #606266; word-break: break-word; }
.gallery-link { display: inline-block; margin-top: 14px; color: #409eff; text-decoration: none; }
.pagination-wrapper { display: flex; justify-content: flex-end; margin: 20px 0; }
@media (max-width: 900px) {
  .toolbar-content { align-items: flex-start; flex-direction: column; }
  .toolbar-actions { flex-wrap: wrap; }
  .case-header { align-items: flex-start; flex-direction: column; }
}
</style>
