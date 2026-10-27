<template>
  <div ref="dashboardRef" class="dashboard-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">LIBRARY INTELLIGENCE</p>
        <h1>数据概览<span>看清规模、结构与待处理事项</span></h1>
        <p class="page-description">在同一筛选范围内比较内容分布，点击统计项进入对应画廊，继续检索与处理。</p>
      </div>
      <div class="heading-actions">
        <span class="update-time">{{ lastUpdated ? '更新于 ' + lastUpdated : '等待数据' }}</span>
        <button class="button secondary fullscreen-button" @click="toggleFullscreen"><el-icon><FullScreen /></el-icon>{{ fullscreen ? '退出大屏' : '全屏大屏' }}</button>
        <button class="button secondary" :disabled="loading" @click="refresh"><el-icon :class="{ spinning: loading }"><Refresh /></el-icon>刷新</button>
      </div>
    </header>

    <form class="filter-panel" @submit.prevent="applyFilters">
      <div class="filter-topline">
        <span class="filter-title"><el-icon><Filter /></el-icon>分析范围</span>
        <div class="time-presets">
          <button v-for="item in presets" :key="item.days" type="button" :class="{ active: draft.days === item.days && !draft.from && !draft.to }" @click="selectPreset(item.days)">{{ item.label }}</button>
        </div>
        <label class="auto-refresh">
        <input v-model="autoRefresh" type="checkbox" />每 30 秒更新</label>
      </div>
      <div class="filter-fields">
        <label><span>抓取日期起</span><input v-model="draft.from" type="date" aria-label="抓取开始日期" @change="draft.days = 0" /></label>
        <label><span>抓取日期止</span><input v-model="draft.to" type="date" aria-label="抓取结束日期" @change="draft.days = 0" /></label>
        <label><span>当前状态</span><select v-model="draft.status" aria-label="当前下载状态"><option value="">全部状态</option><option v-for="status in STATUS_OPTIONS" :key="status.value" :value="status.value">{{ status.label }}</option></select></label>
        <label><span>作品版本</span><select v-model="draft.dedupe" aria-label="作品版本"><option value="all">全部版本</option><option value="preferred">首选版本</option><option value="duplicates">重复版本</option></select></label>
        <label><span>合集归属</span><select v-model="draft.collectionScope" aria-label="合集归属" @change="draft.collectionId = ''"><option value="all">全部记录</option><option value="assigned">已归入合集</option><option value="unassigned">独立画廊</option></select></label>
        <label class="collection-filter"><span>指定合集</span><select v-model="draft.collectionId" aria-label="指定合集" :disabled="draft.collectionScope === 'unassigned'"><option value="">不限合集</option><option v-for="item in collectionOptions" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
        <label><span>时间粒度</span><select v-model="draft.grain" aria-label="时间粒度"><option value="day">按日</option><option value="week">按周</option><option value="month">按月</option></select></label>
        <button class="button primary" type="submit" :disabled="loading">应用范围</button><button class="text-button reset-button" type="button" @click="resetFilters">重置</button>
      </div>
      <div v-if="filterValidation" class="validation-message" role="alert">{{ filterValidation }}</div>
    </form>

    <div v-if="analyticsError" class="feedback error-feedback" role="alert"><el-icon><Warning /></el-icon><span>{{ analyticsError }}{{ hasLoaded ? '，保留上一次成功的数据。' : '。' }}</span><button class="text-button" @click="refresh">重试</button></div>
    <p v-if="displayHint" class="measurement-note" role="status">{{ displayHint }}</p>
    <div class="scope-bar"><span><i />{{ scopeDescription }}</span><span v-if="hasPendingFilters" class="pending-note">有尚未应用的筛选</span><div class="scope-actions"><div class="view-switch" role="group" aria-label="统计查看方式"><button :class="{ active: viewMode === 'chart' }" :aria-pressed="viewMode === 'chart'" @click="viewMode = 'chart'"><el-icon><DataAnalysis /></el-icon>图表</button><button :class="{ active: viewMode === 'table' }" :aria-pressed="viewMode === 'table'" @click="viewMode = 'table'"><el-icon><List /></el-icon>明细表</button></div><button class="text-button graph-link" @click="router.push('/tag-network')">打开关系图谱<el-icon><ArrowRight /></el-icon></button></div></div>

    <section class="metric-grid" :class="{ 'initial-loading': loading && !hasLoaded }" aria-label="关键统计">
      <button v-for="metric in metrics" :key="metric.label" class="metric-card" :class="metric.tone" :disabled="!hasLoaded || metric.value === '—' || metric.value === '0'" @click="openGalleries(metric.filter)">
        <div class="metric-top"><span>{{ metric.label }}</span><el-icon><component :is="metric.icon" /></el-icon></div><strong>{{ metric.value }}</strong><div class="metric-bottom"><small>{{ metric.note }}</small><el-icon><ArrowRight /></el-icon></div>
      </button>
    </section>
    <p class="measurement-note">以上是筛选范围内记录的当前状态；容量来自画廊元数据，不能视为实际磁盘占用或某日入库吞吐量。</p>

    <section class="primary-grid">
      <article class="panel trend-panel">
        <header class="panel-heading"><div><h2>采集节奏</h2><p>按抓取时间观察记录与容量的分布</p></div><div class="small-tabs" role="group" aria-label="趋势指标"><button :class="{ active: trendMetric === 'count' }" @click="trendMetric = 'count'">画廊数</button><button :class="{ active: trendMetric === 'size' }" @click="trendMetric = 'size'">容量</button></div></header>
        <div class="trend-summary"><strong>{{ formatCount(trendMetric === 'count' ? timelineTotal : timelineSizeGb, trendMetric === 'count' ? 0 : 1) }}<small>{{ trendMetric === 'count' ? '条记录' : 'GB 元数据容量' }}</small></strong><span>{{ grainLabel }} · {{ timelineRangeLabel }}</span></div>
        <div v-show="viewMode === 'chart'" class="chart-wrap"><div ref="timelineRef" class="chart trend-chart" /><div v-if="!timeline.length && !loading" class="chart-empty">当前范围暂无时间数据</div></div>
        <div v-if="viewMode === 'table'" class="table-wrap trend-table"><table><thead><tr><th>抓取周期</th><th>画廊数</th><th>容量 / GB</th><th /></tr></thead><tbody><tr v-for="row in timeline" :key="row.date"><td>{{ row.date }}</td><td>{{ formatCount(row.value) }}</td><td>{{ formatCount(Number(row.size_mb || 0) / 1024, 2) }}</td><td><button class="text-button" :disabled="!canDrill(row)" @click="drill(row)">查看</button></td></tr><tr v-if="!timeline.length"><td colspan="4" class="empty-cell">暂无时间数据</td></tr></tbody></table></div>
        <footer class="panel-footer">{{ analytics.timelineRange?.truncated ? '时间线仅展示最近一段周期；上方总体指标仍覆盖完整筛选范围。' : '点击时间点或“查看”可进入该周期内的画廊。' }}</footer>
      </article>
      <article class="panel">
        <header class="panel-heading"><div><h2>当前状态分布</h2><p>定位等待、异常与已完成记录</p></div><span class="panel-chip">{{ statusRows.length }} 种状态</span></header>
        <div v-show="viewMode === 'chart'" class="chart-wrap"><div ref="statusRef" class="chart status-chart" /><div v-if="!statusRows.length && !loading" class="chart-empty">当前范围暂无状态数据</div></div>
        <div v-if="viewMode === 'table'" class="table-wrap status-table">
          <table>
            <thead><tr><th>状态</th><th>数量</th><th>占比</th></tr></thead>
            <tbody><tr v-for="row in statusRows" :key="row.name"><td><button class="table-link" @click="drill(row)"><i :style="{ background: getStatusMeta(row.name).color }" />{{ getStatusMeta(row.name).label }}</button></td><td>{{ formatCount(row.value) }}</td><td>{{ percent(row.value) }}%</td></tr><tr v-if="!statusRows.length"><td colspan="3" class="empty-cell">暂无状态数据</td></tr></tbody></table>
        </div>
        <footer class="panel-footer">状态是当前记录快照，点击扇区筛选对应记录。</footer>
      </article>
    </section>

    <section class="secondary-grid">
      <article class="panel content-panel">
        <header class="panel-heading"><div><h2>内容结构</h2><p>在评分、篇幅、体积与作品版本间切换</p></div></header>
        <div class="dimension-tabs" role="group" aria-label="内容结构维度"><button v-for="item in dimensions" :key="item.key" :class="{ active: dimension === item.key }" @click="dimension = item.key">{{ item.label }}</button></div>
        <div v-show="viewMode === 'chart'" class="chart-wrap"><div ref="contentRef" class="chart content-chart" /><div v-if="!contentRows.length && !loading" class="chart-empty">当前维度暂无数据</div></div>
        <div v-if="viewMode === 'table'" class="table-wrap content-table"><table><thead><tr><th>区间 / 分类</th><th>画廊数</th><th>占比</th><th /></tr></thead><tbody><tr v-for="row in contentRows" :key="row.name"><td>{{ row.name }}</td><td>{{ formatCount(row.value) }}</td><td>{{ percent(row.value) }}%</td><td><button class="text-button" :disabled="!canDrill(row)" @click="drill(row)">查看</button></td></tr><tr v-if="!contentRows.length"><td colspan="4" class="empty-cell">暂无数据</td></tr></tbody></table></div>
        <footer class="panel-footer">{{ dimensionDescription }}；点击区间查看对应画廊。</footer>
      </article>
      <article class="panel collection-panel">
        <header class="panel-heading"><div><h2>合集与独立画廊</h2><p>查看内容如何归组</p></div><button class="text-button" @click="router.push('/collections')">管理合集<el-icon><ArrowRight /></el-icon></button></header>
        <div class="collection-summary">
          <div>
            <strong>{{ formatCount(overview.assigned) }}</strong>
            <span>已归入合集</span>
          </div>
          <div>
            <strong>{{ formatCount(Math.max(0, total - Number(overview.assigned || 0))) }}</strong>
            <span>独立画廊</span>
          </div>
          <span class="completion-ring">{{ percent(overview.assigned) }}<small>% 归组率</small></span>
        </div>
        <div v-show="viewMode === 'chart'" class="chart-wrap">
          <div ref="collectionRef" class="chart collection-chart" />
          <div v-if="!collectionRows.length && !loading" class="chart-empty">暂无合集归属数据</div>
        </div>
        <div v-if="viewMode === 'table'" class="table-wrap collection-table">
          <table>
            <thead>
              <tr>
                <th>合集</th>
                <th>画廊数</th>
                <th />
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in collectionRows" :key="row.id || row.name">
                <td>{{ row.name }}</td>
                <td>{{ formatCount(row.value) }}</td>
                <td><button class="text-button" :disabled="!canDrill(row)" @click="drill(row)">查看</button></td>
              </tr>
                <tr v-if="!collectionRows.length">
                <td colspan="3" class="empty-cell">暂无合集数据</td>
              </tr>
            </tbody>
          </table>
        </div>
        <footer class="panel-footer">仅展示数量靠前的合集，完整归属可在画廊列表中检索。</footer>
      </article>
    </section>

    <section class="three-grid">
      <article class="panel">
        <header class="panel-heading"><div><h2>语言标签</h2><p>从语言视角发现内容</p></div><span class="panel-chip">TOP {{ languageRows.length || '—' }}</span></header>
        <div v-show="viewMode === 'chart'" class="chart-wrap"><div ref="languageRef" class="chart ranking-chart" /><div v-if="!languageRows.length && !loading" class="chart-empty">暂无语言标签</div></div>
        <div v-if="viewMode === 'table'" class="table-wrap ranking-table"><table><thead><tr><th>语言</th><th>出现数</th><th /></tr></thead><tbody><tr v-for="row in languageRows" :key="row.name"><td>{{ languageLabel(row.name) }}</td><td>{{ formatCount(row.value) }}</td><td><button class="text-button" :disabled="!canDrill(row)" @click="drill(row)">查看</button></td></tr><tr v-if="!languageRows.length"><td colspan="3" class="empty-cell">暂无数据</td></tr></tbody></table></div>
        <footer class="panel-footer">按语言标签计数，同一画廊可带有多个语言标签。</footer>
      </article>
      <article class="panel">
        <header class="panel-heading"><div><h2>检索来源</h2><p>采集记录使用的检索关键词</p></div><span class="panel-chip">TOP {{ queryRows.length || '—' }}</span></header>
        <div v-show="viewMode === 'chart'" class="chart-wrap"><div ref="queryRef" class="chart ranking-chart" /><div v-if="!queryRows.length && !loading" class="chart-empty">暂无检索来源</div></div>
        <div v-if="viewMode === 'table'" class="table-wrap ranking-table"><table><thead><tr><th>来源关键词</th><th>画廊数</th><th /></tr></thead><tbody><tr v-for="row in queryRows" :key="row.name"><td>{{ row.name }}</td><td>{{ formatCount(row.value) }}</td><td><button class="text-button" :disabled="!canDrill(row)" @click="drill(row)">查看</button></td></tr><tr v-if="!queryRows.length"><td colspan="3" class="empty-cell">暂无数据</td></tr></tbody></table></div>
        <footer class="panel-footer">此处是采集时记录的关键词，不是站内搜索次数。</footer>
      </article>
      <article class="panel quality-panel">
        <header class="panel-heading"><div><h2>元数据待完善</h2><p>快速定位缺失信息</p></div><el-icon class="quality-icon"><DocumentChecked /></el-icon></header>
        <div class="quality-list"><button v-for="row in qualityRows" :key="row.name" :disabled="!canDrill(row)" @click="drill(row)"><div><span>{{ row.name }}</span><strong>{{ formatCount(row.value) }}<small>{{ percent(row.value) }}%</small></strong></div><span class="quality-track"><i :style="{ width: Math.min(100, Number(percent(row.value))) + '%' }" /></span></button><div v-if="!qualityRows.length" class="quality-empty">暂无缺失项统计</div></div>
        <div class="quality-note"><el-icon><InfoFilled /></el-icon><span>同一画廊可能同时缺失多项信息，各项数量不能相加作为缺失画廊总数。</span></div>
      </article>
    </section>

    <section class="operations-grid">
      <article class="panel downloads-panel">
        <header class="panel-heading"><div><h2>实时下载</h2><p>当前全局运行任务，不受上方内容范围限制</p></div><span class="live-badge"><i />{{ downloadLoaded ? downloads.length + ' 个进行中' : '等待运行状态' }}</span></header>
        <div v-if="servicesError" class="service-error">{{ servicesError }}</div>
        <div v-if="downloads.length" class="download-list"><button v-for="item in downloads" :key="item.gid" class="download-row" @click="openGalleries({ gid: String(item.gid) }, false)"><div class="download-name"><span>#{{ item.gid }}</span><strong>{{ item.title }}</strong><em>{{ Number(item.percent || 0).toFixed(1) }}%</em></div><div class="download-progress"><i :style="{ width: Math.max(0, Math.min(100, Number(item.percent || 0))) + '%' }" /></div><div class="download-size">{{ formatBytes(item.downloadedBytes) }} / {{ formatBytes(item.totalBytes) }}</div></button></div>
        <div v-else class="download-empty"><el-icon><CircleCheck /></el-icon><span>{{ downloadLoaded ? '当前没有进行中的下载' : '尚未获取到运行状态' }}</span><small>点击刷新可获取最新运行状态。</small></div>
      </article>
      <article class="panel service-panel">
        <header class="panel-heading"><div><h2>服务与数据存量</h2><p>数据库连通性与全局规模</p></div></header>
        <div class="database-status" :class="{ connected: dbStatus.connected, disconnected: dbStatus.connected === false }"><i /><span>{{ dbStatus.connected === true ? '数据库连接正常' : dbStatus.connected === false ? '数据库连接异常' : '等待服务状态' }}</span></div>
        <div class="service-metrics"><div><span>全局记录数</span><strong>{{ dbStatus.total == null ? '—' : formatCount(dbStatus.total) }}</strong></div><div><span>元数据容量</span><strong>{{ dbStatus.totalSizeGb == null ? '—' : formatCount(dbStatus.totalSizeGb, 2) }}<small>GB</small></strong></div></div>
        <button class="service-link" @click="router.push('/monitoring')"><span><el-icon><Monitor /></el-icon>进入服务监控</span><el-icon><ArrowRight /></el-icon></button>
      </article>
    </section>
  </div>
