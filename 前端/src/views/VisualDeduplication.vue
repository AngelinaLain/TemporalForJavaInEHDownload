<template>
  <div class="visual-page">
    <el-card shadow="hover">
      <template #header>
        <div class="header-row">
          <div>
            <h3>视觉指纹管理</h3>
            <p>对新增和历史画廊生成采样页感知哈希；历史刷新按文件顺序单任务执行，避免压满 NAS。</p>
          </div>
          <el-button :loading="loading" @click="loadStatus">刷新状态</el-button>
        </div>
      </template>

      <el-row :gutter="16">
        <el-col :xs="24" :sm="12">
          <el-statistic title="当前算法版本" :value="status.algorithmVersion || 0" />
        </el-col>
        <el-col :xs="24" :sm="12">
          <el-statistic title="已有当前版本指纹的画廊" :value="status.fingerprintedGalleries || 0" />
        </el-col>
      </el-row>

      <el-divider />
      <div class="actions">
        <el-button type="primary" :disabled="jobRunning" @click="startRefresh(false)">补全缺失/旧版本</el-button>
        <el-button type="warning" plain :disabled="jobRunning" @click="confirmForceRefresh">强制全部重算</el-button>
      </div>
      <el-alert
        title="强制重算会重新读取群晖中的所有已登记 CBZ。它不会删除画廊文件，但可能产生较长时间的 NAS 顺序读取。"
        type="info"
        :closable="false"
        show-icon
        class="notice"
      />
    </el-card>

    <el-card shadow="hover" class="job-card">
      <template #header>
        <div class="header-row">
          <div>
            <strong>AI 视觉复核</strong>
            <p class="review-hint">输入两个候选画廊 GID。默认只运行本地向量分析，是否调用 LLM 由“AI 设置”决定。</p>
          </div>
          <el-button @click="$router.push('/ai-settings')">AI 设置</el-button>
        </div>
      </template>
      <div class="review-form">
        <el-input-number v-model="reviewForm.leftGid" :min="1" :controls="false" placeholder="左侧 GID" />
        <el-input-number v-model="reviewForm.rightGid" :min="1" :controls="false" placeholder="右侧 GID" />
        <el-button type="primary" :loading="reviewLoading" :disabled="reviewRunning" @click="startAiReview">启动 Temporal 复核</el-button>
      </div>
      <template v-if="reviewState.job">
        <el-divider />
        <div class="review-status">
          <span>任务：{{ reviewState.job.id }}</span>
          <el-tag :type="reviewTag.type">{{ reviewTag.label }}</el-tag>
        </div>
        <el-alert v-if="reviewState.job.lastError" :title="reviewState.job.lastError" type="warning" :closable="false" show-icon class="notice" />
        <dl v-if="reviewState.result" class="job-facts review-result">
          <div><dt>判断</dt><dd>{{ decisionLabel(reviewState.result.decision) }}</dd></div>
          <div><dt>置信度</dt><dd>{{ percent(reviewState.result.confidence) }}</dd></div>
          <div><dt>向量相似度</dt><dd>{{ percent(reviewState.result.embeddingSimilarity) }}</dd></div>
          <div><dt>感知哈希</dt><dd>{{ percent(reviewState.result.perceptualHashSimilarity) }}</dd></div>
          <div><dt>匹配页面</dt><dd>{{ reviewState.result.matchedPages }}/{{ reviewState.result.comparedPages }}</dd></div>
          <div><dt>图片已外发</dt><dd>{{ reviewState.result.imagesTransmitted ? '是' : '否' }}</dd></div>
        </dl>
        <el-alert v-if="reviewState.result?.reason" :title="reviewState.result.reason" type="info" :closable="false" show-icon />
      </template>
    </el-card>

    <el-card v-if="job" shadow="hover" class="job-card">
      <template #header>
        <div class="header-row">
          <strong>最近刷新任务</strong>
          <el-tag :type="jobTag.type">{{ jobTag.label }}</el-tag>
        </div>
      </template>
      <el-progress :percentage="progress" :status="progressStatus" />
      <dl class="job-facts">
        <div><dt>总数</dt><dd>{{ job.total || 0 }}</dd></div>
        <div><dt>已处理</dt><dd>{{ job.processed || 0 }}</dd></div>
        <div><dt>成功</dt><dd>{{ job.succeeded || 0 }}</dd></div>
        <div><dt>失败</dt><dd>{{ job.failed || 0 }}</dd></div>
        <div><dt>当前 GID</dt><dd>{{ job.currentGid || '-' }}</dd></div>
        <div><dt>算法版本</dt><dd>{{ job.algorithmVersion || '-' }}</dd></div>
      </dl>
      <el-alert v-if="job.lastError" :title="job.lastError" type="warning" :closable="false" show-icon />
    </el-card>

    <el-card v-if="failedGalleries.length" shadow="hover" class="failure-card">
      <template #header>
        <div class="header-row">
          <div>
            <strong>失败画廊</strong>
            <span class="failure-hint">每条失败均已保存，可选择后单独重试。</span>
          </div>
          <el-button
            type="primary"
            :disabled="jobRunning || !selectedFailures.length"
            :loading="loading"
            @click="retrySelected"
          >重试所选（{{ selectedFailures.length }}）</el-button>
        </div>
      </template>
      <el-table :data="failedGalleries" row-key="gid" @selection-change="selectedFailures = $event">
        <el-table-column type="selection" width="48" />
        <el-table-column prop="gid" label="GID" width="150" />
        <el-table-column prop="error" label="错误" min-width="420" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const loading = ref(false)
