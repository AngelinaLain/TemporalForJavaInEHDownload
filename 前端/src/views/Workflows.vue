<template>
  <div class="workflows-page">
    <div class="toolbar">
      <span class="hint">每 5 秒自动刷新；点击行展开查看子流程；「日志」查看该流程的运行事件。</span>
      <el-button type="primary" :icon="Refresh" @click="loadWorkflows">刷新</el-button>
    </div>

    <el-table
      :data="roots"
      row-key="workflowId"
      :tree-props="{ children: 'children' }"
      default-expand-all
      v-loading="loading"
      size="small"
      class="workflow-table"
    >
      <el-table-column prop="workflowId" label="工作流 ID" min-width="220" show-overflow-tooltip />
      <el-table-column prop="type" label="类型" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <el-tag size="small" :type="typeTag(row.type)" effect="plain">{{ row.type }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="130">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="开始时间" width="170">
        <template #default="{ row }">{{ formatTime(row.startTime) }}</template>
      </el-table-column>
      <el-table-column label="结束时间" width="170">
        <template #default="{ row }">{{ formatTime(row.closeTime) }}</template>
      </el-table-column>
      <el-table-column prop="historyLength" label="事件数" width="80" />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link :icon="Document" @click="openLogs(row)">日志</el-button>
          <el-button
            v-if="row.status === 'RUNNING'"
            size="small"
            type="danger"
            link
            :icon="CircleClose"
            @click="terminate(row)"
          >终止</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="logVisible"
      :title="`运行日志 - ${logWorkflowId}`"
      width="78%"
      top="6vh"
      destroy-on-close
      @closed="stopLogPolling"
    >
      <div class="log-toolbar">
        <el-tag size="small" :type="statusTag(logStatus)">{{ statusLabel(logStatus) }}</el-tag>
        <el-switch v-model="autoScroll" active-text="自动滚动" style="margin-left: 12px" />
        <el-button size="small" :icon="Refresh" @click="loadHistory">刷新</el-button>
      </div>
      <div ref="logBoxRef" class="log-box">
        <div v-if="logLines.length === 0" class="log-empty">暂无事件</div>
        <div
          v-for="line in logLines"
          :key="line.eventId"
          class="log-line"
          :class="line.level"
        >
          <span class="log-time">{{ formatTime(line.time * 1000) }}</span>
          <span class="log-msg">{{ line.message }}</span>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Document, CircleClose } from '@element-plus/icons-vue'
import api from '../api'

const roots = ref([])
const loading = ref(false)
const refreshTimer = ref(null)

const logVisible = ref(false)
const logWorkflowId = ref('')
const logRunId = ref('')
const logStatus = ref('')
const logLines = ref([])
const autoScroll = ref(true)
const logBoxRef = ref()
const logPollTimer = ref(null)

const STATUS_META = {
  RUNNING: { label: '运行中', tag: 'primary' },
  COMPLETED: { label: '已完成', tag: 'success' },
  FAILED: { label: '失败', tag: 'danger' },
  TERMINATED: { label: '已终止', tag: 'warning' },
  CANCELED: { label: '已取消', tag: 'info' },
  TIMED_OUT: { label: '已超时', tag: 'danger' },
  CONTINUED_AS_NEW: { label: '已续跑', tag: 'info' }
}

const TYPE_META = {
  EHAutomationWorkflow: 'primary',
  RetryFailedDownloadWorkflow: 'warning',
  SingleGalleryDownloadWorkflow: 'success',
  KomgaImportWorkflow: 'info'
}

const statusLabel = (status) => STATUS_META[status]?.label || status
const statusTag = (status) => STATUS_META[status]?.tag || 'info'
const typeTag = (type) => TYPE_META[type] || 'primary'

const formatTime = (value) => {
  if (!value) return '-'
  const date = typeof value === 'number' ? new Date(value) : new Date(value)
  if (isNaN(date.getTime())) return '-'
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
         `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

const loadWorkflows = async () => {
  loading.value = true
  try {
    const res = await api.get('/temporal/monitor/workflows')
    roots.value = res.data || []
  } catch {
    // handled by interceptor
  } finally {
    loading.value = false
  }
}

const loadHistory = async () => {
  try {
    const res = await api.get(`/temporal/monitor/workflows/${logWorkflowId.value}/history`, {
      params: { runId: logRunId.value || undefined }
    })
    logLines.value = res.data || []
    if (autoScroll.value) {
      await nextTick()
      if (logBoxRef.value) {
        logBoxRef.value.scrollTop = logBoxRef.value.scrollHeight
      }
    }
  } catch {
    // handled by interceptor
  }
}

const openLogs = (row) => {
  logWorkflowId.value = row.workflowId
  logRunId.value = row.runId
  logStatus.value = row.status
  logLines.value = []
  logVisible.value = true
  loadHistory()
  if (row.status === 'RUNNING') {
    logPollTimer.value = setInterval(loadHistory, 4000)
  }
}

const stopLogPolling = () => {
  if (logPollTimer.value) {
    clearInterval(logPollTimer.value)
    logPollTimer.value = null
  }
}

const terminate = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定要终止工作流「${row.workflowId}」吗？其子流程需单独终止。`,
      '终止确认',
      { type: 'warning', confirmButtonText: '终止', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await api.post(`/temporal/monitor/workflows/${row.workflowId}/terminate`, null, {
      params: { runId: row.runId, reason: '用户在监控页面手动终止' }
    })
    ElMessage.success('终止指令已发送')
    loadWorkflows()
  } catch {
    // handled by interceptor
  }
}

onMounted(() => {
  loadWorkflows()
  refreshTimer.value = setInterval(loadWorkflows, 5000)
})

onUnmounted(() => {
  if (refreshTimer.value) {
    clearInterval(refreshTimer.value)
  }
  stopLogPolling()
})
</script>

<style scoped>
.workflows-page {
  padding: 8px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.hint {
  color: #909399;
  font-size: 13px;
}

.workflow-table {
  background: #fff;
}

.log-toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}

.log-box {
  height: 60vh;
  overflow-y: auto;
  background: #1e1e2e;
  border-radius: 6px;
  padding: 12px;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.7;
}

.log-empty {
  color: #666;
  text-align: center;
  padding: 40px 0;
}

.log-line {
  display: flex;
  gap: 10px;
  color: #d4d4d4;
}

.log-line.error {
  color: #f56c6c;
}

.log-line.warn {
  color: #e6a23c;
}

.log-time {
  flex-shrink: 0;
  color: #7f849c;
}

.log-msg {
  word-break: break-all;
}
</style>
