<template>
  <div class="network-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">EXPLORE YOUR LIBRARY</p>
        <h1>关系图谱<span>从标签之间，发现新的连接</span></h1>
        <p class="page-description">选择一个标签，沿着共现关系探索内容；切换分类聚类，查看不同主题的分布。</p>
      </div>
      <button class="button secondary" :disabled="loading" @click="load">
        <el-icon :class="{ spinning: loading }"><Refresh /></el-icon>刷新数据
      </button>
    </header>

    <form class="query-bar" @submit.prevent="load">
      <label class="query-field keyword-field">
        <span>作品范围</span>
        <div class="search-control"><el-icon><Search /></el-icon><input v-model="draft.keyword" aria-label="作品标题关键词" placeholder="输入作品标题，或留空查看全部" /></div>
      </label>
      <label class="query-field">
        <span>标签数量</span>
        <select v-model.number="draft.limit" aria-label="标签数量"><option :value="20">20 个 · 精简</option><option :value="36">36 个 · 推荐</option><option :value="60">60 个 · 丰富</option><option :value="0">全部标签 · 按需探索</option></select>
      </label>
      <label class="query-field">
        <span>最少共同出现</span>
        <div class="number-control"><input v-model.number="draft.minWeight" aria-label="最小共现次数" type="number" min="1" max="100" required /><span>次</span></div>
      </label>
      <button class="button primary" type="submit" :disabled="loading"><el-icon><Search /></el-icon>应用筛选</button>
      <span v-if="hasPendingFilters" class="pending-note">筛选尚未应用</span>
    </form>
    <div v-if="loading && draft.limit === 0" class="catalog-progress" role="status"><span>正在读取全部标签 {{ formatCount(loadProgress.loaded) }} / {{ formatCount(loadProgress.total) }}</span><progress :value="loadProgress.loaded" :max="Math.max(1, loadProgress.total)" /><button class="text-button" @click="cancelCatalog">取消加载</button></div>
    <p v-if="allMode" class="catalog-note">全部 {{ formatCount(payload.nodes.length) }} 个标签已载入。画布按可视区域绘制；选择标签时才查询共现连接，避免计算全库两两关系。</p>

    <div v-if="error" class="feedback error-feedback" role="alert">
      <el-icon><Warning /></el-icon><span>{{ error }}{{ hasLoaded ? '，当前保留上次成功的数据。' : '。' }}</span>
      <button class="text-button" @click="load">重新加载</button>
    </div>

    <div class="workspace">
      <section class="graph-panel" aria-label="标签关系画布">
        <div class="graph-toolbar">
          <div class="view-tabs" role="group" aria-label="图谱布局">
            <button :class="{ active: layout === 'force' }" :aria-pressed="layout === 'force'" @click="changeLayout('force')"><el-icon><Share /></el-icon>关系网络</button>
            <button :class="{ active: layout === 'clusters' }" :aria-pressed="layout === 'clusters'" @click="changeLayout('clusters')"><el-icon><Grid /></el-icon>分类聚类</button>
          </div>
          <div class="graph-options">
            <label><input v-model="showLabels" type="checkbox" />显示标签</label>
            <label v-if="!allMode"><input v-model="allLinks" type="checkbox" />全部连线</label>
          </div>
        </div>

        <div class="namespace-bar">
          <button :class="{ active: !namespace }" @click="chooseNamespace('')">全部分类 <small>{{ payload.nodes.length }}</small></button>
          <button v-for="group in namespaces" :key="group.name" :class="{ active: namespace === group.name }" :style="{ '--category-color': namespaceColor(group.name) }" @click="chooseNamespace(namespace === group.name ? '' : group.name)">
            <i />{{ namespaceLabel(group.name) }}<small>{{ group.count }}</small>
          </button>
        </div>

        <div ref="canvasRef" class="canvas" :class="{ dragging: gestureActive }">
          <div class="canvas-status"><i /><span>{{ selected ? '正在探索 ' + shortLabel(selected) : namespace ? namespaceLabel(namespace) + '分类' : '全局视图' }}</span><span class="status-count">{{ displayedNodes.length }} 标签 · {{ displayedLinks.length }} 连接</span></div>
          <AllTagsCanvas v-if="allMode" ref="largeCanvasRef" :nodes="displayedNodes" :links="displayedLinks" :selected-name="selectedName" :hovered-name="hoveredName" :mode="layout" :show-labels="showLabels" @select="selectNode" @hover="hoveredName = $event" @clear="clearSelection" @choose="chooseNamespace" @zoom="view.zoom = $event" />
          <svg v-else ref="svgRef" class="network-svg" :viewBox="'0 0 ' + viewport.width + ' ' + viewport.height" aria-label="可拖动和缩放的标签图谱" @wheel.prevent="onWheel" @pointerdown="startGesture($event)" @pointermove="moveGesture" @pointerup="endGesture" @pointercancel="cancelGesture" @lostpointercapture="cancelGesture">
            <defs>
              <pattern id="network-grid" width="25" height="25" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r=".8" fill="#33445f" opacity=".58" /></pattern>
              <radialGradient v-for="group in namespaces" :id="gradientId(group.name)" :key="group.name" cx="34%" cy="28%" r="80%">
                <stop offset="0" :stop-color="namespaceColor(group.name)" stop-opacity=".32" />
                <stop offset="1" :stop-color="namespaceColor(group.name)" stop-opacity=".06" />
              </radialGradient>
            </defs>
            <rect width="100%" height="100%" fill="url(#network-grid)" />
            <g :transform="viewTransform">
              <g v-if="layout === 'clusters'" class="clusters">
                <g v-for="cluster in graphLayout.clusters" :key="cluster.namespace" class="cluster" role="button" tabindex="0" :aria-label="'进入' + namespaceLabel(cluster.namespace) + '分类'" @pointerdown.stop="startGesture($event, null, cluster.namespace)" @keydown.enter.prevent="chooseNamespace(cluster.namespace)" @keydown.space.prevent="chooseNamespace(cluster.namespace)">
                  <circle :cx="cluster.x" :cy="cluster.y" :r="cluster.r" :fill="'url(#' + gradientId(cluster.namespace) + ')'" :stroke="namespaceColor(cluster.namespace)" stroke-opacity=".28" stroke-dasharray="5 6" vector-effect="non-scaling-stroke" />
                  <text :x="cluster.x" :y="cluster.y - cluster.r + 19 * fontScale / worldScale" text-anchor="middle" :font-size="11 * fontScale / worldScale" :fill="namespaceColor(cluster.namespace)">{{ namespaceLabel(cluster.namespace) }}</text>
                </g>
              </g>
              <g class="edges" aria-hidden="true">
                <line v-for="edge in displayedLinks" :key="edge.source + '|' + edge.target" :x1="position(edge.source).x" :y1="position(edge.source).y" :x2="position(edge.target).x" :y2="position(edge.target).y" :stroke="edgeActive(edge) ? '#a8c5fd' : '#607695'" :stroke-opacity="edgeOpacity(edge)" :stroke-width="lineWidth(edge)" vector-effect="non-scaling-stroke" />
              </g>
              <g v-for="node in displayedNodes" :key="node.name" class="graph-node" :class="{ selected: selectedName === node.name, dimmed: isDimmed(node.name) }" :transform="'translate(' + position(node.name).x + ',' + position(node.name).y + ')'" role="button" tabindex="0" :aria-label="shortLabel(node) + '，出现 ' + node.value + ' 次，按回车探索'" :aria-pressed="selectedName === node.name" @pointerdown.stop="startGesture($event, node.name)" @pointerenter="hoveredName = node.name" @pointerleave="hoveredName = ''" @focus="hoveredName = node.name" @blur="hoveredName = ''" @keydown.enter.prevent="selectNode(node.name)" @keydown.space.prevent="selectNode(node.name)">
                <title>{{ node.label || node.name }} · 出现 {{ node.value }} 次</title>
                <circle class="node-hit" :r="Math.max(radius(node), 16 / worldScale)" fill="transparent" />
                <circle v-if="selectedName === node.name" :r="radius(node) + 5 / worldScale" fill="none" :stroke="namespaceColor(node.namespace)" stroke-opacity=".55" stroke-width="1" vector-effect="non-scaling-stroke" />
                <circle :r="radius(node)" :fill="'url(#' + gradientId(node.namespace) + ')'" :stroke="namespaceColor(node.namespace)" :stroke-width="selectedName === node.name ? 2 : 1.3" vector-effect="non-scaling-stroke" />
                <circle :r="Math.max(1.5 / worldScale, radius(node) * .18)" :fill="namespaceColor(node.namespace)" />
                <g v-if="visibleLabels.has(node.name)" :transform="'scale(' + 1 / worldScale + ')'" class="node-label" aria-hidden="true">
                  <text :y="radius(node) * worldScale + 17 * fontScale" text-anchor="middle">{{ truncate(shortLabel(node), 17) }}</text>
                </g>
              </g>
            </g>
          </svg>

          <div v-if="loading" class="canvas-overlay loading-overlay" role="status"><span class="loader" /><strong>正在加载标签关系</strong><span>整理标签与共现连接…</span></div>
          <div v-else-if="hasLoaded && !displayedNodes.length" class="canvas-overlay"><el-icon :size="36"><Search /></el-icon><strong>没有找到可展示的标签</strong><span>试试其他作品标题，或放宽筛选范围。</span><button class="button canvas-button" @click="resetQuery">清空筛选</button></div>
          <div v-else-if="!hasLoaded && error" class="canvas-overlay"><strong>图谱暂时无法加载</strong><button class="button canvas-button" @click="load">重试</button></div>

          <div v-if="selectedName || namespace" class="canvas-breadcrumb">
            <button @click="returnToOverview"><el-icon><Back /></el-icon>返回全局</button>
            <span v-if="namespace">{{ namespaceLabel(namespace) }}</span><span v-if="selected">{{ shortLabel(selected) }}</span>
          </div>
          <div class="zoom-controls" role="group" aria-label="画布缩放控制">
            <button title="缩小" aria-label="缩小" :disabled="view.zoom <= MIN_ZOOM" @click="zoomBy(1 / 1.25)"><el-icon><Minus /></el-icon></button>
            <span aria-live="polite">{{ Math.round(view.zoom * 100) }}%</span>
            <button title="放大" aria-label="放大" :disabled="view.zoom >= (allMode ? 20 : MAX_ZOOM)" @click="zoomBy(1.25)"><el-icon><Plus /></el-icon></button>
            <i /><button title="适应画布" aria-label="适应画布" @click="fitView"><el-icon><FullScreen /></el-icon></button>
          </div>
          <p class="canvas-hint">拖动空白平移 · 滚轮 / 双指缩放 · 点击标签探索 · Esc 取消选择</p>
        </div>
        <footer class="graph-footer"><span><i />节点大小表示标签出现频率，连线表示共同出现</span><span>{{ allMode ? '点击标签按需加载连接' : allLinks ? '显示全部连接' : '显示核心连接' }} · {{ applied.keyword ? '标题：' + applied.keyword : '全部作品' }}</span></footer>
      </section>

      <aside class="inspector">
        <header class="inspector-heading"><span>{{ selected ? '标签洞察' : '探索入口' }}</span><button v-if="selected" class="icon-button" title="取消选择" aria-label="取消选择" @click="clearSelection"><el-icon><Close /></el-icon></button><el-icon v-else><Compass /></el-icon></header>
        <template v-if="selected">
          <section class="selected-summary">
            <span class="category-label" :style="{ color: namespaceColor(selected.namespace) }"><i :style="{ background: namespaceColor(selected.namespace) }" />{{ namespaceLabel(selected.namespace) }}</span>
            <h2>{{ shortLabel(selected) }}</h2><code>{{ selected.name }}</code>
            <div class="node-metrics"><div><strong>{{ formatCount(selected.value) }}</strong><span>出现次数</span></div><div><strong>{{ allMode ? neighborTotal : neighbors.length }}</strong><span>{{ allMode ? '全分类关联' : '关联标签' }}</span></div></div>
            <button class="button primary wide" @click="openGallery"><el-icon><FolderOpened /></el-icon>查看相关画廊<el-icon><ArrowRight /></el-icon></button>
            <div class="selection-actions"><button class="text-button" @click="focusNode">居中查看</button><label><input v-model="onlyNeighbors" type="checkbox" />只看直接关联</label></div>
          </section>
          <section class="related-section">
            <div class="section-caption"><b>共同出现的标签</b><span>按共现次数排序</span></div>
            <p v-if="neighborLoading" class="side-notice" role="status">正在查询直接关联…</p>
            <p v-if="neighborError" class="side-notice" role="alert">{{ neighborError }}<button class="text-button" @click="loadNeighbors(selectedName)">重试</button></p>
            <p v-if="allMode && !neighborLoading && !neighborError" class="side-notice">全分类共 {{ formatCount(neighborTotal) }} 个关联{{ neighborTruncated ? '，仅载入共现最高的 100 个' : '' }}。<template v-if="namespace">当前分类显示 {{ neighbors.length }} 个。<button class="text-button" @click="showAllNeighborNamespaces">查看全部分类</button></template></p>
            <div v-if="!neighbors.length && !neighborLoading && !neighborError" class="side-empty"><el-icon><Share /></el-icon><p>{{ namespace ? '当前分类未显示关联' : '当前阈值下暂无关联' }}</p><small>{{ namespace ? '可查看全部分类中的关联标签。' : '降低“最少共同出现”后重新查询。' }}</small></div>
            <div v-else class="tag-list"><button v-for="item in neighbors" :key="item.node.name" class="tag-row" @click="selectNode(item.node.name)"><i :style="{ background: namespaceColor(item.node.namespace) }" /><span><b>{{ shortLabel(item.node) }}</b><small>{{ namespaceLabel(item.node.namespace) }}</small></span><em>{{ formatCount(item.weight) }} 次</em><el-icon><ArrowRight /></el-icon></button></div>
          </section>
        </template>
        <template v-else>
          <div class="intro-copy"><div class="intro-icon"><el-icon><Pointer /></el-icon></div><h2>从一个标签开始</h2><p>点击画布上的节点或下方标签，查看它的关联内容。悬停可临时点亮连接。</p></div>
          <div class="locator"><el-icon><Search /></el-icon><input v-model="locator" aria-label="在当前图谱中查找标签" placeholder="在当前图谱中查找标签" /></div>
          <div class="section-caption browse-caption"><b>{{ locator ? '匹配标签' : '高频标签' }}</b><span>{{ browsableNodes.length }} 个</span></div>
          <div class="tag-list browse-list"><button v-for="node in browsePageNodes" :key="node.name" class="tag-row" @click="selectNode(node.name)"><i :style="{ background: namespaceColor(node.namespace) }" /><span><b>{{ shortLabel(node) }}</b><small>{{ namespaceLabel(node.namespace) }}</small></span><em>{{ formatCount(node.value) }}</em><el-icon><ArrowRight /></el-icon></button><div v-if="!browsableNodes.length" class="side-empty">没有匹配的标签</div></div>
          <div v-if="browsableNodes.length > 50" class="catalog-pager"><button :disabled="browsePage <= 1" @click="browsePage--">上一页</button><span>{{ browsePage }} / {{ Math.ceil(browsableNodes.length / 50) }}</span><button :disabled="browsePage * 50 >= browsableNodes.length" @click="browsePage++">下一页</button></div>
        </template>
      </aside>
    </div>
    <p class="scope-note">{{ allMode ? '全部标签采用分类空间布局；关系按选中标签查询，不执行全库两两关系计算。' : '展示当前筛选范围内的高频标签及其关系；“核心连接”保留每个标签最强的几条连接。' }}</p>
  </div>
