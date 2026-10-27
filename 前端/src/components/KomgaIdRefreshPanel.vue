<template>
  <section class="id-refresh-panel" aria-labelledby="komga-id-refresh-title">
    <header class="panel-heading">
      <div class="panel-heading-copy"><p class="panel-eyebrow">LIBRARY CONNECTIONS</p><h2 id="komga-id-refresh-title">刷新数据库中的 Komga ID</h2><p>检查旧关联，只有本地归档的 GID 或文件名唯一匹配时才更新数据库；未找到或存在多个候选时保留原 ID。</p></div>
      <span class="job-state" :class="{ active: job.running }" role="status"><i />{{ statusLabel }}</span>
    </header>

    <form class="refresh-form" @submit.prevent="start">
      <label class="gid-field"><span>指定画廊 GID <small>可选</small></span><el-input v-model="gidInput" aria-label="要刷新 Komga ID 的画廊 GID" placeholder="例如 1553036，多个 GID 用逗号或空格分隔" :disabled="job.running || actionBusy" maxlength="20000" clearable /><small>留空检查已有关联或已入库的记录；指定时最多 1000 个 GID。</small></label>
      <div class="refresh-actions"><el-button type="primary" native-type="submit" :icon="Refresh" :loading="starting" :disabled="checking || actionBusy || job.running">刷新失效 ID</el-button><el-button v-if="job.running" :loading="cancelling" :disabled="actionBusy || job.cancelRequested" @click="cancel">{{ job.cancelRequested ? '正在停止…' : '停止任务' }}</el-button><el-button :icon="RefreshRight" :loading="checking" :disabled="checking || actionBusy" @click="loadStatus" aria-label="更新 Komga ID 刷新任务进度">更新进度</el-button></div>
    </form>
    <p v-if="error" class="panel-error" role="alert"><el-icon><WarningFilled /></el-icon>{{ error }}</p>

    <div v-if="job.jobId || job.status !== 'IDLE'" class="job-details">
      <div class="progress-heading"><span>{{ progressDescription }}</span><strong>{{ formatCount(job.processed) }} / {{ formatCount(job.total) }}</strong></div>
      <el-progress :percentage="percentage" :stroke-width="5" :show-text="false" :indeterminate="job.status === 'INDEXING'" :status="job.status === 'FAILED' ? 'exception' : undefined" />
      <div class="result-metrics"><div v-for="metric in metrics" :key="metric.key" :class="metric.key"><strong>{{ formatCount(metric.value) }}</strong><span>{{ metric.label }}</span></div></div>
      <div class="job-context"><span v-if="job.currentGid">正在处理 GID {{ job.currentGid }}</span><span v-if="job.startedAt">开始 {{ formatDate(job.startedAt) }}</span><span v-if="job.finishedAt">结束 {{ formatDate(job.finishedAt) }}</span><span v-if="job.cancelRequested && job.running">正在结束当前记录，已更新的 ID 会保留。</span></div>
      <p v-if="job.lastError" class="job-error" role="alert">{{ job.lastError }}</p>
      <template v-if="job.results?.length">
        <div class="result-heading"><h3>变更与异常明细</h3><span v-if="job.resultsTruncated">共 {{ formatCount(job.resultsTotal) }} 条变更与异常，仅展示前 {{ job.results.length }} 条；关联有效的记录不逐条列出。</span><span v-else>{{ job.results.length }} 条明细；关联有效的记录不逐条列出。</span></div>
        <el-table :data="job.results" class="result-table" :max-height="330" size="small" table-layout="fixed">
          <el-table-column prop="gid" label="GID" width="100" />
          <el-table-column label="处理结果" width="112"><template #default="{ row }"><el-tag :type="resultMeta(row.status).type" size="small">{{ resultMeta(row.status).label }}</el-tag></template></el-table-column>
          <el-table-column label="原 ID" width="170"><template #default="{ row }"><span class="book-id">{{ row.oldId || '未关联' }}</span></template></el-table-column>
          <el-table-column label="新 ID" width="170"><template #default="{ row }"><span class="book-id">{{ row.newId || '—' }}</span></template></el-table-column>
          <el-table-column label="说明" min-width="240"><template #default="{ row }"><div class="result-reason">{{ row.message || '—' }}</div></template></el-table-column>
        </el-table>
      </template>
    </div>
  </section>
