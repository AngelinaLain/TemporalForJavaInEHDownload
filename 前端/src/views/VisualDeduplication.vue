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
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const loading = ref(false)
const status = reactive({ algorithmVersion: 0, fingerprintedGalleries: 0, latestJob: null })
const job = computed(() => status.latestJob)
const jobRunning = computed(() => ['QUEUED', 'RUNNING'].includes(job.value?.status))
const progress = computed(() => {
  if (!job.value?.total) return jobRunning.value ? 0 : 100
  return Math.min(100, Math.round((job.value.processed || 0) * 100 / job.value.total))
})
const progressStatus = computed(() => job.value?.status === 'FAILED' ? 'exception' : job.value?.status === 'COMPLETED' ? 'success' : undefined)
const jobTag = computed(() => ({
  QUEUED: { label: '等待执行', type: 'info' },
  RUNNING: { label: '执行中', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' },
  COMPLETED_WITH_ERRORS: { label: '完成但有失败', type: 'warning' },
  FAILED: { label: '失败', type: 'danger' }
}[job.value?.status] || { label: job.value?.status || '-', type: 'info' }))

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

onMounted(async () => {
  await loadStatus()
  timer = window.setInterval(() => { if (jobRunning.value) void loadStatus() }, 5000)
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
.notice, .job-card { margin-top: 16px; }
.job-facts { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; margin: 18px 0; }
.job-facts div { padding: 10px; border-radius: 6px; background: #f5f7fa; text-align: center; }
.job-facts dt { color: #909399; font-size: 12px; }
.job-facts dd { margin: 5px 0 0; color: #303133; font-weight: 600; }
@media (max-width: 900px) {
  .header-row { align-items: flex-start; flex-direction: column; }
  .job-facts { grid-template-columns: repeat(2, 1fr); }
}
</style>
