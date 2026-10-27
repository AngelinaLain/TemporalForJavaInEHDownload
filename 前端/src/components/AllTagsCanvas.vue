<template>
  <canvas ref="canvas" class="all-tags-canvas" tabindex="0" role="img" aria-label="全部标签图谱，拖动平移、滚轮缩放；也可在右侧按名称定位标签" @wheel.prevent="wheel" @pointerdown="start" @pointermove="move" @pointerup="end" @pointercancel="cancel" @lostpointercapture="cancel" @pointerleave="leave" />
</template>

<script setup>
// 用单个画布展示大量标签，处理可视区域绘制、节点选择、拖动和缩放。
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { indexCatalog, layoutLargeCatalog } from '../utils/largeTagLayout'
import { namespaceColor, namespaceLabel } from '../utils/tagNetworkLayout'
const props = defineProps({ nodes: { type: Array, default: () => [] }, links: { type: Array, default: () => [] }, selectedName: String, hoveredName: String, mode: String, showLabels: Boolean })
const emit = defineEmits(['select', 'hover', 'clear', 'choose', 'zoom'])
const canvas = ref(null)
let model = layoutLargeCatalog([]), index = indexCatalog([]), observer, frame = 0, width = 900, height = 660
let zoom = 1, panX = 0, panY = 0, fit = 1, gesture, fontScale = 1
const pointers = new Map()
let pinch
// 读取全局字号后申请重绘，让画布文字与页面设置同步。
function measureFont() { fontScale = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--app-font-scale')) || 1; schedule() }
function scale() { return fit * zoom }
// 把图谱位置转换为屏幕位置，用于绘制节点、连线和文字。
function screen(point) { return { x: width / 2 + panX + (point.x - model.width / 2) * scale(), y: height / 2 + panY + (point.y - model.height / 2) * scale() } }
// 把鼠标位置转换回图谱位置，用于局部查找和保持缩放锚点。
function world(point) { return { x: (point.x - width / 2 - panX) / scale() + model.width / 2, y: (point.y - height / 2 - panY) / scale() + model.height / 2 } }
function point(event) { const rect = canvas.value.getBoundingClientRect(); return { x: event.clientX - rect.left, y: event.clientY - rect.top } }
function nodeSize(node) {
  const frequency = Math.max(0, Number(node.value) || 0)
  // 全局视图保留最小可见圆点；放大只展开位置，避免节点变成遮挡内容的大球。
  return Math.max(1.1, Math.min(12, (5 + Math.log1p(frequency) * .8) * fit)) / Math.pow(zoom, .12)
}
// 把连续的鼠标事件合并到下一帧，避免同一帧重复绘图。
function schedule() { if (!frame) frame = requestAnimationFrame(draw) }
// 先查出可见节点，再绘制分类、关联线和少量标签；选中节点之外的内容适当淡化。
function draw() {
  frame = 0
  if (!canvas.value) return
  const ctx = canvas.value.getContext('2d')
  const dpr = Math.min(2, window.devicePixelRatio || 1)
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, width, height)
  const currentScale = scale(), margin = 40 / currentScale
  const first = world({ x: 0, y: 0 }), last = world({ x: width, y: height })
  // 留出少量边缘余量，节点进入视野时即可绘制，又不必遍历画布外的全部标签。
  const visible = index.query(first.x - margin, first.y - margin, last.x + margin, last.y + margin)
  const active = props.hoveredName || props.selectedName
  const connected = new Set([active])
  for (const edge of props.links) {
    if (edge.source === active) connected.add(edge.target)
    if (edge.target === active) connected.add(edge.source)
  }
  if (props.mode === 'clusters') for (const group of model.clusters) {
    const center = screen(group), r = group.r * currentScale
    if (center.x + r < 0 || center.y + r < 0 || center.x - r > width || center.y - r > height) continue
    ctx.beginPath(); ctx.arc(center.x, center.y, r, 0, Math.PI * 2)
    ctx.fillStyle = namespaceColor(group.namespace) + '0b'; ctx.fill()
    ctx.strokeStyle = namespaceColor(group.namespace) + '66'; ctx.lineWidth = .7; ctx.setLineDash([4, 6]); ctx.stroke(); ctx.setLineDash([])
    ctx.fillStyle = namespaceColor(group.namespace); ctx.font = '500 ' + 11 * fontScale + 'px sans-serif'; ctx.textAlign = 'center'
    ctx.fillText(namespaceLabel(group.namespace), center.x, center.y - r + 18 * fontScale)
  }
  ctx.lineWidth = .6 / Math.sqrt(zoom)
  for (const edge of props.links) {
    const from = model.positions.get(edge.source), to = model.positions.get(edge.target)
    if (!from || !to) continue
    const a = screen(from), b = screen(to)
    ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y)
    ctx.strokeStyle = active && edge.source !== active && edge.target !== active ? '#829cbd15' : '#9abffb77'; ctx.stroke()
  }
  for (const item of visible) {
    const location = screen(item), size = nodeSize(item.node)
    ctx.globalAlpha = active && !connected.has(item.node.name) ? .22 : .9
    ctx.beginPath(); ctx.arc(location.x, location.y, size, 0, Math.PI * 2)
    ctx.fillStyle = namespaceColor(item.node.namespace) + '77'; ctx.fill()
    if (size > 3) { ctx.strokeStyle = namespaceColor(item.node.namespace); ctx.lineWidth = .9; ctx.stroke() }
    if (item.node.name === props.selectedName) {
      ctx.beginPath(); ctx.arc(location.x, location.y, size + 4, 0, Math.PI * 2); ctx.strokeStyle = '#f2f7ff'; ctx.stroke()
    }
  }
  ctx.globalAlpha = 1
  // 标签过多时只显示当前探索的名称，避免成千文字重叠并拖慢绘制。
  const labels = active ? visible.filter(item => item.node.name === active) : props.showLabels && visible.length <= 120 ? visible.slice(0, 18) : []
  ctx.font = 12 * fontScale + 'px sans-serif'; ctx.textAlign = 'center'; ctx.lineJoin = 'round'
  for (const item of labels) {
    const location = screen(item), label = item.node.label || item.node.name
    const short = label.includes(':') ? label.slice(label.indexOf(':') + 1) : label
    ctx.strokeStyle = '#14233b'; ctx.lineWidth = 4; ctx.strokeText(short.slice(0, 24), location.x, location.y + nodeSize(item.node) + 17 * fontScale)
    ctx.fillStyle = '#dbe7fa'; ctx.fillText(short.slice(0, 24), location.x, location.y + nodeSize(item.node) + 17 * fontScale)
  }
}
// 只查鼠标附近的网格，选出命中范围内最近的节点。
function hit(location) {
  const target = world(location), extent = 14 / scale()
  let nearest, distance = Infinity
  for (const item of index.query(target.x - extent, target.y - extent, target.x + extent, target.y + extent)) {
    const at = screen(item), delta = Math.hypot(at.x - location.x, at.y - location.y)
    if (delta < Math.max(8, nodeSize(item.node) + 3) && delta < distance) { nearest = item; distance = delta }
  }
  return nearest
}
// 限制缩放范围并同步调整平移量，让鼠标指向的位置在缩放前后保持不动。
function setZoom(value, anchor = { x: width / 2, y: height / 2 }) {
  const next = Math.max(.5, Math.min(20, value)), ratio = next / zoom
  panX = anchor.x - width / 2 - (anchor.x - width / 2 - panX) * ratio
  panY = anchor.y - height / 2 - (anchor.y - height / 2 - panY) * ratio
  zoom = next; emit('zoom', zoom); schedule()
}
// 根据当前画布尺寸重新适配完整图谱，并清除旧的平移和放大状态。
function resetView() {
  fit = Math.max(.001, Math.min(1.2, (width - 100) / Math.max(100, model.width), (height - 120) / Math.max(100, model.height)))
  zoom = 1; panX = 0; panY = 0; emit('zoom', zoom); schedule()
}
// 放大并居中指定标签，供侧栏的定位操作调用。
function focus(name) {
  const item = model.positions.get(name)
  if (!item) return
  zoom = Math.min(20, Math.max(2, .6 / fit))
  panX = -(item.x - model.width / 2) * scale(); panY = -(item.y - model.height / 2) * scale()
  emit('zoom', zoom); schedule()
}
// 将不同设备的滚轮单位归一后平滑缩放。
function wheel(event) { const delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? height : 1); setZoom(zoom * Math.exp(-Math.max(-160, Math.min(160, delta)) * .0025), point(event)) }
// 记录指针并捕获后续移动；出现第二个触点时改为双指缩放。
function start(event) {
  if (event.button !== 0) return
  const at = point(event); pointers.set(event.pointerId, at); canvas.value.setPointerCapture(event.pointerId)
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()]
    pinch = { distance: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)), center: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 } }; gesture = null; return
  }
  gesture = { id: event.pointerId, start: at, last: at, moved: false }
}
// 单指或鼠标拖动画布，双指同时平移和缩放；未拖动时只更新悬停节点。
function move(event) {
  const at = point(event)
  if (pointers.has(event.pointerId)) pointers.set(event.pointerId, at)
  if (pinch && pointers.size === 2) {
    const [a, b] = [...pointers.values()], distance = Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)), center = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
    panX += center.x - pinch.center.x; panY += center.y - pinch.center.y; setZoom(zoom * distance / pinch.distance, center); pinch = { distance, center }; return
  }
  if (gesture?.id === event.pointerId) {
    if (Math.hypot(at.x - gesture.start.x, at.y - gesture.start.y) > 5) gesture.moved = true
    if (gesture.moved) { panX += at.x - gesture.last.x; panY += at.y - gesture.last.y; gesture.last = at; schedule() }
  } else {
    const item = hit(at); emit('hover', item?.node.name || ''); canvas.value.style.cursor = item ? 'pointer' : 'grab'
  }
}
// 释放指针；只有没有明显移动的操作才视为点击，避免拖动后误选标签。
function end(event) {
  const finished = gesture, wasPinch = !!pinch
  pointers.delete(event.pointerId); gesture = null; pinch = null
  if (canvas.value.hasPointerCapture(event.pointerId)) canvas.value.releasePointerCapture(event.pointerId)
  if (!finished || finished.id !== event.pointerId || finished.moved || wasPinch) return
  const at = point(event), item = hit(at)
  if (item) emit('select', item.node.name)
  else if (props.mode === 'clusters') {
    const target = world(at), cluster = model.clusters.find(group => Math.hypot(group.x - target.x, group.y - target.y) <= group.r)
    if (cluster) emit('choose', cluster.namespace); else emit('clear')
  } else emit('clear')
}
// 手势取消或指针捕获丢失时清理状态，避免后续移动继续拖动画布。
function cancel(event) { pointers.delete(event.pointerId); gesture = null; pinch = null }
function leave() { if (!gesture) emit('hover', '') }
watch(() => props.nodes, () => { model = layoutLargeCatalog(props.nodes); index = indexCatalog(model.points); resetView() }, { immediate: true })
watch(() => [props.links, props.selectedName, props.hoveredName, props.mode, props.showLabels], schedule)
onMounted(() => {
  observer = new ResizeObserver(entries => {
    width = entries[0].contentRect.width; height = entries[0].contentRect.height
    const dpr = Math.min(2, window.devicePixelRatio || 1); canvas.value.width = Math.round(width * dpr); canvas.value.height = Math.round(height * dpr); resetView()
  })
  observer.observe(canvas.value); measureFont(); window.addEventListener('app-font-scale-change', measureFont)
})
onUnmounted(() => { observer?.disconnect(); cancelAnimationFrame(frame); window.removeEventListener('app-font-scale-change', measureFont) })
defineExpose({ zoomBy: factor => setZoom(zoom * factor), resetView, focus })
</script>

<style scoped>
.all-tags-canvas { display: block; width: 100%; height: 100%; touch-action: none; cursor: grab; background-image: radial-gradient(#33445f88 .8px, transparent .8px); background-size: 25px 25px; }
.all-tags-canvas:focus-visible { outline: 2px solid #84a8e6; outline-offset: -3px; }
</style>