</template>

<script setup>
// 启动并跟踪数据库 Komga ID 刷新任务，展示更新明细并允许停止当前任务。
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Refresh, RefreshRight, WarningFilled } from '@element-plus/icons-vue'
import api from '../api'

const initialJob = () => ({ status: 'IDLE', running: false, total: 0, processed: 0, updated: 0, unchanged: 0, notFound: 0, ambiguous: 0, conflicts: 0, failed: 0, results: [] })
const job = ref(initialJob()), gidInput = ref(''), error = ref('')
const checking = ref(false), starting = ref(false), cancelling = ref(false)
const actionBusy = computed(() => starting.value || cancelling.value)
const statusLabel = computed(() => ({ IDLE: '尚未运行', INDEXING: '读取图库索引', RUNNING: '正在检查关联', COMPLETED: '已完成', CANCELLED: '已停止', FAILED: '任务失败' })[job.value.status] || '任务状态')
const percentage = computed(() => Math.min(100, Math.max(0, Number(job.value.total) > 0 ? Number(job.value.processed) / Number(job.value.total) * 100 : 0)))
const progressDescription = computed(() => job.value.status === 'INDEXING' ? '正在读取 Komga 物理文件索引…' : job.value.running ? '检查并更新失效关联' : job.value.status === 'CANCELLED' ? '任务已停止，已完成的更新保留' : job.value.status === 'FAILED' ? '任务未完成，请查看原因后重试' : '检查完成')
const metrics = computed(() => [
  { key: 'updated', label: '已更新', value: job.value.updated },
  { key: 'unchanged', label: '关联有效', value: job.value.unchanged },
  { key: 'notFound', label: '未找到', value: job.value.notFound },
  { key: 'ambiguous', label: '多个候选', value: job.value.ambiguous },
  { key: 'conflicts', label: '关联冲突', value: job.value.conflicts },
  { key: 'failed', label: '失败', value: job.value.failed }
])
let disposed = false, timer, statusController, actionController

