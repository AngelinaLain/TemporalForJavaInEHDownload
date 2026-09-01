<template>
  <div class="dashboard">
    <!-- 统计卡片 -->
    <el-row :gutter="20" justify="center" class="stat-cards">
      <el-col :xs="12" :sm="8" :lg="4" v-for="card in statCards" :key="card.label">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" :style="{ background: card.color }">
            <el-icon :size="28"><component :is="card.icon" /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ card.value }}</div>
            <div class="stat-label">{{ card.label }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 下载进度 + 数据库状态 -->
    <el-row :gutter="20" class="chart-row">
      <el-col :xs="24" :lg="14">
        <el-card shadow="hover">
          <template #header>本地下载进度</template>
          <div v-if="downloads.length === 0" class="empty-hint">暂无进行中的下载</div>
          <div v-else class="download-list">
            <div v-for="d in downloads" :key="d.gid" class="download-item">
              <div class="download-title">
                <span class="gid">[{{ d.gid }}]</span>
                <span class="title">{{ d.title }}</span>
                <span class="percent">{{ d.percent }}%</span>
              </div>
              <el-progress :percentage="d.percent" :stroke-width="12" :show-text="false" />
              <div class="download-size">
                {{ formatBytes(d.downloadedBytes) }} / {{ formatBytes(d.totalBytes) }}
                <span v-if="d.sizeMb">（预估 {{ d.sizeMb }} MB）</span>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="10">
        <el-card shadow="hover">
          <template #header>数据库状态</template>
          <div v-if="dbStatus.connected">
            <div class="db-row"><el-tag type="success">连接正常</el-tag></div>
            <div class="db-row">总画廊数：{{ dbStatus.total ?? '-' }}</div>
            <div class="db-row">总大小：{{ dbStatus.totalSizeGb ?? '-' }} GB</div>
          </div>
          <div v-else>
            <div class="db-row"><el-tag type="danger">连接失败</el-tag></div>
            <div v-if="dbStatus.error" class="db-row error">{{ dbStatus.error }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 图表区域 -->
    <el-row :gutter="20" class="chart-row">
      <el-col :xs="24" :lg="12">
        <el-card shadow="hover">
          <template #header>下载状态分布</template>
          <div ref="statusChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="12">
        <el-card shadow="hover">
          <template #header>文件大小分布</template>
          <div ref="sizeChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" class="chart-row">
      <el-col :xs="24" :lg="14">
        <el-card shadow="hover">
          <template #header>抓取时间线</template>
          <div ref="timelineChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="10">
        <el-card shadow="hover">
          <template #header>标签分类 Top 20</template>
          <div ref="tagChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, markRaw } from 'vue'
import * as echarts from 'echarts'
import { Files, FolderChecked, WarningFilled, Clock, CircleCheck, DataLine } from '@element-plus/icons-vue'
import api from '../api'
import { useTagStore } from '../stores/tagStore'
import { getStatusMeta } from '../constants/status'

const tagStore = useTagStore()
tagStore.loadTranslations()

const stats = ref({})
const downloads = ref([])
const dbStatus = ref({})
const statusChartRef = ref()
const sizeChartRef = ref()
const timelineChartRef = ref()
const tagChartRef = ref()

// 保存图表实例，整个组件生命周期内只创建一次，刷新时仅调用 setOption
let statusChart = null
let sizeChart = null
let timelineChart = null
let tagChart = null
let refreshTimer = null
let progressTimer = null

const statCards = computed(() => [
  { label: '总画廊数', value: stats.value.total ?? '-', icon: markRaw(Files), color: '#409EFF' },
  { label: '已下载', value: stats.value.downloaded ?? '-', icon: markRaw(FolderChecked), color: '#67C23A' },
  { label: '已入库', value: stats.value.imported ?? '-', icon: markRaw(CircleCheck), color: '#E6A23C' },
  { label: '下载失败', value: stats.value.failed ?? '-', icon: markRaw(WarningFilled), color: '#F56C6C' },
  { label: '待下载', value: stats.value.pending ?? '-', icon: markRaw(Clock), color: '#909399' },
  { label: '总大小(GB)', value: stats.value.totalSizeGb ?? '-', icon: markRaw(DataLine), color: '#8B5CF6' }
])


const loadData = async () => {
  try {
    const [statsRes, statusRes, sizeRes, timelineRes, tagRes] = await Promise.all([
      api.get('/dashboard/stats'),
      api.get('/dashboard/status-distribution'),
      api.get('/dashboard/file-size-distribution'),
      api.get('/dashboard/crawl-timeline'),
      api.get('/dashboard/tag-stats')
    ])

    stats.value = statsRes.data

    // 直接更新已有图表实例的数据，不 dispose/reinit，避免每次刷新闪烁
    statusChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0 },
      series: [{
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { show: true, formatter: '{b}\n{c}' },
        data: statusRes.data.map(item => ({
          ...item,
          name: getStatusMeta(item.name).label,
          itemStyle: { color: getStatusMeta(item.name).color }
        }))
      }]
    }, true)

    sizeChart.setOption({
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: sizeRes.data.labels },
      yAxis: { type: 'value', name: '数量' },
      series: [{
        type: 'bar',
        data: sizeRes.data.data,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#409EFF' },
            { offset: 1, color: '#79bbff' }
          ]),
          borderRadius: [4, 4, 0, 0]
        }
      }]
    }, true)

    timelineChart.setOption({
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: timelineRes.data.dates, axisLabel: { rotate: 45 } },
      yAxis: { type: 'value', name: '数量' },
      series: [{
        type: 'line',
        data: timelineRes.data.counts,
        smooth: true,
        areaStyle: { opacity: 0.3 },
        lineStyle: { width: 2 },
        itemStyle: { color: '#667eea' }
      }]
    }, true)

    const tagData = tagRes.data.reverse()
    tagChart.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 100 },
      xAxis: { type: 'value' },
      yAxis: { type: 'category', data: tagData.map(t => t.nameCn || tagStore.translateNs(t.name) || t.name) },
      series: [{
        type: 'bar',
        data: tagData.map(t => t.value),
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
            { offset: 0, color: '#764ba2' },
            { offset: 1, color: '#667eea' }
          ]),
          borderRadius: [0, 4, 4, 0]
        }
      }]
    }, true)
  } catch {
    // handled by interceptor
  }
}

