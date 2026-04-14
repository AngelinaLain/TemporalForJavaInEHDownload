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

const tagStore = useTagStore()
tagStore.loadTranslations()

const stats = ref({})
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

const statCards = computed(() => [
  { label: '总画廊数', value: stats.value.total ?? '-', icon: markRaw(Files), color: '#409EFF' },
  { label: '已下载', value: stats.value.downloaded ?? '-', icon: markRaw(FolderChecked), color: '#67C23A' },
  { label: '已入库', value: stats.value.imported ?? '-', icon: markRaw(CircleCheck), color: '#E6A23C' },
  { label: '下载失败', value: stats.value.failed ?? '-', icon: markRaw(WarningFilled), color: '#F56C6C' },
  { label: '待下载', value: stats.value.pending ?? '-', icon: markRaw(Clock), color: '#909399' },
  { label: '总大小(GB)', value: stats.value.totalSizeGb ?? '-', icon: markRaw(DataLine), color: '#8B5CF6' }
])

const statusColors = {
  '未下载': '#909399', '下载中': '#409EFF', '已下载': '#67C23A',
  '下载失败': '#F56C6C', '已入库': '#E6A23C', '阻断': '#F56C6C', '已忽略': '#C0C4CC'
}

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
          itemStyle: { color: statusColors[item.name] || '#409EFF' }
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

const startAutoRefresh = () => {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = setInterval(loadData, 30000)
}

onMounted(() => {
  // 图表实例只在 mount 时创建一次
  statusChart = echarts.init(statusChartRef.value)
  sizeChart = echarts.init(sizeChartRef.value)
  timelineChart = echarts.init(timelineChartRef.value)
  tagChart = echarts.init(tagChartRef.value)
  loadData()
  startAutoRefresh()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
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
</style>