function formatCount(value) { return new Intl.NumberFormat('zh-CN').format(Number(value) || 0) }
function formatDate(value) { const date = new Date(value); return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false }) }
function resultMeta(status) { return ({ UPDATED: { label: '已更新', type: 'success' }, UNCHANGED: { label: '关联有效', type: 'info' }, NOT_FOUND: { label: '未找到', type: 'warning' }, AMBIGUOUS: { label: '多个候选', type: 'warning' }, CONFLICT: { label: '关联冲突', type: 'warning' }, FAILED: { label: '失败', type: 'danger' } })[status] || { label: status || '—', type: 'info' } }
// 合并完整任务默认值，避免刚启动或空结果时出现缺失字段。
function applyJob(value) { job.value = { ...initialJob(), ...(value || {}), results: Array.isArray(value?.results) ? value.results : [] } }
function clearPolling() { window.clearTimeout(timer); timer = undefined }
// 只在任务运行时安排下一次进度请求，避免重叠轮询和空闲请求。
function schedulePolling() {
  clearPolling()
  if (!disposed && job.value.running) timer = window.setTimeout(() => { void loadStatus() }, 2000)
}
function stopStatusRequest() { statusController?.abort(); statusController = null; checking.value = false }
// 解析用户输入并去重，拒绝不安全整数、非法字符和过大的指定范围。
function parseGids() {
  const text = gidInput.value.trim()
  if (!text) return []
  const values = text.split(/[\s,，;；]+/).filter(Boolean)
  if (values.some(value => !/^\d+$/.test(value) || !Number.isSafeInteger(Number(value)) || Number(value) <= 0)) throw new Error('GID 必须是有效的正整数，请用逗号或空格分隔。')
  const gids = [...new Set(values.map(Number))]
  if (gids.length > 1000) throw new Error('一次最多指定 1000 个 GID，请分批处理。')
  return gids
}
// 读取当前后台任务，进入页面时可恢复其他标签页已启动的任务。
async function loadStatus() {
  if (disposed || statusController || actionBusy.value) return
  const request = new AbortController()
  statusController = request; checking.value = true
  try {
    const response = await api.get('/komga-id-refresh/status', { signal: request.signal, silent: true })
    if (!disposed && statusController === request) { applyJob(response.data); error.value = '' }
  } catch (failure) {
    if (!disposed && statusController === request && failure.code !== 'ERR_CANCELED') error.value = failure.response?.data?.msg || failure.message || '任务进度读取失败，请重新更新进度。'
  } finally {
    if (statusController === request) { statusController = null; checking.value = false; schedulePolling() }
  }
}
// 校验范围后启动一次刷新；启动响应不确定时读取现有任务，避免盲目重复提交。
async function start() {
  if (disposed || actionBusy.value || checking.value || job.value.running) return
  let gids
  try { gids = parseGids() } catch (failure) { error.value = failure.message; return }
  clearPolling(); stopStatusRequest()
  const request = new AbortController()
  actionController = request; starting.value = true; error.value = ''
  let failed = false
  try {
    const response = await api.post('/komga-id-refresh', gids.length ? { gids } : {}, { signal: request.signal, silent: true })
    if (!disposed && actionController === request) applyJob(response.data)
  } catch (failure) {
    if (!disposed && actionController === request && failure.code !== 'ERR_CANCELED') { error.value = failure.response?.data?.msg || failure.message || '刷新任务启动失败。'; failed = true }
  } finally {
    if (actionController === request) { actionController = null; starting.value = false; schedulePolling() }
  }
  // 启动响应丢失或其他标签页已经启动任务时，重新读取进度，避免重复启动。
  if (failed && !disposed && localStorage.getItem('token')) { const message = error.value; await loadStatus(); if (!error.value) error.value = message }
}
// 携带当前任务 ID 请求停止；任务已换成新任务时只更新进度，避免误停其他任务。
async function cancel() {
  if (disposed || actionBusy.value || !job.value.running || job.value.cancelRequested) return
  const jobId = job.value.jobId
  if (!jobId) { error.value = '任务信息已变化，请先更新进度。'; await loadStatus(); return }
  clearPolling(); stopStatusRequest()
  const request = new AbortController()
  actionController = request; cancelling.value = true; error.value = ''
  let failed = false
  try {
    const response = await api.post('/komga-id-refresh/cancel', { jobId }, { signal: request.signal, silent: true })
    if (!disposed && actionController === request) applyJob(response.data)
  } catch (failure) {
    if (!disposed && actionController === request && failure.code !== 'ERR_CANCELED') { error.value = failure.response?.data?.msg || failure.message || '停止请求失败，请稍后重试。'; failed = true }
  } finally {
    if (actionController === request) { actionController = null; cancelling.value = false; schedulePolling() }
  }
  if (failed && !disposed && localStorage.getItem('token')) { const message = error.value; await loadStatus(); if (!error.value) error.value = message }
}
onMounted(() => { void loadStatus() })
onUnmounted(() => { disposed = true; clearPolling(); statusController?.abort(); actionController?.abort() })
</script>