const handleResize = () => {
  [statusChart, sizeChart, timelineChart, tagChart].forEach(c => c?.resize())
}

const formatBytes = (bytes) => {
  const b = Number(bytes) || 0
  if (b < 1024) return b + ' B'
  if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB'
  if (b < 1024 * 1024 * 1024) return (b / 1024 / 1024).toFixed(1) + ' MB'
  return (b / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

const loadDownloadProgress = async () => {
  try {
    const res = await api.get('/dashboard/download-progress')
    downloads.value = res.data || []
  } catch {
    // handled by interceptor
  }
}

const loadDbStatus = async () => {
  try {
    const res = await api.get('/dashboard/db-status')
    dbStatus.value = res.data || {}
  } catch {
    // handled by interceptor
  }
}

const startAutoRefresh = () => {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = setInterval(loadData, 30000)
  if (progressTimer) clearInterval(progressTimer)
  progressTimer = setInterval(loadDownloadProgress, 3000)
}

onMounted(() => {
  // 图表实例只在 mount 时创建一次
  statusChart = echarts.init(statusChartRef.value)
  sizeChart = echarts.init(sizeChartRef.value)
  timelineChart = echarts.init(timelineChartRef.value)
  tagChart = echarts.init(tagChartRef.value)
  loadData()
  loadDownloadProgress()
  loadDbStatus()
  startAutoRefresh()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
  if (progressTimer) {
    clearInterval(progressTimer)
    progressTimer = null
  }
  window.removeEventListener('resize', handleResize)
  ;[statusChart, sizeChart, timelineChart, tagChart].forEach(c => c?.dispose())
})
</script>

<style scoped>
.dashboard {
  padding: 0;
}

.stat-cards {
  margin-bottom: 20px;
}

.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  box-sizing: border-box;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
}

.stat-info {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.stat-value {
  font-size: 22px;
  font-weight: 700;
  color: #303133;
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
  white-space: nowrap;
}

.chart-row {
  margin-bottom: 20px;
}

.chart-container {
  height: 350px;
  width: 100%;
}

.empty-hint {
  color: #909399;
  text-align: center;
  padding: 24px 0;
  font-size: 14px;
}

.download-list {
  max-height: 320px;
  overflow-y: auto;
}

.download-item {
  padding: 10px 0;
  border-bottom: 1px solid #f0f0f0;
}

.download-item:last-child {
  border-bottom: none;
}

.download-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.download-title .gid {
  color: #909399;
  font-size: 12px;
  flex-shrink: 0;
}

.download-title .title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #303133;
  font-size: 14px;
}

.download-title .percent {
  color: #409eff;
  font-weight: 600;
  flex-shrink: 0;
}

.download-size {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.db-row {
  margin-bottom: 10px;
  font-size: 14px;
  color: #303133;
}

.db-row.error {
  color: #f56c6c;
  word-break: break-all;
}

</style>