</template>

<script setup>
// 组织标签筛选、关系探索和详情侧栏；高频标签用 SVG，全部标签用画布分页加载。
import { computed, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Back, Close, Compass, FolderOpened, FullScreen, Grid, Minus, Plus, Pointer, Refresh, Search, Share, Warning } from '@element-plus/icons-vue'
import api from '../api'
import { layoutNetwork, namespaceColor, namespaceLabel, nodeRadius } from '../utils/tagNetworkLayout'
import AllTagsCanvas from '../components/AllTagsCanvas.vue'

const MIN_ZOOM = .5
const MAX_ZOOM = 5
const router = useRouter()
const canvasRef = ref(null)
const svgRef = ref(null)
const largeCanvasRef = ref(null)
const draft = reactive({ keyword: '', limit: 36, minWeight: 2 })
const applied = reactive({ ...draft })
const payload = shallowRef({ nodes: [], links: [] })
const allMode = computed(() => applied.limit === 0)
const loadProgress = reactive({ loaded: 0, total: 0 })
const neighborPayload = shallowRef({ nodes: [], links: [] })
const neighborLoading = ref(false), neighborError = ref(''), neighborTotal = ref(0), neighborTruncated = ref(false)
const browsePage = ref(1), fontScale = ref(1)
const graphLayout = ref({ positions: {}, clusters: [], width: 1100, height: 740 })
const loading = ref(false)
const hasLoaded = ref(false)
const error = ref('')
const layout = ref('force')
const namespace = ref('')
const selectedName = ref('')
const hoveredName = ref('')
const locator = ref('')
const onlyNeighbors = ref(false)
const showLabels = ref(false)
const allLinks = ref(false)
const viewport = reactive({ width: 900, height: 660 })
const scene = reactive({ centerX: 550, centerY: 370, width: 1100, height: 740 })
const view = reactive({ x: 0, y: 0, zoom: 1 })
const gestureActive = ref(false)
let controller, neighborController, resizeObserver, gesture = null, pinch = null
const pointers = new Map()

