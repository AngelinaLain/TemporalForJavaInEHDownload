<template>
  <div class="archive-sync-page">
    <el-card shadow="hover" class="hero">
      <div class="hero-row">
        <div>
          <div class="eyebrow">SYNOLOGY ARCHIVE RECONCILIATION</div>
          <h2>群晖归档同步</h2>
          <p>筛出数据库文件名在群晖中不存在的画廊，依次按 GID、完整标题和标题片段召回旧文件，再由人工确认。</p>
        </div>
        <el-button type="primary" size="large" :loading="scanStatus.running" @click="startScan">
          {{ scanStatus.running ? '扫描中' : '重新扫描' }}
        </el-button>
      </div>
      <div v-if="scanStatus.running" class="progress-row">
        <el-progress :percentage="scanPercent" :stroke-width="8" />
        <span>{{ scanStatus.scanned || 0 }} / {{ scanStatus.total || 0 }}</span>
      </div>
      <el-alert v-if="scanStatus.error" :title="scanStatus.error" type="error" show-icon :closable="false" />
    </el-card>

    <el-card shadow="never" class="toolbar">
      <div class="toolbar-row">
        <div class="summary">
          <el-tag type="warning">待处理 {{ scanStatus.reviewCount || 0 }}</el-tag>
          <span>唯一候选也会保留给人工核查；候选若属于另一条数据库记录会显示冲突 GID。</span>
        </div>
        <div class="filters">
          <el-select v-model="filterStatus" style="width: 170px" @change="reloadFromFirstPage">
            <el-option label="待处理与失败" value="ACTIVE" />
            <el-option label="仅待核查" value="PENDING" />
            <el-option label="处理中" value="SYNCING" />
            <el-option label="处理失败" value="FAILED" />
            <el-option label="已重新下载" value="REDOWNLOAD_STARTED" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="全部" value="ALL" />
          </el-select>
          <el-button :loading="loading" @click="loadReviews">刷新</el-button>
        </div>
      </div>
    </el-card>

    <el-empty v-if="!loading && !records.length" description="当前没有需要审核的归档" />

    <el-card v-for="item in records" :key="item.gid" shadow="hover" class="review-card">
      <template #header>
        <div class="case-header">
          <div class="case-title">
            <el-tag :type="statusType(item.status)">{{ statusLabel(item.status) }}</el-tag>
            <el-tag effect="plain" :type="matchType(item.matchType).type">{{ matchType(item.matchType).label }}</el-tag>
            <strong>{{ item.title || '-' }}</strong>
            <span class="gid">GID {{ item.gid }}</span>
          </div>
          <a v-if="item.galleryUrl" :href="item.galleryUrl" target="_blank" rel="noopener">打开 EH ↗</a>
        </div>
      </template>

      <el-descriptions :column="1" border>
        <el-descriptions-item label="数据库目标文件名">
          <span class="filename target">{{ item.expectedFilename }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="匹配说明">{{ item.message || '-' }}</el-descriptions-item>
      </el-descriptions>

      <div class="candidate-editor">
        <div class="candidate-label">群晖原文件名</div>
        <el-select
          v-model="drafts[item.gid]"
          filterable
          allow-create
          default-first-option
          placeholder="选择候选，或手工输入群晖中的完整原文件名"
          class="candidate-select"
          :disabled="item.status === 'SYNCING' || item.status === 'REDOWNLOAD_STARTING' || item.status === 'COMPLETED'"
        >
          <el-option
            v-for="candidate in item.candidates"
            :key="candidate.filename"
            :label="candidateLabel(candidate)"
            :value="candidate.filename"
          />
        </el-select>
      </div>

      <div v-if="item.candidates?.some(candidate => candidate.databaseGid)" class="conflicts">
        <el-alert type="warning" show-icon :closable="false">
          <template #title>候选文件已被其他数据库记录引用，请核对后再处理</template>
          <div v-for="candidate in item.candidates.filter(value => value.databaseGid)" :key="candidate.filename">
            GID {{ candidate.databaseGid }}：{{ candidate.filename }}
          </div>
        </el-alert>
      </div>

      <div class="actions">
        <el-button
          type="primary"
          :loading="workingGid === item.gid && workingAction === 'sync'"
          :disabled="!drafts[item.gid] || item.status === 'SYNCING' || item.status === 'REDOWNLOAD_STARTING' || item.status === 'COMPLETED'"
          @click="synchronize(item)"
        >
          无需重下，按此文件同步
        </el-button>
        <el-button
          type="danger"
          plain
          :loading="workingGid === item.gid && workingAction === 'redownload'"
          :disabled="item.status === 'SYNCING' || item.status === 'REDOWNLOAD_STARTING' || item.status === 'COMPLETED'"
          @click="redownload(item)"
        >
          重新抓取下载并入库
        </el-button>
      </div>
    </el-card>

    <div v-if="pagination.total" class="pagination-wrapper">
      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :page-sizes="[10, 20, 50, 100]"
        :total="pagination.total"
        layout="total, sizes, prev, pager, next"
        @size-change="reloadFromFirstPage"
        @current-change="loadReviews"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const records = ref([])
const loading = ref(false)
const scanStatus = reactive({ running: false, total: 0, scanned: 0, reviewCount: 0, error: null })
const pagination = reactive({ page: 1, size: 20, total: 0 })
const filterStatus = ref('ACTIVE')
const drafts = reactive({})
const workingGid = ref(null)
const workingAction = ref(null)
let pollTimer

const scanPercent = computed(() => scanStatus.total
  ? Math.min(100, Math.round((scanStatus.scanned || 0) * 100 / scanStatus.total))
  : 0)

const loadStatus = async () => {
  const res = await api.get('/archive-sync/status')
  Object.assign(scanStatus, res.data || {})
}

const loadReviews = async () => {
  loading.value = true
  try {
    const res = await api.get('/archive-sync/reviews', {
      params: { page: pagination.page, size: pagination.size, status: filterStatus.value }
    })
    records.value = res.data.records || []
    pagination.total = Number(res.data.total || 0)
    for (const item of records.value) {
      if (!drafts[item.gid]) drafts[item.gid] = item.selectedFilename || item.candidates?.[0]?.filename || ''
    }
  } finally {
    loading.value = false
  }
}

const startScan = async () => {
  await api.post('/archive-sync/scan')
  ElMessage.success('群晖归档扫描已启动')
  pagination.page = 1
  await loadStatus()
}

const reloadFromFirstPage = () => {
  pagination.page = 1
  void loadReviews()
}

const synchronize = async item => {
  const filename = drafts[item.gid]?.trim()
  if (!filename) return
  try {
    await ElMessageBox.confirm(
      `将读取“${filename}”，检查并补写 ComicInfo.xml，最终改名为数据库目标文件名。是否继续？`,
      `确认同步 GID ${item.gid}`,
      { type: 'warning', confirmButtonText: '开始同步', cancelButtonText: '取消' }
    )
  } catch { return }
  workingGid.value = item.gid
  workingAction.value = 'sync'
  try {
    await api.post(`/archive-sync/${item.gid}/synchronize`, { filename })
    ElMessage.success('同步已在后台启动')
    await Promise.all([loadStatus(), loadReviews()])
  } finally {
    workingGid.value = null
    workingAction.value = null
  }
}

const redownload = async item => {
  try {
    await ElMessageBox.confirm(
      `GID ${item.gid} 将重新从 EH 抓取、下载、写入 ComicInfo.xml 并扫描入库。是否继续？`,
      '确认重新下载',
      { type: 'warning', confirmButtonText: '重新下载', cancelButtonText: '取消' }
    )
  } catch { return }
  workingGid.value = item.gid
  workingAction.value = 'redownload'
  try {
    const res = await api.post(`/archive-sync/${item.gid}/redownload`)
    ElMessage.success(`下载流程已启动：${res.data.workflowId}`)
    await Promise.all([loadStatus(), loadReviews()])
  } finally {
    workingGid.value = null
    workingAction.value = null
  }
}

const candidateLabel = candidate => candidate.databaseGid
  ? `${candidate.filename}（数据库 GID ${candidate.databaseGid}）`
  : candidate.filename

const matchType = value => ({
  GID: { label: 'GID 匹配', type: 'success' },
  TITLE: { label: '标题匹配', type: 'primary' },
  FUZZY: { label: '模糊匹配', type: 'warning' },
  MISSING: { label: '未匹配', type: 'danger' }
}[value] || { label: value || '未知', type: 'info' })

const statusLabel = value => ({
  PENDING: '待核查', SYNCING: '同步中', FAILED: '处理失败',
  COMPLETED: '已完成', REDOWNLOAD_STARTING: '正在启动下载', REDOWNLOAD_STARTED: '已启动下载'
}[value] || value)

const statusType = value => ({
  PENDING: 'warning', SYNCING: 'primary', FAILED: 'danger',
  COMPLETED: 'success', REDOWNLOAD_STARTING: 'primary', REDOWNLOAD_STARTED: 'primary'
}[value] || 'info')

const poll = async () => {
  try {
    const wasRunning = scanStatus.running
    await loadStatus()
    if (scanStatus.running || wasRunning || records.value.some(item => item.status === 'SYNCING')) {
      await loadReviews()
    }
  } catch {
    // Global interceptor already reports actionable failures.
  }
}

onMounted(async () => {
  await Promise.all([loadStatus(), loadReviews()])
  pollTimer = window.setInterval(poll, 2500)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<style scoped>
.archive-sync-page { max-width: 1480px; margin: 0 auto; }
.hero { background: linear-gradient(135deg, #173f4a, #245d68); color: #fff; }
.hero-row, .toolbar-row, .case-header, .case-title, .actions, .summary, .filters { display: flex; align-items: center; }
.hero-row, .toolbar-row, .case-header { justify-content: space-between; gap: 24px; }
.hero h2 { margin: 4px 0 8px; }
.hero p { margin: 0; color: #d8edf0; }
.eyebrow { color: #63e6df; font-size: 12px; font-weight: 700; letter-spacing: 1.5px; }
.progress-row { margin-top: 22px; display: grid; grid-template-columns: 1fr auto; gap: 14px; align-items: center; }
.toolbar, .review-card { margin-top: 16px; }
.summary, .filters, .case-title, .actions { gap: 10px; }
.summary span { color: #606266; font-size: 13px; }
.case-title { min-width: 0; }
.case-title strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gid { color: #909399; white-space: nowrap; font-size: 13px; }
.case-header a { color: #409eff; text-decoration: none; white-space: nowrap; }
.filename { word-break: break-all; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.target { font-weight: 600; color: #1f5d68; }
.candidate-editor { display: grid; grid-template-columns: 130px 1fr; gap: 14px; align-items: center; margin-top: 16px; }
.candidate-label { color: #606266; font-size: 14px; }
.candidate-select { width: 100%; }
.conflicts, .actions { margin-top: 14px; }
.pagination-wrapper { display: flex; justify-content: flex-end; margin: 20px 0; }
@media (max-width: 900px) {
  .hero-row, .toolbar-row, .case-header { align-items: flex-start; flex-direction: column; }
  .summary, .filters, .case-title, .actions { flex-wrap: wrap; }
  .candidate-editor { grid-template-columns: 1fr; }
}
</style>