const reviewLoading = ref(false)
const status = reactive({ algorithmVersion: 0, fingerprintedGalleries: 0, latestJob: null, failedGalleries: [] })
const reviewForm = reactive({ leftGid: null, rightGid: null })
const reviewState = reactive({ job: null, result: null })
const selectedFailures = ref([])
const job = computed(() => status.latestJob)
const failedGalleries = computed(() => status.failedGalleries || [])
const jobRunning = computed(() => ['QUEUED', 'RUNNING'].includes(job.value?.status))
const reviewRunning = computed(() => ['QUEUED', 'RUNNING'].includes(reviewState.job?.status))
const progress = computed(() => {
  if (!job.value?.total) return jobRunning.value ? 0 : 100
  return Math.min(100, Math.round((job.value.processed || 0) * 100 / job.value.total))
})
const progressStatus = computed(() => {
  if (job.value?.status === 'FAILED') return 'exception'
  if (job.value?.status === 'COMPLETED') return 'success'
  if (job.value?.status === 'COMPLETED_WITH_ERRORS') return 'warning'
  return undefined
})
const jobTag = computed(() => ({
  QUEUED: { label: '等待执行', type: 'info' },
  RUNNING: { label: '执行中', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' },
  COMPLETED_WITH_ERRORS: { label: '完成但有失败', type: 'warning' },
  FAILED: { label: '失败', type: 'danger' }
}[job.value?.status] || { label: job.value?.status || '-', type: 'info' }))
const reviewTag = computed(() => ({
  QUEUED: { label: '等待执行', type: 'info' }, RUNNING: { label: '分析中', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' }, COMPLETED_WITH_WARNINGS: { label: '完成（LLM 不可用）', type: 'warning' },
  FAILED: { label: '失败', type: 'danger' }
}[reviewState.job?.status] || { label: reviewState.job?.status || '-', type: 'info' }))

let timer
const loadStatus = async () => {
  loading.value = true
  try {
    const res = await api.get('/visual-dedup/status')
    Object.assign(status, res.data || {})
  } finally {
    loading.value = false
  }
}

const startRefresh = async force => {
  loading.value = true
  try {
    await api.post('/visual-dedup/refresh', { force })
    ElMessage.success(force ? '全量视觉指纹重算已启动' : '视觉指纹补全已启动')
    await loadStatus()
  } finally {
    loading.value = false
  }
}

const confirmForceRefresh = async () => {
  try {
    await ElMessageBox.confirm('将重新读取所有已有画廊归档并覆盖当前版本指纹，是否继续？', '确认全量重算', {
      type: 'warning', confirmButtonText: '开始重算', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await startRefresh(true)
}

const retrySelected = async () => {
  const gids = selectedFailures.value.map(failure => failure.gid)
  if (!gids.length) return
  loading.value = true
  try {
    await api.post('/visual-dedup/refresh/retry', { gids })
    selectedFailures.value = []
    ElMessage.success(`已开始重试 ${gids.length} 个失败画廊`)
    await loadStatus()
  } finally {
    loading.value = false
  }
}

const startAiReview = async () => {
  if (!reviewForm.leftGid || !reviewForm.rightGid) return ElMessage.warning('请输入两个画廊 GID')
  reviewLoading.value = true
  try {
    const response = await api.post('/visual-dedup/ai-review', reviewForm)
    localStorage.setItem('visualAiReviewJobId', response.data.jobId)
    ElMessage.success('AI 视觉复核已提交到 Temporal')
    await loadAiReview(response.data.jobId)
  } finally { reviewLoading.value = false }
}
const loadAiReview = async id => {
  if (!id) return
  const response = await api.get(`/visual-dedup/ai-review/${id}`)
  Object.assign(reviewState, response.data || {})
}
const percent = value => value == null ? '-' : `${Math.round(Number(value) * 100)}%`
const decisionLabel = value => ({ SAME_CONTENT: '同一内容', POSSIBLE_VARIANT: '可能为版本差异', DIFFERENT_CONTENT: '不同内容', INSUFFICIENT_EVIDENCE: '证据不足' }[value] || value)

onMounted(async () => {
  await loadStatus()
  const savedReviewId = localStorage.getItem('visualAiReviewJobId')
  if (savedReviewId) await loadAiReview(savedReviewId).catch(() => localStorage.removeItem('visualAiReviewJobId'))
  timer = window.setInterval(() => {
    if (jobRunning.value) void loadStatus()
    if (reviewRunning.value && reviewState.job?.id) void loadAiReview(reviewState.job.id)
  }, 5000)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<style scoped>
.visual-page { max-width: 1200px; margin: 0 auto; }
.header-row { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.header-row h3 { margin: 0 0 8px; }
.header-row p { margin: 0; color: #606266; font-size: 14px; }
.actions { display: flex; gap: 10px; }
.actions .el-button + .el-button { margin-left: 0; }
.notice, .job-card, .failure-card { margin-top: 16px; }
.failure-hint { margin-left: 12px; color: #909399; font-size: 13px; }
.review-hint { margin: 6px 0 0; color: #909399; font-size: 13px; }
.review-form { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.review-status { display: flex; justify-content: space-between; align-items: center; color: #606266; }
.review-result { margin-bottom: 12px; }
.job-facts { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; margin: 18px 0; }
.job-facts div { padding: 10px; border-radius: 6px; background: #f5f7fa; text-align: center; }
.job-facts dt { color: #909399; font-size: 12px; }
.job-facts dd { margin: 5px 0 0; color: #303133; font-weight: 600; }
@media (max-width: 900px) {
  .header-row { align-items: flex-start; flex-direction: column; }
  .job-facts { grid-template-columns: repeat(2, 1fr); }
}
</style>