</template>

<script setup>
import { computed, markRaw, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GraphicComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { ArrowRight, CircleCheck, Collection, DataAnalysis, DocumentChecked, Files, Filter, FolderChecked, FullScreen, InfoFilled, Link, List, Monitor, Refresh, StarFilled, Tickets, Warning } from '@element-plus/icons-vue'
import api from '../api'
import { STATUS_OPTIONS, getStatusMeta } from '../constants/status'
import { scaleChartFonts } from '../utils/scaleChartFonts'

echarts.use([BarChart, LineChart, PieChart, GraphicComponent, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])
const router = useRouter()
const dashboardRef = ref(null)
const fullscreen = ref(false)
const displayHint = ref('')
const defaults = { days: 30, from: '', to: '', status: '', dedupe: 'all', collectionScope: 'all', collectionId: '', grain: 'day' }
const draft = reactive({ ...defaults })
const applied = reactive({ ...defaults })
const presets = [{ days: 7, label: '近 7 天' }, { days: 30, label: '近 30 天' }, { days: 90, label: '近 90 天' }, { days: 0, label: '全部时间' }]
const dimensions = [{ key: 'rating', label: '评分' }, { key: 'pages', label: '页数' }, { key: 'size', label: '体积' }, { key: 'dedupe', label: '版本' }]
const analytics = ref({ overview: {}, status: [], timeline: [], rating: [], pages: [], size: [], dedupe: [], queries: [], collections: [], languages: [], quality: [] })
const collectionOptions = ref([])
const downloads = ref([])
const downloadLoaded = ref(false)
const dbStatus = ref({})
const loading = ref(false)
const hasLoaded = ref(false)
const autoRefresh = ref(true)
const analyticsError = ref('')
const servicesError = ref('')
const filterValidation = ref('')
const lastUpdated = ref('')
const dimension = ref('rating')
const trendMetric = ref('count')
const viewMode = ref('chart')
const timelineRef = ref(null), statusRef = ref(null), contentRef = ref(null), collectionRef = ref(null), languageRef = ref(null), queryRef = ref(null)
const charts = new Map()
let analyticsController, serviceController, collectionController, resizeObserver, refreshTimer, servicesBusy = false, disposed = false

const overview = computed(() => analytics.value.overview || {})
const total = computed(() => Number(overview.value.total || 0))
const timeline = computed(() => analytics.value.timeline || [])
const statusRows = computed(() => (analytics.value.status || []).filter(row => Number(row.value) > 0))
const contentRows = computed(() => analytics.value[dimension.value] || [])
const collectionRows = computed(() => analytics.value.collections || [])
const languageRows = computed(() => analytics.value.languages || [])
const queryRows = computed(() => analytics.value.queries || [])
const qualityRows = computed(() => analytics.value.quality || [])
const timelineTotal = computed(() => timeline.value.reduce((sum, row) => sum + Number(row.value || 0), 0))
const timelineSizeGb = computed(() => timeline.value.reduce((sum, row) => sum + Number(row.size_mb || 0), 0) / 1024)
const grainLabel = computed(() => ({ day: '按日', week: '按周', month: '按月' })[applied.grain] || '按日')
const hasPendingFilters = computed(() => Object.keys(defaults).some(key => String(draft[key] ?? '') !== String(applied[key] ?? '')))
const scopeDescription = computed(() => {
  const range = applied.from || applied.to ? (applied.from || '最早') + ' 至 ' + (applied.to || '今天') : applied.days ? '近 ' + applied.days + ' 天' : '全部时间'
  const parts = [range, applied.status ? getStatusMeta(applied.status).label : '全部状态', ({ all: '全部版本', preferred: '首选版本', duplicates: '重复版本' })[applied.dedupe]]
  if (applied.collectionId) parts.push(collectionOptions.value.find(item => String(item.id) === String(applied.collectionId))?.name || '指定合集')
  else if (applied.collectionScope !== 'all') parts.push(applied.collectionScope === 'assigned' ? '已归入合集' : '独立画廊')
  return parts.join(' · ')
})
const timelineRangeLabel = computed(() => {
  const range = analytics.value.timelineRange
  if (range?.from && range?.to) return range.from + ' — ' + range.to
  return timeline.value.length ? timeline.value[0].date + ' — ' + timeline.value[timeline.value.length - 1].date : '暂无周期'
})
const dimensionDescription = computed(() => ({ rating: '社区评分分层，未评分单独列出', pages: '按画廊记录的页数分层', size: '按元数据文件大小分层', dedupe: '按首选与重复版本归类' })[dimension.value])
const metrics = computed(() => [
  { label: '范围内画廊', value: hasLoaded.value ? formatCount(total.value) : '—', note: '点击浏览当前范围', icon: markRaw(Files), tone: 'blue', filter: {} },
  { label: '元数据容量', value: hasLoaded.value ? formatCount(Number(overview.value.size_mb || 0) / 1024, 1) + ' GB' : '—', note: '按文件体积排序查看', icon: markRaw(DataAnalysis), tone: 'violet', filter: { sort: 'fileSizeMb', order: 'desc' } },
  { label: '当前已入库', value: hasLoaded.value ? formatCount(overview.value.imported) : '—', note: '占当前范围 ' + percent(overview.value.imported) + '%', icon: markRaw(FolderChecked), tone: 'green', filter: { status: 'IMPORTED' } },
  { label: '已关联 Komga', value: hasLoaded.value ? formatCount(overview.value.linked) : '—', note: '占当前范围 ' + percent(overview.value.linked) + '%', icon: markRaw(Link), tone: 'cyan', filter: { komga: 'linked' } },
  { label: '已归入合集', value: hasLoaded.value ? formatCount(overview.value.assigned) : '—', note: '归组率 ' + percent(overview.value.assigned) + '%', icon: markRaw(Collection), tone: 'violet', filter: { collectionScope: 'assigned' } },
  { label: '重复版本', value: hasLoaded.value ? formatCount(overview.value.duplicates) : '—', note: '占当前范围 ' + percent(overview.value.duplicates) + '%', icon: markRaw(DocumentChecked), tone: 'amber', filter: { dedupe: 'duplicates' } },
  { label: '平均社区评分', value: Number(overview.value.avg_rating) > 0 ? Number(overview.value.avg_rating).toFixed(2) : '—', note: '按评分排序查看', icon: markRaw(StarFilled), tone: 'amber', filter: { sort: 'rating', order: 'desc' } },
  { label: '平均篇幅', value: Number(overview.value.avg_pages) > 0 ? formatCount(overview.value.avg_pages, 0) + ' 页' : '—', note: '按页数排序查看', icon: markRaw(Tickets), tone: 'cyan', filter: { sort: 'pageCount', order: 'desc' } }
])

function formatCount(value, digits = 0) { return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: digits, minimumFractionDigits: digits }).format(Number(value) || 0) }
function percent(value) { return total.value ? (Number(value || 0) / total.value * 100).toFixed(1) : '0.0' }
function formatBytes(value) {
  const bytes = Number(value) || 0
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return bytes < 1073741824 ? (bytes / 1048576).toFixed(1) + ' MB' : (bytes / 1073741824).toFixed(2) + ' GB'
}
function languageLabel(name) { return ({ chinese: '中文', japanese: '日文', english: '英文', korean: '韩文', french: '法文', spanish: '西班牙文', german: '德文', russian: '俄文', translated: '已翻译' })[String(name).replace(/^language:/, '')] || String(name).replace(/^language:/, '') }
function canDrill(row) { return !!row?.filter && Object.keys(row.filter).length > 0 }
function drill(row) { if (canDrill(row)) openGalleries(row.filter) }
function padDate(date) { return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0') }
function listScope() {
  const query = { page: '1', size: '20', dedupe: applied.dedupe }
  if (applied.status) query.status = applied.status
  if (applied.collectionScope !== 'all') query.collectionScope = applied.collectionScope
  if (applied.collectionId) query.collectionId = applied.collectionId
  const range = analytics.value.range
  if (range) {
    if (range.from) query.from = range.from
    if (range.to) query.to = range.to
  } else if (applied.from || applied.to) {
    if (applied.from) query.from = applied.from
    query.to = applied.to || padDate(new Date())
  } else if (Number(applied.days) > 0) {
    const start = new Date(); start.setDate(start.getDate() - Number(applied.days) + 1)
    query.from = padDate(start)
    query.to = padDate(new Date())
  }
  return query
}
function openGalleries(extra = {}, withScope = true) {
  const query = { ...(withScope ? listScope() : { page: '1', size: '20', dedupe: 'all' }), ...extra }
  Object.keys(query).forEach(key => { if (query[key] === undefined || query[key] === null || query[key] === '') delete query[key]; else query[key] = String(query[key]) })
  router.push({ path: '/galleries', query })
}
function selectPreset(days) { draft.days = days; draft.from = ''; draft.to = '' }
function normalizeFilters(values) {
  return { days: Number(values.days) || 0, from: values.from || '', to: values.to || '', status: values.status || '', dedupe: values.dedupe || 'all', collectionScope: values.collectionScope || 'all', collectionId: values.collectionScope === 'unassigned' ? '' : String(values.collectionId || ''), grain: values.grain || 'day' }
}
function applyFilters() {
  filterValidation.value = ''
  if (draft.from && draft.to && draft.from > draft.to) { filterValidation.value = '开始日期不能晚于结束日期'; return }
  void loadAnalytics(normalizeFilters(draft))
}
function resetFilters() { Object.assign(draft, defaults); filterValidation.value = ''; void loadAnalytics({ ...defaults }) }

async function loadAnalytics(values, background = false) {
  if (background && loading.value) return
  analyticsController?.abort()
  const request = new AbortController()
  analyticsController = request
  loading.value = true
  analyticsError.value = ''
  const params = Object.fromEntries(Object.entries(values).filter(([, value]) => value !== ''))
  try {
    const response = await api.get('/dashboard/analytics', { params, signal: request.signal })
    if (request !== analyticsController || disposed) return
    analytics.value = response.data || { overview: {} }
    Object.assign(applied, values)
    hasLoaded.value = true
    const timestamp = response.data?.generatedAt ? new Date(response.data.generatedAt) : new Date()
    lastUpdated.value = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(Number.isNaN(timestamp.getTime()) ? new Date() : timestamp)
    await nextTick()
    renderCharts()
  } catch (failure) {
    if (request === analyticsController && failure.code !== 'ERR_CANCELED' && !disposed) analyticsError.value = '统计数据加载失败'
  } finally {
    if (request === analyticsController) loading.value = false
  }
}
async function loadServices() {
  if (servicesBusy || disposed) return
  servicesBusy = true
  serviceController = new AbortController()
  const signal = serviceController.signal
  try {
    const results = await Promise.allSettled([api.get('/dashboard/download-progress', { signal }), api.get('/dashboard/db-status', { signal })])
    if (disposed || signal.aborted) return
    if (results[0].status === 'fulfilled') { downloads.value = results[0].value.data || []; downloadLoaded.value = true }
    if (results[1].status === 'fulfilled') dbStatus.value = results[1].value.data || {}
    servicesError.value = results.some(result => result.status === 'rejected') ? '部分实时服务状态更新失败，暂保留已有结果。' : ''
  } finally { servicesBusy = false }
}
function refresh() { void loadAnalytics({ ...applied }); void loadServices() }
function onVisibilityChange() { if (!document.hidden && autoRefresh.value) { void loadAnalytics({ ...applied }, true); void loadServices() } }
async function toggleFullscreen() {
  displayHint.value = ''
  try {
    if (document.fullscreenElement === dashboardRef.value) await document.exitFullscreen()
    else if (dashboardRef.value?.requestFullscreen) await dashboardRef.value.requestFullscreen()
    else displayHint.value = '当前浏览器不支持全屏，可以使用浏览器的全屏功能查看。'
  } catch { displayHint.value = '浏览器未能进入全屏，当前分析范围和数据保持不变。' }
}
function onFullscreenChange() { fullscreen.value = document.fullscreenElement === dashboardRef.value; nextTick(resizeCharts) }

const chartStyle = {
  textStyle: { fontFamily: '-apple-system, BlinkMacSystemFont, Segoe UI, Microsoft YaHei, sans-serif', color: '#8390a4' },
  animationDuration: 300,
  tooltip: { trigger: 'axis', renderMode: 'richText', backgroundColor: '#1f304d', borderWidth: 0, padding: [10, 12], textStyle: { color: '#e5edf8', fontSize: 11 } },
  grid: { top: 24, left: 16, right: 20, bottom: 20, containLabel: true }
}
function categoryAxis(labels) { return { type: 'category', data: labels, axisLine: { lineStyle: { color: '#e9edf4' } }, axisTick: { show: false }, axisLabel: { color: '#8c99ad', fontSize: 10, interval: 'auto', hideOverlap: true } } }
function valueAxis(name) { return { type: 'value', name, nameTextStyle: { color: '#9ba7ba', fontSize: 9 }, splitLine: { lineStyle: { color: '#eff2f7', type: 'dashed' } }, axisLabel: { color: '#96a3b6', fontSize: 10 }, minInterval: name === 'GB' ? undefined : 1 } }
function renderRanking(chart, rows, color, label = row => row.name) {
  if (!chart) return
  const data = [...rows].reverse()
  updateChart(chart, { ...chartStyle, grid: { top: 12, left: 15, right: 32, bottom: 14, containLabel: true }, xAxis: { ...valueAxis(), splitNumber: 3 }, yAxis: { ...categoryAxis(data.map(label)), inverse: false, axisLabel: { fontSize: 10, color: '#7f8fa7', width: 110, overflow: 'truncate' }, axisLine: { show: false } }, series: [{ type: 'bar', barMaxWidth: 13, data: data.map(row => ({ value: row.value, name: label(row), filter: row.filter })), itemStyle: { color, borderRadius: [0, 4, 4, 0] }, label: { show: true, position: 'right', fontSize: 9, color: '#94a2b6' } }] })
}
function renderCharts() {
  if (disposed) return
  const count = trendMetric.value === 'count'
  updateChart(charts.get('timeline'), { ...chartStyle, grid: { top: 25, left: 17, right: 20, bottom: 24, containLabel: true }, xAxis: { ...categoryAxis(timeline.value.map(row => row.date)), boundaryGap: false }, yAxis: valueAxis(count ? '记录数' : 'GB'), series: [{ type: 'line', smooth: .2, symbol: 'circle', symbolSize: 5, showSymbol: timeline.value.length <= 35, lineStyle: { width: 2.5 }, itemStyle: { color: '#5276e8' }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: '#6088fa38' }, { offset: 1, color: '#6088fa02' }]) }, data: timeline.value.map(row => ({ value: count ? Number(row.value) : Number((Number(row.size_mb || 0) / 1024).toFixed(2)), filter: row.filter })) }] })
  updateChart(charts.get('status'), { ...chartStyle, tooltip: { ...chartStyle.tooltip, trigger: 'item' }, legend: { type: 'scroll', bottom: 0, itemWidth: 7, itemHeight: 7, icon: 'circle', textStyle: { color: '#8c99ac', fontSize: 10 } }, series: [{ type: 'pie', radius: ['47%', '72%'], center: ['50%', '43%'], label: { show: false }, emphasis: { label: { show: true, fontSize: 11, formatter: '{b}\n{d}%' } }, itemStyle: { borderWidth: 3, borderColor: '#fff', borderRadius: 4 }, data: statusRows.value.map(row => ({ name: getStatusMeta(row.name).label, value: row.value, filter: row.filter || { status: row.name }, itemStyle: { color: getStatusMeta(row.name).color } })) }], graphic: [{ type: 'text', left: 'center', top: '34%', style: { text: formatCount(total.value), fill: '#31415a', fontSize: 26, fontWeight: 600 } }, { type: 'text', left: 'center', top: '46%', style: { text: '范围内记录', fill: '#9da8ba', fontSize: 10 } }] })
  updateChart(charts.get('content'), { ...chartStyle, xAxis: {...categoryAxis(contentRows.value.map(row => row.name)), axisLabel: { fontSize: 10, color: '#8c99ad', interval: 0, hideOverlap: true } }, yAxis: valueAxis('画廊数'), series: [{ type: 'bar', barMaxWidth: 30, data: contentRows.value.map(row => ({ name: row.name, value: row.value, filter: row.filter })), itemStyle: { color: ({ rating: '#e8b34d', pages: '#57b9c9', size: '#8b7cdb', dedupe: '#6790e6' })[dimension.value], borderRadius: [5, 5, 0, 0] }, label: { show: true, position: 'top', color: '#93a1b6', fontSize: 10 } }] })
  renderRanking(charts.get('collections'), collectionRows.value, '#7294df')
  renderRanking(charts.get('languages'), languageRows.value, '#5eb7ba', row => languageLabel(row.name))
  renderRanking(charts.get('queries'), queryRows.value, '#9a87d7')
}
function resizeCharts() { if (!disposed) charts.forEach(chart => chart.resize()) }
function updateChart(chart, option) { chart?.setOption(scaleChartFonts(option), true) }
function onFontScaleChange() { nextTick(() => { renderCharts(); resizeCharts() }) }
watch([dimension, trendMetric], () => nextTick(renderCharts))
watch(viewMode, () => nextTick(() => { resizeCharts(); renderCharts() }))
onMounted(() => {
  const elements = { timeline: timelineRef, status: statusRef, content: contentRef, collections: collectionRef, languages: languageRef, queries: queryRef }
  resizeObserver = new ResizeObserver(resizeCharts)
  Object.entries(elements).forEach(([key, element]) => {
    const chart = echarts.init(element.value)
    chart.on('click', event => { if (event.data?.filter && Object.keys(event.data.filter).length) openGalleries(event.data.filter) })
    charts.set(key, chart)
    resizeObserver.observe(element.value)
  })
  collectionController = new AbortController()
  api.get('/collections', { signal: collectionController.signal }).then(response => { if (!disposed) collectionOptions.value = response.data || [] }).catch(() => {})
  void loadAnalytics({ ...defaults })
  void loadServices()
  refreshTimer = setInterval(() => { if (autoRefresh.value && !document.hidden) { void loadAnalytics({ ...applied }, true); void loadServices() } }, 30000)
  document.addEventListener('visibilitychange', onVisibilityChange)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  window.addEventListener('app-font-scale-change', onFontScaleChange)
})
onUnmounted(() => {
  disposed = true
  clearInterval(refreshTimer)
  analyticsController?.abort()
  serviceController?.abort()
  collectionController?.abort()
  resizeObserver?.disconnect()
  document.removeEventListener('visibilitychange', onVisibilityChange)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  window.removeEventListener('app-font-scale-change', onFontScaleChange)
  charts.forEach(chart => chart.dispose())
})
</script>