const hasPendingFilters = computed(() => draft.keyword.trim() !== applied.keyword || draft.limit !== applied.limit || draft.minWeight !== applied.minWeight)
const namespaces = computed(() => {
  const counts = new Map()
  payload.value.nodes.forEach(node => counts.set(node.namespace, (counts.get(node.namespace) || 0) + 1))
  return [...counts].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count)
})
const filteredNodes = computed(() => payload.value.nodes.filter(node => !namespace.value || node.namespace === namespace.value))
const filteredNames = computed(() => new Set(filteredNodes.value.map(node => node.name)))
const filteredLinks = computed(() => (allMode.value ? neighborPayload.value.links : payload.value.links).filter(edge => filteredNames.value.has(edge.source) && filteredNames.value.has(edge.target)))
const selected = computed(() => payload.value.nodes.find(node => node.name === selectedName.value))
const nodeMap = computed(() => new Map(payload.value.nodes.map(node => [node.name, node])))
const neighbors = computed(() => {
  if (!selectedName.value) return []
  return filteredLinks.value.filter(edge => edge.source === selectedName.value || edge.target === selectedName.value)
    .map(edge => ({ node: nodeMap.value.get(edge.source === selectedName.value ? edge.target : edge.source), weight: edge.value }))
    .sort((a, b) => b.weight - a.weight)
})
const neighborNames = computed(() => new Set([selectedName.value, ...neighbors.value.map(item => item.node.name)]))
const displayedNodes = computed(() => onlyNeighbors.value && selectedName.value ? filteredNodes.value.filter(node => neighborNames.value.has(node.name)) : filteredNodes.value)
const activeName = computed(() => hoveredName.value || selectedName.value)
const activeNames = computed(() => {
  const names = new Set([activeName.value])
  filteredLinks.value.forEach(edge => {
    if (edge.source === activeName.value) names.add(edge.target)
    if (edge.target === activeName.value) names.add(edge.source)
  })
  return names
})
const displayedLinks = computed(() => {
  const shownNames = new Set(displayedNodes.value.map(node => node.name))
  const links = filteredLinks.value.filter(edge => shownNames.has(edge.source) && shownNames.has(edge.target))
  if (allLinks.value || onlyNeighbors.value) return links
  const degrees = new Map()
  return [...links].sort((a, b) => b.value - a.value).filter(edge => {
    if (edge.source === activeName.value || edge.target === activeName.value) return true
    if ((degrees.get(edge.source) || 0) >= 3 && (degrees.get(edge.target) || 0) >= 3) return false
    degrees.set(edge.source, (degrees.get(edge.source) || 0) + 1)
    degrees.set(edge.target, (degrees.get(edge.target) || 0) + 1)
    return true
  })
})
const browsableNodes = computed(() => {
  const query = locator.value.trim().toLowerCase()
  return [...filteredNodes.value].filter(node => !query || (node.name + ' ' + node.label).toLowerCase().includes(query)).sort((a, b) => b.value - a.value)
})
const browsePageNodes = computed(() => browsableNodes.value.slice((browsePage.value - 1) * 50, browsePage.value * 50))
const maxValue = computed(() => payload.value.nodes.reduce((max, node) => Math.max(max, node.value), 1))
const fitScale = computed(() => Math.max(.12, Math.min(1.6, (viewport.width - 110) / scene.width, (viewport.height - 155) / scene.height)))
const worldScale = computed(() => fitScale.value * view.zoom)
const viewTransform = computed(() => 'translate(' + (viewport.width / 2 + view.x) + ',' + (viewport.height / 2 + view.y) + ') scale(' + worldScale.value + ') translate(' + -scene.centerX + ',' + -scene.centerY + ')')
const visibleLabels = computed(() => {
  const candidates = [...displayedNodes.value].sort((a, b) => {
    const priority = node => node.name === selectedName.value ? 3 : node.name === hoveredName.value ? 2 : 0
    return priority(b) - priority(a) || b.value - a.value
  })
  const shown = new Set(), boxes = []
  for (const node of candidates) {
    const important = node.name === selectedName.value || node.name === hoveredName.value
    if (!important && !showLabels.value && shown.size >= 5) continue
    const point = position(node.name)
    const x = (point.x - scene.centerX) * worldScale.value + viewport.width / 2 + view.x
    const y = (point.y - scene.centerY) * worldScale.value + viewport.height / 2 + view.y + radius(node) * worldScale.value + 17 * fontScale.value
    const width = Math.min(170, [...shortLabel(node)].reduce((sum, char) => sum + (char.charCodeAt(0) > 255 ? 11 : 6.5), 12)) * fontScale.value
    const box = { x: x - width / 2, y: y - 12 * fontScale.value, width, height: 21 * fontScale.value }
    if (!important && (box.x < 10 || box.x + width > viewport.width - 10 || boxes.some(other => box.x < other.x + other.width && box.x + width > other.x && box.y < other.y + other.height && box.y + box.height > other.y))) continue
    boxes.push(box)
    shown.add(node.name)
  }
  return shown
})