<style scoped>
.id-refresh-panel { background: var(--app-surface, #fff); border: 1px solid var(--app-border, #e0e7f1); border-radius: var(--app-radius, 12px); padding: 22px; margin-bottom: 22px; min-width: 0; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.panel-heading-copy { min-width: 0; }
.panel-eyebrow { color: var(--app-accent); font-size: calc(9px * var(--app-font-scale, 1)); font-weight: 700; letter-spacing: .14em; margin: 0 0 6px; }
h2 { color: var(--app-text); font-size: calc(17px * var(--app-font-scale, 1)); margin: 0; line-height: 1.5; }
.panel-heading-copy > p:last-child { color: var(--app-text-muted); font-size: calc(11px * var(--app-font-scale, 1)); line-height: 1.8; margin: 8px 0 0; }
.job-state { display: inline-flex; align-items: center; gap: 6px; color: var(--app-text-muted); font-size: calc(10px * var(--app-font-scale, 1)); white-space: nowrap; padding-top: 3px; }
.job-state i { width: 6px; height: 6px; border-radius: 50%; background: #b7c3d5; }.job-state.active { color: var(--app-accent); }.job-state.active i { background: var(--app-accent); }
.refresh-form { display: flex; align-items: center; gap: 20px; margin-top: 20px; }
.gid-field { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 7px; }
.gid-field > span { color: var(--app-text-secondary); font-size: calc(11px * var(--app-font-scale, 1)); }.gid-field > span small { color: var(--app-text-muted); margin-left: 6px; font-size: calc(10px * var(--app-font-scale, 1)); }.gid-field > small { color: var(--app-text-muted); line-height: 1.7; font-size: calc(10px * var(--app-font-scale, 1)); }
.refresh-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; flex-shrink: 0; }.refresh-actions :deep(.el-button + .el-button) { margin-left: 0; }
.panel-error { display: flex; gap: 7px; align-items: flex-start; background: #fff5f2; color: #b76e50; border-radius: 6px; padding: 10px 12px; margin: 17px 0 0; font-size: calc(11px * var(--app-font-scale, 1)); line-height: 1.8; overflow-wrap: anywhere; }.panel-error .el-icon { flex-shrink: 0; margin-top: 4px; }
.job-details { border-top: 1px solid #edf1f7; margin-top: 21px; padding-top: 19px; min-width: 0; }
.progress-heading { display: flex; justify-content: space-between; gap: 12px; font-size: calc(11px * var(--app-font-scale, 1)); color: var(--app-text-secondary); margin-bottom: 10px; }.progress-heading strong { color: var(--app-text-muted); font-size: calc(11px * var(--app-font-scale, 1)); font-weight: 500; white-space: nowrap; font-variant-numeric: tabular-nums; }
.result-metrics { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); margin: 18px 0 15px; gap: 10px; }.result-metrics > div { padding: 12px; background: var(--app-surface-muted, #f7f9fc); border-radius: 7px; min-width: 0; }.result-metrics strong { display: block; color: var(--app-text); font-size: calc(22px * var(--app-font-scale, 1)); font-weight: 600; line-height: 1.4; font-variant-numeric: tabular-nums; }.result-metrics span { color: var(--app-text-muted); font-size: calc(10px * var(--app-font-scale, 1)); }.result-metrics .updated strong { color: #548878; }.result-metrics .failed strong { color: #b77570; }
.job-context { display: flex; gap: 10px 20px; flex-wrap: wrap; color: var(--app-text-muted); font-size: calc(10px * var(--app-font-scale, 1)); line-height: 1.8; }.job-error { color: #af7166; font-size: calc(11px * var(--app-font-scale, 1)); overflow-wrap: anywhere; line-height: 1.8; }
.result-heading { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; margin: 22px 0 11px; }.result-heading h3 { font-size: calc(12px * var(--app-font-scale, 1)); color: var(--app-text-secondary); margin: 0; font-weight: 600; }.result-heading > span { font-size: calc(10px * var(--app-font-scale, 1)); color: var(--app-text-muted); line-height: 1.8; }
.result-table { width: 100%; min-width: 0; border: 1px solid #edf1f7; border-radius: 7px; }.book-id { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: calc(10px * var(--app-font-scale, 1)); overflow-wrap: anywhere; }.result-reason { overflow-wrap: anywhere; font-size: calc(11px * var(--app-font-scale, 1)); }
@media (max-width: 1100px) { .refresh-form { flex-wrap: wrap; gap: 13px; }.gid-field { flex-basis: 100%; }.refresh-actions { justify-content: flex-start; }.result-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 560px) { .id-refresh-panel { padding: 16px; }.panel-heading { flex-wrap: wrap; gap: 8px; }.panel-heading-copy { flex-basis: 100%; }.refresh-actions { width: 100%; }.result-metrics { gap: 7px; }.result-metrics > div { padding: 10px 8px; }.result-metrics strong { font-size: calc(19px * var(--app-font-scale, 1)); }.progress-heading { align-items: flex-start; }.progress-heading > span { min-width: 0; } }
</style>