<style scoped>
.dashboard-page { --accent: #456cde; --ink: #24344c; --muted: #8c9aaf; color: var(--ink); max-width: 1920px; margin: 0 auto; padding-bottom: 20px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif; }
.dashboard-page:fullscreen { max-width: none; height: 100vh; box-sizing: border-box; background: #f0f3f8; padding: 24px 28px; overflow: auto; }
button, input, select { font: inherit; }
button { cursor: pointer; }
button:disabled { cursor: not-allowed; opacity: .48; }
button:focus-visible, input:focus-visible, select:focus-visible { outline: 3px solid #a9c0ff; outline-offset: 3px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 22px; margin: 5px 0 23px; }
.eyebrow { margin: 0 0 9px; color: var(--accent); font-size: calc(10px * var(--app-font-scale, 1)); font-weight: 700; letter-spacing: .18em; }
h1 { display: flex; align-items: center; gap: 18px; margin: 0; font-size: calc(27px * var(--app-font-scale, 1)); letter-spacing: -.035em; }
h1 > span { border-left: 1px solid #d6deea; padding-left: 18px; color: #99a4b5; font-size: calc(13px * var(--app-font-scale, 1)); font-weight: 400; letter-spacing: 0; }
.page-description { color: var(--muted); font-size: calc(12px * var(--app-font-scale, 1)); line-height: 1.75; margin: 9px 0 0; }
.heading-actions { display: flex; align-items: center; gap: 13px; flex-shrink: 0; }
.update-time { color: #a4adbd; font-size: calc(10px * var(--app-font-scale, 1)); font-variant-numeric: tabular-nums; }
.button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; border: 1px solid transparent; border-radius: 7px; font-size: calc(12px * var(--app-font-scale, 1)); font-weight: 600; padding: 10px 15px; white-space: nowrap; transition: .15s; }
.secondary { background: #fff; color: #6f7e94; border-color: #dfe6ef; }
.secondary:hover:not(:disabled) { background: #f8faff; border-color: #c9d5ed; }
.primary { color: #fff; background: var(--accent); box-shadow: 0 3px 7px #456cde18; }
.primary:hover:not(:disabled) { background: #385fcf; }
.text-button { display: inline-flex; align-items: center; gap: 4px; border: 0; background: none; color: #6080d7; font-size: inherit; padding: 0; white-space: nowrap; }
.text-button:hover:not(:disabled) { color: #335dce; text-decoration: underline; }
.filter-panel { background: #fff; border: 1px solid #e1e7f0; border-radius: 12px; padding: 16px 20px 18px; margin-bottom: 18px; }
.filter-topline { display: flex; align-items: center; gap: 19px; padding-bottom: 15px; margin-bottom: 15px; border-bottom: 1px solid #f0f3f7; }
.filter-title { display: flex; align-items: center; gap: 6px; font-size: calc(12px * var(--app-font-scale, 1)); color: #51627c; font-weight: 600; }
.time-presets { display: flex; gap: 5px; }
.time-presets button { border: 0; background: transparent; color: #8a98ad; font-size: calc(11px * var(--app-font-scale, 1)); padding: 6px 11px; border-radius: 5px; }
.time-presets button.active { color: #4a6ed5; background: #edf3ff; font-weight: 600; }
.auto-refresh { margin-left: auto; display: flex; align-items: center; gap: 6px; font-size: calc(10px * var(--app-font-scale, 1)); color: #9ba7b8; cursor: pointer; }
input[type="checkbox"] { accent-color: var(--accent); margin: 0; }
.filter-fields { display: flex; align-items: flex-end; gap: 11px; flex-wrap: wrap; }
.filter-fields > label { display: flex; flex-direction: column; gap: 7px; min-width: 112px; flex: 1; }
.filter-fields > label > span { color: #7d8da5; font-size: calc(11px * var(--app-font-scale, 1)); }
.filter-fields input, .filter-fields select { height: 35px; border: 1px solid #dfe6f0; border-radius: 6px; padding: 0 9px; color: #65748b; background: #fafbfd; min-width: 0; width: 100%; box-sizing: border-box; font-size: calc(12px * var(--app-font-scale, 1)); }
.filter-fields .collection-filter { max-width: 220px; min-width: 140px; }
.filter-fields select:disabled { opacity: .45; }
.filter-fields > .button { height: 35px; padding: 0 14px; }
.reset-button { font-size: calc(11px * var(--app-font-scale, 1)); padding: 0 2px; margin-bottom: 10px; }
.validation-message { color: #bd6d47; font-size: calc(11px * var(--app-font-scale, 1)); padding-top: 12px; }
.scope-bar { display: flex; align-items: center; gap: 12px; margin: 16px 1px; color: #8c9aaf; font-size: calc(11px * var(--app-font-scale, 1)); }
.scope-bar > span:first-child { display: flex; align-items: center; gap: 6px; }
.scope-bar > span > i { height: 5px; width: 5px; background: #7291db; border-radius: 50%; }
.pending-note { color: #b48a3d; font-size: calc(10px * var(--app-font-scale, 1)); }
.scope-actions { margin-left: auto; display: flex; align-items: center; gap: 18px; }
.view-switch { display: flex; gap: 2px; padding: 2px; border: 1px solid #e0e7f0; border-radius: 6px; background: #e9edf4; }
.view-switch button { border: 0; background: transparent; color: #8b99ad; padding: 5px 8px; font-size: calc(10px * var(--app-font-scale, 1)); border-radius: 4px; display: flex; align-items: center; gap: 5px; }
.view-switch button.active { color: #5b7bce; background: #fff; box-shadow: 0 1px 3px #254a8a0a; }
.graph-link { font-size: calc(10px * var(--app-font-scale, 1)); }
.metric-grid { display: grid; grid-template-columns: repeat(8, minmax(0, 1fr)); gap: 12px; }
.metric-card { text-align: left; border: 1px solid #e2e8f1; border-radius: 10px; background: #fff; color: var(--ink); padding: 15px 14px 13px; overflow: hidden; transition: border-color .15s, transform .15s; min-width: 0; }
.metric-card:hover { border-color: #b6c9f5; transform: translateY(-2px); }
.metric-top, .metric-bottom { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.metric-top > span { font-size: calc(11px * var(--app-font-scale, 1)); color: #7f8fa6; white-space: nowrap; }
.metric-top > .el-icon { padding: 6px; border-radius: 7px; color: #6f90df; background: #eff4ff; font-size: calc(14px * var(--app-font-scale, 1)); }
.violet .metric-top > .el-icon { color: #9b86d3; background: #f5f0ff; }
.amber .metric-top > .el-icon { color: #d9a447; background: #fff8e9; }
.green .metric-top > .el-icon { color: #67ae8f; background: #edf9f3; }
.cyan .metric-top > .el-icon { color: #5baebf; background: #eefaFC; }
.metric-card > strong { font-size: calc(24px * var(--app-font-scale, 1)); font-weight: 650; letter-spacing: -.03em; margin-top: 15px; display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-variant-numeric: tabular-nums; }
.metric-bottom { margin-top: 10px; color: #adb7c7; }
.metric-bottom small { font-size: calc(10px * var(--app-font-scale, 1)); color: #8c9bb2; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.metric-bottom .el-icon { font-size: calc(9px * var(--app-font-scale, 1)); }
.measurement-note { margin: 11px 2px 18px; color: #a3adbd; font-size: calc(10px * var(--app-font-scale, 1)); line-height: 1.7; }
.primary-grid, .secondary-grid, .three-grid, .operations-grid { display: grid; gap: 16px; margin-bottom: 16px; align-items: stretch; }
.primary-grid { grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr); }
.secondary-grid { grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr); }
.three-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.operations-grid { grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr); }
.panel { background: #fff; border: 1px solid #e1e7f0; border-radius: 11px; min-width: 0; overflow: hidden; display: flex; flex-direction: column; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 19px 20px 15px; }
.panel-heading h2 { font-size: calc(13px * var(--app-font-scale, 1)); margin: 0; font-weight: 600; color: #465874; }
.panel-heading p { font-size: calc(11px * var(--app-font-scale, 1)); color: #8797ae; margin: 6px 0 0; line-height: 1.5; }
.panel-heading > .text-button { font-size: calc(10px * var(--app-font-scale, 1)); }
.panel-chip { font-size: calc(9px * var(--app-font-scale, 1)); background: #f3f6fc; color: #99aac3; padding: 5px 7px; border-radius: 5px; white-space: nowrap; }
.small-tabs { display: flex; padding: 2px; gap: 2px; background: #f0f4fa; border-radius: 6px; flex-shrink: 0; }
.small-tabs button { border: 0; background: transparent; color: #9ba8bc; padding: 5px 9px; font-size: calc(10px * var(--app-font-scale, 1)); border-radius: 4px; }
.small-tabs button.active { color: #6080d6; background: #fff; box-shadow: 0 1px 3px #1b417c09; }
.trend-summary { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; padding: 0 21px 1px; }
.trend-summary strong { font-size: calc(23px * var(--app-font-scale, 1)); color: #405472; font-weight: 600; font-variant-numeric: tabular-nums; }
.trend-summary strong small { margin-left: 8px; color: #a6b0c0; font-size: calc(10px * var(--app-font-scale, 1)); font-weight: 400; }
.trend-summary > span { font-size: calc(9px * var(--app-font-scale, 1)); color: #9ba8bd; }
.chart-wrap { position: relative; min-width: 0; }
.chart { width: 100%; height: 258px; }
.trend-chart { height: 255px; }
.status-chart { height: 298px; }
.content-chart { height: 260px; }
.collection-chart { height: 228px; }
.ranking-chart { height: 255px; }
.chart-empty { position: absolute; inset: 0; display: grid; place-items: center; color: #a9b5c7; font-size: calc(12px * var(--app-font-scale, 1)); background: #ffffffc9; pointer-events: none; }
.panel-footer { margin-top: auto; padding: 10px 19px 12px; border-top: 1px solid #f1f4f8; font-size: calc(10px * var(--app-font-scale, 1)); color: #8191a8; line-height: 1.6; }
.dimension-tabs { display: flex; gap: 8px; padding: 0 20px 7px; }
.dimension-tabs button { padding: 5px 13px; border-radius: 5px; background: #fff; color: #98a6bb; border: 1px solid #e8edf5; font-size: calc(10px * var(--app-font-scale, 1)); }
.dimension-tabs button.active { border-color: #cddcfb; color: #5b7bd0; background: #f2f6ff; }
.collection-summary { display: flex; gap: 26px; padding: 0 20px 13px; align-items: center; }
.collection-summary > div > strong { font-size: calc(22px * var(--app-font-scale, 1)); color: #475d7c; font-weight: 600; font-variant-numeric: tabular-nums; }
.collection-summary > div > span { display: block; font-size: calc(9px * var(--app-font-scale, 1)); color: #a8b3c4; margin-top: 4px; }
.completion-ring { margin-left: auto; color: #78a292; font-size: calc(20px * var(--app-font-scale, 1)); text-align: right; font-weight: 500; }
.completion-ring > small { font-size: calc(9px * var(--app-font-scale, 1)); color: #9bb7aa; display: block; margin-top: 3px; }
.quality-icon { color: #9eb4d9; font-size: calc(20px * var(--app-font-scale, 1)); }
.quality-list { padding: 0 20px; flex: 1; }
.quality-list > button { display: block; width: 100%; border: 0; border-radius: 6px; background: transparent; padding: 11px 0; color: #798ba7; text-align: left; }
.quality-list > button:hover:not(:disabled) { color: #4c72ce; }
.quality-list > button > div { display: flex; justify-content: space-between; align-items: center; gap: 10px; font-size: calc(11px * var(--app-font-scale, 1)); }
.quality-list strong { font-size: calc(13px * var(--app-font-scale, 1)); font-weight: 500; color: #7890ad; font-variant-numeric: tabular-nums; }
.quality-list strong > small { font-size: calc(9px * var(--app-font-scale, 1)); color: #b4bed0; display: inline-block; min-width: 38px; text-align: right; }
.quality-track { display: block; height: 4px; border-radius: 4px; background: #f0f4fa; overflow: hidden; margin-top: 10px; }
.quality-track > i { display: block; height: 100%; border-radius: inherit; background: #d6b276; min-width: 0; }
.quality-note { display: flex; gap: 6px; padding: 12px 20px 18px; color: #adb8c8; font-size: calc(9px * var(--app-font-scale, 1)); line-height: 1.7; }
.quality-note > .el-icon { margin-top: 2px; flex-shrink: 0; font-size: calc(12px * var(--app-font-scale, 1)); color: #c0cbdb; }
.quality-empty { padding: 80px 0; color: #a9b4c4; font-size: calc(12px * var(--app-font-scale, 1)); text-align: center; }
.table-wrap { overflow: auto; scrollbar-width: thin; padding: 0 19px 15px; }
.table-wrap table { border-collapse: collapse; width: 100%; font-size: calc(11px * var(--app-font-scale, 1)); color: #73849e; }
.table-wrap th { color: #8b9bb2; font-weight: 400; font-size: calc(10px * var(--app-font-scale, 1)); position: sticky; top: 0; background: #fff; z-index: 1; text-align: left; padding: 9px 8px; border-bottom: 1px solid #edf2f8; white-space: nowrap; }
.table-wrap td { padding: 10px 8px; border-bottom: 1px solid #f2f5f9; font-variant-numeric: tabular-nums; }
.table-wrap th:not(:first-child), .table-wrap td:not(:first-child) { text-align: right; }
.table-wrap td:first-child { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.trend-table { height: 255px; box-sizing: border-box; }
.status-table { height: 298px; box-sizing: border-box; }
.content-table { height: 260px; box-sizing: border-box; }
.collection-table { height: 228px; box-sizing: border-box; }
.ranking-table { height: 255px; box-sizing: border-box; }
.table-link { border: 0; background: transparent; padding: 0; color: #7e90ae; display: inline-flex; align-items: center; gap: 7px; font-size: calc(10px * var(--app-font-scale, 1)); }
.table-link > i { height: 6px; width: 6px; border-radius: 50%; }
.empty-cell { text-align: center !important; color: #b0bdce; padding-top: 60px !important; }
.live-badge { color: #8daf9e; font-size: calc(10px * var(--app-font-scale, 1)); display: flex; align-items: center; gap: 6px; }
.live-badge > i { background: #71b695; width: 5px; height: 5px; border-radius: 50%; box-shadow: 0 0 0 3px #edf8f1; }
.download-list { max-height: 215px; overflow-y: auto; scrollbar-width: thin; padding: 0 20px 10px; }
.download-row { padding: 12px 0; width: 100%; border: 0; border-bottom: 1px solid #f1f4f8; background: transparent; text-align: left; }
.download-name { display: flex; gap: 9px; align-items: center; margin-bottom: 9px; }
.download-name > span { color: #9cabc1; font-size: calc(9px * var(--app-font-scale, 1)); }
.download-name strong { flex: 1; color: #71839f; font-size: calc(11px * var(--app-font-scale, 1)); font-weight: 400; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.download-name em { color: #6f91d3; font-size: calc(10px * var(--app-font-scale, 1)); font-style: normal; }
.download-progress { height: 4px; background: #edf2fb; border-radius: 3px; overflow: hidden; }
.download-progress i { display: block; height: 100%; background: #85a0df; border-radius: inherit; }
.download-size { color: #acb8ca; font-size: calc(9px * var(--app-font-scale, 1)); margin-top: 7px; }
.download-empty { min-height: 135px; display: flex; justify-content: center; align-items: center; flex-direction: column; gap: 9px; color: #a2b2c8; font-size: calc(11px * var(--app-font-scale, 1)); }
.download-empty .el-icon { font-size: calc(25px * var(--app-font-scale, 1)); color: #a8c5b7; margin-bottom: 3px; }
.download-empty small { color: #bdc6d5; font-size: calc(9px * var(--app-font-scale, 1)); }
.database-status { display: flex; align-items: center; gap: 8px; margin: 0 20px 20px; padding: 10px 13px; color: #9caec5; background: #f5f8fc; font-size: calc(11px * var(--app-font-scale, 1)); border-radius: 6px; }
.database-status > i { background: #c1cbd9; height: 5px; width: 5px; border-radius: 50%; }
.database-status.connected { color: #83a793; background: #f1f8f4; }
.database-status.connected > i { background: #75b395; }
.database-status.disconnected { color: #b99584; background: #fff6f1; }
.database-status.disconnected > i { background: #ce9780; }
.service-metrics { display: grid; grid-template-columns: 1fr 1fr; padding: 0 20px 22px; }
.service-metrics > div + div { border-left: 1px solid #eef2f7; padding-left: 20px; }
.service-metrics span { font-size: calc(9px * var(--app-font-scale, 1)); color: #a7b3c5; }
.service-metrics strong { display: block; color: #8499b7; font-size: calc(21px * var(--app-font-scale, 1)); font-weight: 500; margin-top: 7px; font-variant-numeric: tabular-nums; }
.service-metrics small { margin-left: 5px; color: #a9b8cc; font-size: calc(9px * var(--app-font-scale, 1)); font-weight: 400; }
.service-link { margin-top: auto; display: flex; justify-content: space-between; align-items: center; border: 0; border-top: 1px solid #eff3f8; background: #fbfcfe; padding: 11px 20px; color: #8da4c7; font-size: calc(10px * var(--app-font-scale, 1)); }
.service-link > span { display: flex; align-items: center; gap: 7px; }
.service-link:hover { background: #f2f6ff; }
.service-error { color: #b69473; font-size: calc(10px * var(--app-font-scale, 1)); padding: 0 20px 8px; }
.feedback { display: flex; align-items: center; gap: 8px; font-size: calc(11px * var(--app-font-scale, 1)); padding: 11px 14px; border-radius: 7px; margin-bottom: 13px; }
.error-feedback { background: #fff5e9; color: #b08855; border: 1px solid #f1dfc7; }
.feedback .text-button { margin-left: auto; }
.initial-loading .metric-card { opacity: .65; }
.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 1550px) { .metric-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); } .filter-fields { gap: 10px; } .filter-fields > label { min-width: 125px; } }
@media (max-width: 1050px) { .primary-grid, .secondary-grid, .operations-grid { grid-template-columns: 1fr; } .three-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .quality-panel { grid-column: 1 / -1; } .quality-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 25px; } h1 > span { display: none; } .scope-actions { gap: 10px; } .graph-link { display: none; } }
@media (max-width: 700px) { .page-heading { align-items: flex-start; } h1 { font-size: calc(23px * var(--app-font-scale, 1)); } .heading-actions { gap: 5px; } .update-time { display: none; } .heading-actions .button { padding: 9px 10px; font-size: calc(11px * var(--app-font-scale, 1)); } .filter-panel { padding: 13px; } .filter-topline { flex-wrap: wrap; gap: 11px; } .filter-title { width: 100%; } .time-presets { gap: 1px; } .time-presets button { padding: 6px 8px; font-size: calc(10px * var(--app-font-scale, 1)); } .auto-refresh { font-size: calc(9px * var(--app-font-scale, 1)); } .filter-fields > label { min-width: 115px; } .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; } .metric-card { padding: 13px 12px; } .metric-card > strong { font-size: calc(22px * var(--app-font-scale, 1)); } .scope-bar { align-items: flex-start; flex-wrap: wrap; } .scope-actions { margin-left: 0; } .three-grid { grid-template-columns: 1fr; } .quality-panel { grid-column: auto; } .quality-list { display: block; } .trend-summary { flex-wrap: wrap; gap: 5px; } .panel-heading { padding: 17px 16px 14px; } .panel-heading h2 { font-size: calc(12px * var(--app-font-scale, 1)); } .panel-heading p { font-size: calc(9px * var(--app-font-scale, 1)); } .trend-summary { padding-left: 17px; } .filter-fields .collection-filter { max-width: none; } }
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { transition: none !important; } .spinning { animation-duration: 2s; } }
</style>