function shortLabel(node) {
  const label = node.label || node.name
  return label.includes(':') ? label.slice(label.indexOf(':') + 1) : label
}
function truncate(value, length) { return [...value].length > length ? [...value].slice(0, length).join('') + '…' : value }
function formatCount(value) { return new Intl.NumberFormat('zh-CN').format(value) }
function gradientId(name) { return 'tag-color-' + namespaces.value.findIndex(group => group.name === name) }
function position(name) { return graphLayout.value.positions[name] || { x: 550, y: 370 } }
// 抵消画布放大对圆半径的影响：放大时拉开节点间距，同时让节点略微缩小。
function radius(node) { return nodeRadius(node.value, maxValue.value) / Math.max(1, fitScale.value) / Math.pow(view.zoom, 1.18) }
function isDimmed(name) { return !!activeName.value && !activeNames.value.has(name) }
function edgeActive(edge) { return !!activeName.value && (edge.source === activeName.value || edge.target === activeName.value) }
function edgeOpacity(edge) { return activeName.value ? edgeActive(edge) ? .72 : .055 : layout.value === 'clusters' ? .15 : .27 }
function lineWidth(edge) { return Math.min(edgeActive(edge) ? 1.25 : .8, (.3 + Math.log1p(edge.value) * .09) / Math.sqrt(view.zoom)) }

// 高频模式重新计算节点位置；全部模式交给画布处理，避免运行两两力计算。
function rebuildLayout() {
  if (allMode.value) { fitView(); return }
  graphLayout.value = layoutNetwork(filteredNodes.value, filteredLinks.value, layout.value)
  fitView()
}
function changeLayout(value) { if (layout.value !== value) { layout.value = value; rebuildLayout() } }
function chooseNamespace(value) { namespace.value = value; clearSelection(); rebuildLayout() }
function showAllNeighborNamespaces() { namespace.value = ''; rebuildLayout() }
// 选中标签后更新侧栏；全部模式此时才查询该标签的直接关联。
function selectNode(name) {
  if (!filteredNames.value.has(name)) return
  selectedName.value = name; hoveredName.value = ''; locator.value = ''
  if (allMode.value) loadNeighbors(name)
}
// 取消尚未完成的关联查询并清空选择，避免旧标签的连接迟到后重新点亮。
function clearSelection() {
  neighborController?.abort(); neighborController = null
  neighborPayload.value = { nodes: [], links: [] }; neighborError.value = ''; neighborLoading.value = false; neighborTotal.value = 0; neighborTruncated.value = false
  selectedName.value = ''; hoveredName.value = ''; onlyNeighbors.value = false
}
function returnToOverview() { namespace.value = ''; clearSelection(); rebuildLayout() }
// 计算当前图谱边界并恢复全景，全部模式则调用画布的适配方法。
function fitView() {
  if (allMode.value) { largeCanvasRef.value?.resetView(); view.zoom = 1; return }
  const circles = layout.value === 'clusters' ? graphLayout.value.clusters : filteredNodes.value.map(node => ({ ...position(node.name), r: nodeRadius(node.value, maxValue.value) + 8 }))
  if (circles.length) {
    const left = Math.min(...circles.map(circle => circle.x - circle.r)), right = Math.max(...circles.map(circle => circle.x + circle.r))
    const top = Math.min(...circles.map(circle => circle.y - circle.r)), bottom = Math.max(...circles.map(circle => circle.y + circle.r))
    Object.assign(scene, { centerX: (left + right) / 2, centerY: (top + bottom) / 2, width: Math.max(100, right - left), height: Math.max(100, bottom - top) })
  }
  view.x = 0; view.y = 0; view.zoom = 1
}
// 将选中节点移到视图中央，方便从侧栏回到画布中的具体位置。
function focusNode() {
  if (!selected.value) return
  if (allMode.value) { largeCanvasRef.value?.focus(selectedName.value); return }
  view.zoom = Math.max(1.8, view.zoom)
  const point = position(selectedName.value)
  view.x = -(point.x - scene.centerX) * worldScale.value
  view.y = -(point.y - scene.centerY) * worldScale.value
}
function openGallery() {
  if (selected.value) router.push({ path: '/galleries', query: { tag: selected.value.name, q: applied.keyword || undefined, dedupe: 'all', page: '1', size: '20' } })
}
function resetQuery() { Object.assign(draft, { keyword: '', limit: 36, minWeight: 2 }); load() }

// 重新读取图谱；全部标签逐页使用同一个快照，并校验总数、游标和去重结果后才替换旧图谱。
async function load() {
  controller?.abort()
  const request = new AbortController()
  controller = request
  const params = { keyword: draft.keyword.trim(), limit: draft.limit, minWeight: Math.max(1, Math.min(100, Number(draft.minWeight) || 2)) }
  loading.value = true
  loadProgress.loaded = 0; loadProgress.total = 0
  error.value = ''
  try {
    let data
    if (params.limit === 0) {
      let offset = 0, snapshotId = '', total = null
      const rows = []
      do {
        const response = await api.get('/dashboard/tag-network', { params: { ...params, offset, pageSize: 500, snapshotId: snapshotId || undefined }, signal: request.signal, silent: true })
        if (controller !== request || request.signal.aborted) return
        const page = response.data || {}
        // 分页期间始终使用同一个目录快照，防止数据重排造成漏项或重复项。
        if (!page.snapshotId || (snapshotId && snapshotId !== page.snapshotId)) throw new Error('标签快照已失效，请重新加载')
        snapshotId = page.snapshotId
        const pageTotal = Number(page.total)
        if (!Number.isSafeInteger(pageTotal) || pageTotal < 0 || (total !== null && total !== pageTotal)) throw new Error('标签快照数量不一致，请重新加载')
        total = pageTotal
        rows.push(...(page.nodes || []))
        loadProgress.loaded = rows.length; loadProgress.total = total
        if (!page.hasMore) break
        const next = Number(page.nextOffset)
        if (!Number.isSafeInteger(next) || next <= offset || next !== rows.length || next > total) throw new Error('标签分页游标无效，请重新加载')
        offset = next
        // 每读完一页让浏览器先绘制进度并处理取消操作，避免连续加载阻塞交互。
        await new Promise(resolve => requestAnimationFrame(resolve))
      } while (!request.signal.aborted)
      // 只有数量与唯一标签数都完整时才替换旧图谱；失败时旧数据仍可继续浏览。
      if (rows.length !== total || new Set(rows.map(node => node.name)).size !== total) throw new Error('标签快照不完整，请重新加载')
      data = { nodes: rows, links: [] }
    } else {
      const response = await api.get('/dashboard/tag-network', { params, signal: request.signal, silent: true })
      data = response.data
    }
    if (controller !== request) return
    const nodes = [...new Map((data?.nodes || []).filter(node => node.name).map(node => [String(node.name), { ...node, name: String(node.name), namespace: node.namespace || 'misc', value: Math.max(0, Number(node.value) || 0) }])).values()]
    const names = new Set(nodes.map(node => node.name))
    const links = (data?.links || []).filter(edge => names.has(edge.source) && names.has(edge.target) && edge.source !== edge.target).map(edge => ({ ...edge, value: Math.max(1, Number(edge.value) || 1) }))
    payload.value = { nodes, links }
    browsePage.value = 1
    Object.assign(applied, params)
    namespace.value = ''
    clearSelection()
    rebuildLayout()
    hasLoaded.value = true
  } catch (failure) {
    if (controller === request && failure.code !== 'ERR_CANCELED') error.value = failure.response?.data?.msg || failure.message || '标签关系加载失败'
  } finally {
    if (controller === request) loading.value = false
  }
}
// 只取消正在读取的标签目录，保留上一次完整加载的数据供继续浏览。
function cancelCatalog() { controller?.abort(); controller = null; loading.value = false }
// 只请求选中标签的有限条关联，切换标签时取消旧请求，避免侧栏数据串位。
async function loadNeighbors(name) {
  neighborController?.abort()
  const request = new AbortController(); neighborController = request
  neighborLoading.value = true; neighborError.value = ''; neighborPayload.value = { nodes: [], links: [] }
  neighborTotal.value = 0; neighborTruncated.value = false
  try {
    const response = await api.get('/dashboard/tag-network/neighbors', { params: { tag: name, keyword: applied.keyword, minWeight: applied.minWeight, limit: 100 }, signal: request.signal, silent: true })
    if (neighborController !== request || selectedName.value !== name) return
    neighborPayload.value = response.data || { nodes: [], links: [] }
    neighborTotal.value = Number(response.data?.totalConnections) || 0
    neighborTruncated.value = !!response.data?.truncated
  } catch (failure) {
    if (neighborController === request && failure.code !== 'ERR_CANCELED') neighborError.value = '关联查询失败，请重试。'
  } finally { if (neighborController === request) neighborLoading.value = false }
}

// 将浏览器坐标转为 SVG 内部坐标，保证页面缩放后拖动位置仍准确。
function localPoint(event) {
  const point = svgRef.value.createSVGPoint()
  point.x = event.clientX
  point.y = event.clientY
  return point.matrixTransform(svgRef.value.getScreenCTM().inverse())
}
function worldPoint(point) { return { x: (point.x - viewport.width / 2 - view.x) / worldScale.value + scene.centerX, y: (point.y - viewport.height / 2 - view.y) / worldScale.value + scene.centerY } }
// 围绕指定屏幕位置缩放，并同步平移，避免滚轮放大时图谱突然跳走。
function zoomAt(nextZoom, anchor) {
  const next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, nextZoom))
  const ratio = next / view.zoom
  view.x = anchor.x - viewport.width / 2 - (anchor.x - viewport.width / 2 - view.x) * ratio
  view.y = anchor.y - viewport.height / 2 - (anchor.y - viewport.height / 2 - view.y) * ratio
  view.zoom = next
}
function zoomBy(factor) { if (allMode.value) largeCanvasRef.value?.zoomBy(factor); else zoomAt(view.zoom * factor, { x: viewport.width / 2, y: viewport.height / 2 }) }
function onWheel(event) {
  const delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? viewport.height : 1)
  zoomAt(view.zoom * Math.exp(-Math.max(-160, Math.min(160, delta)) * .0025), localPoint(event))
}
// 记录点击目标和手势起点，双指输入优先切换成缩放。
function startGesture(event, name = '', group = '') {
  if (event.button !== 0 || loading.value) return
  const point = localPoint(event)
  pointers.set(event.pointerId, point)
  svgRef.value.setPointerCapture(event.pointerId)
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()]
    pinch = { distance: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)), center: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 } }
    gesture = null
    gestureActive.value = true
    return
  }
  if (pointers.size > 2) return
  gesture = { id: event.pointerId, name, group, start: point, last: point, moved: false }
}
// 根据指针数量处理拖动或双指缩放，超过移动阈值后不再当成点击。
function moveGesture(event) {
  if (!pointers.has(event.pointerId)) return
  const point = localPoint(event)
  pointers.set(event.pointerId, point)
  if (pinch && pointers.size === 2) {
    const [a, b] = [...pointers.values()]
    const distance = Math.max(1, Math.hypot(a.x - b.x, a.y - b.y))
    const center = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
    view.x += center.x - pinch.center.x
    view.y += center.y - pinch.center.y
    zoomAt(view.zoom * distance / pinch.distance, center)
    pinch = { distance, center }
    return
  }
  if (!gesture || gesture.id !== event.pointerId) return
  if (!gesture.moved && Math.hypot(point.x - gesture.start.x, point.y - gesture.start.y) < 5) return
  gesture.moved = true
  gestureActive.value = true
  if (gesture.name) {
    const next = worldPoint(point), previous = worldPoint(gesture.last), pos = position(gesture.name)
    pos.x += next.x - previous.x
    pos.y += next.y - previous.y
  } else {
    view.x += point.x - gesture.last.x
    view.y += point.y - gesture.last.y
  }
  gesture.last = point
}
// 手势结束后仅对未拖动的指针执行节点或分类选择。
function endGesture(event) {
  const finished = gesture
  const wasPinching = !!pinch
  pointers.delete(event.pointerId)
  gesture = null
  pinch = null
  gestureActive.value = false
  if (svgRef.value.hasPointerCapture(event.pointerId)) svgRef.value.releasePointerCapture(event.pointerId)
  if (wasPinching || !finished || finished.id !== event.pointerId || finished.moved) return
  if (finished.name) selectNode(finished.name)
  else if (finished.group) chooseNamespace(finished.group)
  else clearSelection()
}
// 指针取消或捕获丢失时重置状态，防止松手后画布仍继续移动。
function cancelGesture(event) {
  pointers.delete(event.pointerId)
  gesture = null
  pinch = null
  gestureActive.value = false
}
function handleKeydown(event) {
  if (event.key === 'Escape') {
    clearSelection()
    document.activeElement?.blur?.()
  }
}
watch(onlyNeighbors, () => { hoveredName.value = '' })
watch([locator, namespace], () => { browsePage.value = 1 })
function updateFontScale() { fontScale.value = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--app-font-scale')) || 1 }
onMounted(() => {
  resizeObserver = new ResizeObserver(entries => {
    const rect = entries[0].contentRect
    viewport.width = Math.max(1, rect.width)
    viewport.height = Math.max(1, rect.height)
  })
  resizeObserver.observe(canvasRef.value)
  window.addEventListener('keydown', handleKeydown)
  updateFontScale()
  window.addEventListener('app-font-scale-change', updateFontScale)
  load()
})
onUnmounted(() => { controller?.abort(); neighborController?.abort(); resizeObserver?.disconnect(); window.removeEventListener('keydown', handleKeydown); window.removeEventListener('app-font-scale-change', updateFontScale); pointers.clear() })
</script>

<style scoped>
.network-page { --accent: #4169e1; --ink: #203049; --muted: #7e8b9f; color: var(--ink); max-width: 1920px; margin: 0 auto; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif; }
.catalog-progress { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin: -5px 0 16px; padding: 12px 16px; background: #edf3ff; border-radius: 8px; color: #5c759f; font-size: calc(12px * var(--app-font-scale, 1)); }
.catalog-progress progress { accent-color: #5279df; height: 7px; flex: 1; min-width: 100px; }
.catalog-note, .side-notice { color: #8a99ae; font-size: calc(11px * var(--app-font-scale, 1)); line-height: 1.7; }
.catalog-note { margin: -4px 2px 15px; }
.side-notice { padding: 0 19px; }.side-notice button { margin-left: 6px; }
.catalog-pager { border-top: 1px solid #edf0f5; display: flex; justify-content: space-between; align-items: center; padding: 12px; font-size: calc(10px * var(--app-font-scale, 1)); color: #8a99ae; }
.catalog-pager button { border: 0; background: #f0f4fc; color: #5f7fba; padding: 6px 9px; border-radius: 5px; font: inherit; }
button, input, select { font: inherit; }
button { cursor: pointer; }
button:disabled { cursor: not-allowed; opacity: .45; }
button:focus-visible, input:focus-visible, select:focus-visible { outline: 3px solid #93b2ff; outline-offset: 3px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin: 5px 0 23px; }
.eyebrow { font-size: calc(10px * var(--app-font-scale, 1)); font-weight: 700; letter-spacing: .18em; color: var(--accent); margin: 0 0 9px; }
h1 { font-size: calc(27px * var(--app-font-scale, 1)); letter-spacing: -.03em; margin: 0; font-weight: 750; display: flex; align-items: center; gap: 18px; }
h1 span { font-size: calc(13px * var(--app-font-scale, 1)); color: #95a0b1; font-weight: 400; letter-spacing: 0; border-left: 1px solid #d7deea; padding-left: 18px; }
.page-description { margin: 9px 0 0; font-size: calc(13px * var(--app-font-scale, 1)); color: var(--muted); line-height: 1.6; }
.button { border: 1px solid transparent; border-radius: 8px; display: inline-flex; justify-content: center; align-items: center; gap: 7px; padding: 10px 15px; font-size: calc(13px * var(--app-font-scale, 1)); font-weight: 600; white-space: nowrap; transition: background .16s, box-shadow .16s; }
.primary { color: #fff; background: var(--accent); box-shadow: 0 3px 8px #4169e11a; }
.primary:hover:not(:disabled) { background: #3659c4; }
.secondary { color: #586780; background: #fff; border-color: #dce3ed; }
.secondary:hover:not(:disabled) { background: #f8faff; border-color: #c0cfeb; }
.query-bar { background: #fff; border: 1px solid #e3e8f0; border-radius: 12px; padding: 17px 20px; display: flex; align-items: flex-end; gap: 16px; flex-wrap: wrap; margin-bottom: 18px; }
.query-field { display: flex; flex-direction: column; gap: 7px; }
.query-field > span { font-size: calc(11px * var(--app-font-scale, 1)); color: #6d7a8f; font-weight: 600; }
.keyword-field { flex: 1; min-width: 220px; max-width: 470px; }
.search-control, .number-control, .query-field select { border: 1px solid #dfe5ef; background: #fafbfd; border-radius: 7px; height: 37px; box-sizing: border-box; }
.search-control { display: flex; align-items: center; padding: 0 10px; gap: 9px; color: #98a4b5; }
.search-control:focus-within, .locator:focus-within { border-color: #7d9bea; box-shadow: 0 0 0 2px #4169e10f; }
.search-control input { border: 0; outline: none; background: transparent; width: 100%; font-size: calc(13px * var(--app-font-scale, 1)); color: var(--ink); }
input::placeholder { color: #a2abba; }
.query-field select { padding: 0 32px 0 10px; font-size: calc(12px * var(--app-font-scale, 1)); color: #52617a; }
.number-control { display: flex; align-items: center; padding-right: 10px; font-size: calc(12px * var(--app-font-scale, 1)); color: #98a4b5; }
.number-control input { width: 62px; border: 0; padding-left: 10px; background: transparent; color: var(--ink); outline: none; }
.pending-note { font-size: calc(11px * var(--app-font-scale, 1)); color: #b48329; align-self: center; }
.workspace { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 17px; align-items: start; }
.graph-panel { overflow: hidden; border: 1px solid #e0e6f0; border-radius: 13px; background: #fff; min-width: 0; }
.graph-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 13px 16px; }
.view-tabs { display: flex; gap: 3px; background: #f1f4f9; padding: 3px; border-radius: 8px; }
.view-tabs button { display: flex; align-items: center; gap: 7px; border: 0; border-radius: 6px; background: transparent; color: #8290a4; padding: 8px 12px; font-size: calc(12px * var(--app-font-scale, 1)); }
.view-tabs button.active { background: #fff; color: var(--accent); box-shadow: 0 1px 5px #1e305414; font-weight: 600; }
.graph-options { display: flex; gap: 13px; font-size: calc(11px * var(--app-font-scale, 1)); color: #8290a4; }
.graph-options label, .selection-actions label { display: flex; align-items: center; gap: 5px; cursor: pointer; }
input[type="checkbox"] { accent-color: var(--accent); margin: 0; }
.namespace-bar { display: flex; flex-wrap: wrap; gap: 6px; padding: 0 16px 13px; }
.namespace-bar button { display: flex; align-items: center; gap: 5px; padding: 5px 8px; background: #fff; color: #7f8b9e; border: 1px solid #edf0f5; border-radius: 5px; font-size: calc(10px * var(--app-font-scale, 1)); }
.namespace-bar button i { width: 6px; height: 6px; border-radius: 50%; background: var(--category-color); }
.namespace-bar small { color: #a4adbc; font-size: calc(10px * var(--app-font-scale, 1)); margin-left: 2px; }
.namespace-bar button.active { background: #eff4ff; color: var(--accent); border-color: #cddafd; }
.namespace-bar button:hover { background: #f6f8fc; }
.canvas { position: relative; height: clamp(540px, 69vh, 800px); overflow: hidden; background: radial-gradient(ellipse at 45% 42%, #192a46, #0e182b 78%); user-select: none; }
.network-svg { width: 100%; height: 100%; display: block; cursor: grab; touch-action: none; }
.dragging .network-svg, .dragging .graph-node { cursor: grabbing; }
.canvas-status { position: absolute; top: 19px; left: 20px; display: flex; align-items: center; gap: 7px; color: #b1bed2; font-size: calc(11px * var(--app-font-scale, 1)); pointer-events: none; z-index: 1; }
.canvas-status > i { width: 5px; height: 5px; background: #84b5fb; border-radius: 50%; box-shadow: 0 0 10px #84b5fb77; }
.status-count { margin-left: 4px; color: #627591; font-size: calc(10px * var(--app-font-scale, 1)); }
.graph-node { cursor: pointer; opacity: 1; transition: opacity .16s; outline: none; }
.graph-node.dimmed { opacity: .18; }
.graph-node:focus-visible > circle:not(.node-hit) { stroke: #fff; stroke-width: 2.5; }
.node-label { pointer-events: none; }
.node-label text { fill: #c7d5e8; font-family: inherit; font-size: calc(11px * var(--app-font-scale, 1)); paint-order: stroke; stroke: #14233b; stroke-width: 4px; stroke-linejoin: round; }
.graph-node.selected .node-label text { fill: #fff; font-weight: 600; }
.edges { pointer-events: none; }
.cluster { cursor: zoom-in; outline: none; }
.cluster:hover > circle, .cluster:focus-visible > circle { fill-opacity: .9; stroke-opacity: .8; }
.cluster text { pointer-events: none; letter-spacing: .06em; }
.canvas-breadcrumb { position: absolute; top: 46px; left: 20px; display: flex; align-items: center; gap: 8px; font-size: calc(10px * var(--app-font-scale, 1)); color: #8ea4c3; max-width: calc(100% - 40px); }
.canvas-breadcrumb button { display: flex; align-items: center; gap: 5px; border: 1px solid #3a4e6d; color: #b7c8e0; background: #172740; padding: 5px 8px; border-radius: 5px; font-size: calc(10px * var(--app-font-scale, 1)); }
.canvas-breadcrumb > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.canvas-hint { position: absolute; left: 20px; bottom: 14px; margin: 0; font-size: calc(10px * var(--app-font-scale, 1)); color: #687e9d; pointer-events: none; }
.zoom-controls { display: flex; align-items: center; gap: 1px; position: absolute; right: 17px; bottom: 39px; padding: 3px; background: #1c2c45e8; border: 1px solid #344662; border-radius: 8px; box-shadow: 0 4px 15px #0002; }
.zoom-controls button { width: 31px; height: 30px; display: grid; place-items: center; background: transparent; border: 0; color: #becde3; border-radius: 4px; }
.zoom-controls button:hover:not(:disabled) { background: #334968; color: #fff; }
.zoom-controls > span { min-width: 43px; text-align: center; font-size: calc(10px * var(--app-font-scale, 1)); color: #aebed5; font-variant-numeric: tabular-nums; }
.zoom-controls i:not(.el-icon) { border-left: 1px solid #3c4f6a; height: 15px; margin: 0 3px; }
.graph-footer { display: flex; justify-content: space-between; gap: 12px; padding: 12px 16px; font-size: calc(10px * var(--app-font-scale, 1)); color: #93a0b1; }
.graph-footer > span { display: flex; align-items: center; gap: 6px; }
.graph-footer i { border: 1px solid #97afd3; height: 7px; width: 7px; border-radius: 50%; }
.inspector { border: 1px solid #e0e6f0; border-radius: 13px; background: #fff; overflow: hidden; }
.inspector-heading { display: flex; align-items: center; justify-content: space-between; padding: 17px 19px; min-height: 23px; border-bottom: 1px solid #edf0f5; font-size: calc(12px * var(--app-font-scale, 1)); font-weight: 600; }
.inspector-heading > .el-icon { color: #9faec4; font-size: calc(17px * var(--app-font-scale, 1)); }
.icon-button { border: 0; background: #f3f5fa; width: 24px; height: 24px; border-radius: 5px; color: #8594ab; display: grid; place-items: center; }
.intro-copy { padding: 24px 20px 18px; }
.intro-icon { width: 35px; height: 35px; display: grid; place-items: center; background: #eff4ff; border-radius: 10px; color: #6c8ee6; font-size: calc(19px * var(--app-font-scale, 1)); }
.intro-copy h2 { font-size: calc(16px * var(--app-font-scale, 1)); font-weight: 600; margin: 16px 0 8px; }
.intro-copy p { font-size: calc(12px * var(--app-font-scale, 1)); color: #93a0b2; line-height: 1.8; margin: 0; }
.locator { display: flex; align-items: center; gap: 7px; margin: 0 19px 22px; padding: 9px 10px; border: 1px solid #e1e7f0; background: #fafbfd; border-radius: 7px; color: #9aa8bc; }
.locator input { border: 0; outline: 0; background: transparent; min-width: 0; font-size: calc(11px * var(--app-font-scale, 1)); width: 100%; color: var(--ink); }
.section-caption { display: flex; justify-content: space-between; align-items: center; gap: 6px; font-size: calc(11px * var(--app-font-scale, 1)); color: #60728d; }
.section-caption > span { font-size: calc(10px * var(--app-font-scale, 1)); color: #a7b0bf; }
.browse-caption { padding: 0 19px 10px; }
.tag-list { max-height: 390px; overflow-y: auto; scrollbar-width: thin; }
.browse-list { max-height: 365px; padding-bottom: 8px; }
.tag-row { display: flex; align-items: center; gap: 9px; width: 100%; padding: 12px 19px; border: 0; text-align: left; color: var(--ink); background: transparent; border-top: 1px solid #f2f4f8; }
.tag-row:hover, .tag-row:focus-visible { background: #f4f7ff; }
.tag-row > i { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.tag-row > span { flex: 1; min-width: 0; }
.tag-row b { display: block; font-size: calc(12px * var(--app-font-scale, 1)); font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tag-row small { display: block; font-size: calc(9px * var(--app-font-scale, 1)); color: #a4afbf; margin-top: 4px; }
.tag-row em { font-style: normal; font-size: calc(10px * var(--app-font-scale, 1)); color: #91a0b7; font-variant-numeric: tabular-nums; white-space: nowrap; }
.tag-row > .el-icon { color: #b7c1d0; font-size: calc(11px * var(--app-font-scale, 1)); }
.selected-summary { padding: 23px 19px 19px; }
.category-label { font-size: calc(10px * var(--app-font-scale, 1)); display: flex; align-items: center; gap: 6px; }
.category-label i { height: 6px; width: 6px; border-radius: 50%; }
.selected-summary h2 { font-size: calc(22px * var(--app-font-scale, 1)); letter-spacing: -.02em; line-height: 1.4; margin: 12px 0 5px; overflow-wrap: anywhere; }
.selected-summary code { font-family: inherit; font-size: calc(10px * var(--app-font-scale, 1)); color: #a4aebe; overflow-wrap: anywhere; }
.node-metrics { display: grid; grid-template-columns: 1fr 1fr; margin: 25px 0 22px; }
.node-metrics > div + div { border-left: 1px solid #eaf0f7; padding-left: 25px; }
.node-metrics strong { font-size: calc(24px * var(--app-font-scale, 1)); font-weight: 600; font-variant-numeric: tabular-nums; }
.node-metrics span { display: block; font-size: calc(10px * var(--app-font-scale, 1)); color: #96a4b8; margin-top: 6px; }
.wide { width: 100%; font-size: calc(12px * var(--app-font-scale, 1)); }
.wide > .el-icon:last-child { margin-left: auto; }
.selection-actions { display: flex; justify-content: space-between; gap: 8px; margin-top: 17px; font-size: calc(10px * var(--app-font-scale, 1)); color: #91a0b4; }
.text-button { border: 0; background: none; color: var(--accent); padding: 0; font-size: inherit; cursor: pointer; }
.related-section { border-top: 1px solid #edf0f5; }
.related-section .section-caption { padding: 17px 19px 12px; }
.side-empty { text-align: center; padding: 30px 16px; color: #99a8bd; font-size: calc(12px * var(--app-font-scale, 1)); }
.side-empty .el-icon { font-size: calc(25px * var(--app-font-scale, 1)); }
.side-empty small { font-size: calc(10px * var(--app-font-scale, 1)); line-height: 1.7; }
.scope-note { font-size: calc(10px * var(--app-font-scale, 1)); line-height: 1.7; color: #96a3b6; margin: 13px 2px 0; }
.feedback { display: flex; align-items: center; gap: 8px; padding: 12px 16px; margin-bottom: 15px; font-size: calc(12px * var(--app-font-scale, 1)); border-radius: 8px; }
.error-feedback { color: #aa6431; background: #fff5e8; border: 1px solid #f3dfc5; }
.feedback .text-button { margin-left: auto; white-space: nowrap; }
.canvas-overlay { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px; color: #a2b4cc; padding: 25px; font-size: calc(12px * var(--app-font-scale, 1)); background: #101e33dd; z-index: 3; text-align: center; }
.canvas-overlay strong { color: #d1def0; font-size: calc(16px * var(--app-font-scale, 1)); font-weight: 500; }
.canvas-button { background: #263d5e; color: #cbdaf0; border-color: #3e5677; margin-top: 6px; }
.loader { height: 25px; width: 25px; border-radius: 50%; border: 2px solid #344969; border-top-color: #7aabf5; animation: spin 1s linear infinite; }
.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (min-width: 1600px) { .workspace { grid-template-columns: minmax(0, 1fr) 320px; } }
@media (max-width: 1150px) { .workspace { grid-template-columns: minmax(0, 1fr) 260px; gap: 12px; } .graph-toolbar { flex-wrap: wrap; } h1 span { display: none; } .graph-footer { flex-direction: column; gap: 6px; } .query-bar { padding: 15px; gap: 12px; } }
@media (max-width: 850px) { .workspace { grid-template-columns: 1fr; } .inspector { display: block; } .canvas { height: 560px; } .tag-list { max-height: 270px; } .page-heading { align-items: flex-start; } .page-heading > .button { padding: 8px 10px; font-size: calc(11px * var(--app-font-scale, 1)); } h1 { font-size: calc(23px * var(--app-font-scale, 1)); } .page-description { font-size: calc(12px * var(--app-font-scale, 1)); } .keyword-field { max-width: none; flex-basis: 100%; } .query-bar > .button { margin-left: auto; } .canvas-hint { font-size: calc(9px * var(--app-font-scale, 1)); right: 12px; } }
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { transition: none !important; } .spinning, .loader { animation-duration: 2s; } }
</style>
