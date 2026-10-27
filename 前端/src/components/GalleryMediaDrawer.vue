<template>
  <el-drawer :model-value="modelValue" size="min(1100px, 96vw)" class="gallery-media-drawer" destroy-on-close @update:model-value="emit('update:modelValue', $event)" @closed="onDrawerClosed">
    <template #header><div class="drawer-heading"><span>画廊图片 · #{{ gallery?.gid }}</span><strong>{{ gallery?.title || '画廊预览' }}</strong></div></template>
    <div class="media-topline">
      <div><span class="source-badge">{{ sourceLabel }}</span><span class="media-count">{{ data.total || 0 }} 张图片</span></div>
      <div class="media-actions"><el-button size="small" :icon="Refresh" :loading="loading" :disabled="loading" @click="load(true)">重新检测本地图片</el-button><a v-if="sourceUrl" :href="sourceUrl" target="_blank" rel="noopener noreferrer">打开原画廊 ↗</a></div>
    </div>
    <p class="media-hint">预览读取本地归档或 Komga 图库。仅加载当前页，缩略图进入可视区域后再获取；点击图片查看大图。</p>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon class="media-alert" />
    <el-alert v-else-if="!loading && data.message" :title="data.message" :type="data.available === false ? 'warning' : 'info'" :closable="false" show-icon class="media-alert" />
    <div v-if="!loading && data.available === false && diagnostics.length" class="media-diagnostics" role="status"><strong>本地图片检测结果</strong><ul><li v-for="(issue, index) in diagnostics" :key="issue.code + ':' + index">{{ issue.message }}</li></ul></div>
    <div class="media-pagination"><el-pagination :current-page="page" :page-size="size" :page-sizes="[10, 20, 50, 100]" :total="Number(data.total) || 0" layout="total, sizes, prev, pager, next, jumper" @current-change="changePage" @size-change="changeSize" /></div>
    <div v-loading="loading" class="media-content">
      <div v-if="data.images?.length && modelValue" class="image-grid">
        <div v-for="image in data.images" :key="gallery.gid + ':' + image.index" class="image-tile" role="button" tabindex="0" :aria-label="'查看第 ' + image.index + ' 张图片'" @click="openImage(image)" @keydown.enter.self.prevent="openImage(image)" @keydown.space.self.prevent="openImage(image)">
          <LazyGalleryImage :src="image.thumbnailUrl" :alt="'第 ' + image.index + ' 张图片'" />
          <span><b>{{ String(image.index).padStart(3, '0') }}</b><small>{{ image.name || '第 ' + image.index + ' 页' }}</small><el-icon><ZoomIn /></el-icon></span>
        </div>
      </div>
      <el-empty v-else-if="!loading" :description="'暂无可读取的本地图片，可修复上方检测项后重新检测。'" :image-size="90" />
      <el-skeleton v-else :rows="8" animated />
    </div>
    <el-dialog v-model="viewerVisible" append-to-body width="min(1100px, 94vw)" :title="viewerTitle" destroy-on-close @closed="onViewerClosed">
      <div v-loading="viewerLoading" class="full-image"><img v-if="viewerUrl" :src="viewerUrl" :alt="viewerTitle" /><div v-else-if="viewerError" class="viewer-error"><p>{{ viewerError }}</p><el-button @click="openImage(viewerImage)">重试</el-button></div></div>
    </el-dialog>
  </el-drawer>
</template>

<script setup>
// 展示画廊的本地图片分页、检测结果和大图预览，关闭时清理未完成的请求。
import { computed, onUnmounted, ref, watch } from 'vue'
import { Refresh, ZoomIn } from '@element-plus/icons-vue'
import api from '../api'
import LazyGalleryImage from './LazyGalleryImage.vue'
import { fetchGalleryBlob } from '../utils/galleryMedia'
const props = defineProps({ modelValue: Boolean, gallery: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue'])
const page = ref(1), size = ref(20), data = ref({ images: [], total: 0 }), loading = ref(false), error = ref('')
const viewerVisible = ref(false), viewerLoading = ref(false), viewerUrl = ref(''), viewerError = ref(''), viewerImage = ref(null)
let controller, viewerController
const sourceLabel = computed(() => ({ KOMGA: 'Komga 本地图库', ARCHIVE: '本地归档', NONE: '本地图片暂不可用' }[data.value.source] || '本地图片预览'))
const diagnostics = computed(() => Array.isArray(data.value.diagnostics) ? data.value.diagnostics.filter(issue => issue && typeof issue.message === 'string' && issue.message.trim()) : [])
const sourceUrl = computed(() => { try { const url = new URL(props.gallery?.galleryUrl); return ['http:', 'https:'].includes(url.protocol) ? url.href : '' } catch { return '' } })
const viewerTitle = computed(() => '第 ' + (viewerImage.value?.index || '') + ' 张图片')
// 取消上一轮索引请求后读取当前页；只有主动重新检测才要求后端清除来源缓存。
async function load(refresh = false) {
  controller?.abort()
  if (!props.modelValue || !props.gallery?.gid) return
  const request = new AbortController()
  controller = request; loading.value = true; error.value = ''; data.value = { images: [], total: data.value.total }
  try {
    const res = await api.get('/dashboard/gallery-media/' + props.gallery.gid, { params: { page: page.value, size: size.value, ...(refresh === true ? { refresh: true } : {}) }, signal: request.signal })
    if (controller === request) {
      data.value = res.data || { images: [], total: 0 }
      page.value = Math.max(1, Number(data.value.page) || page.value)
      if ([10, 20, 50, 100].includes(Number(data.value.size))) size.value = Number(data.value.size)
    }
  } catch (failure) { if (controller === request && failure.code !== 'ERR_CANCELED') error.value = '本地图片索引加载失败，请检查归档挂载或 Komga 状态后重试。' }
  finally { if (controller === request) loading.value = false }
}
// 翻页前关闭大图，防止旧页图片与新页索引同时显示。
function changePage(value) { if (page.value !== value) { closeViewer(); page.value = value; load() } }
function changeSize(value) { if (size.value !== value) { closeViewer(); size.value = value; page.value = 1; load() } }
// 用户点击后才读取原图；只接收当前请求的结果，避免快速切图时显示旧图片。
async function openImage(image) {
  if (!image) return
  releaseViewer()
  viewerImage.value = image; viewerVisible.value = true; viewerLoading.value = true; viewerError.value = ''
  const request = new AbortController()
  viewerController = request
  try {
    const blob = await fetchGalleryBlob(image.imageUrl, request.signal)
    if (viewerController === request && !request.signal.aborted) viewerUrl.value = URL.createObjectURL(blob)
  } catch (failure) { if (viewerController === request && failure.code !== 'ERR_CANCELED') viewerError.value = '这张图片暂时无法加载。' }
  finally { if (viewerController === request) viewerLoading.value = false }
}
// 取消原图请求并释放浏览器临时地址，避免重复查看占用越来越多内存。
function releaseViewer() {
  viewerController?.abort(); viewerController = null; viewerLoading.value = false
  if (viewerUrl.value) URL.revokeObjectURL(viewerUrl.value)
  viewerUrl.value = ''; viewerError.value = ''
}
function closeViewer() { releaseViewer(); viewerVisible.value = false }
function onViewerClosed() { if (!viewerVisible.value) releaseViewer() }
function onDrawerClosed() { if (!props.modelValue) dispose() }
// 抽屉关闭或画廊切换时取消请求、关闭大图并清空本次预览。
function dispose() { controller?.abort(); controller = null; closeViewer(); data.value = { images: [], total: 0 }; loading.value = false }
watch(() => [props.modelValue, props.gallery?.gid], ([visible]) => {
  dispose()
  if (visible) { page.value = 1; load() }
}, { immediate: true })
onUnmounted(dispose)
</script>

<style scoped>
.drawer-heading { min-width: 0; display: flex; flex-direction: column; gap: 5px; }.drawer-heading > span { font-size: calc(11px * var(--app-font-scale, 1)); color: #9aa6b8; }.drawer-heading strong { font-size: calc(17px * var(--app-font-scale, 1)); color: #26364d; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 85vw; }
.media-topline { display: flex; justify-content: space-between; align-items: center; gap: 12px; }.media-topline > div { display: flex; align-items: center; gap: 10px; }.source-badge { font-size: calc(11px * var(--app-font-scale, 1)); color: #5b79bc; background: #edf3ff; border-radius: 5px; padding: 5px 8px; }.media-count { font-size: calc(12px * var(--app-font-scale, 1)); color: #9aa7b9; }.media-topline a { font-size: calc(12px * var(--app-font-scale, 1)); color: #557ad4; text-decoration: none; }.media-hint { font-size: calc(11px * var(--app-font-scale, 1)); color: #9ba6b5; line-height: 1.7; margin: 13px 0 20px; }.media-content { min-height: 260px; }.media-alert { margin-bottom: 15px; }.image-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(155px, 1fr)); gap: 17px; }.image-tile { background: #fff; padding: 0; border: 1px solid #e7ecf4; border-radius: 9px; cursor: zoom-in; overflow: hidden; min-width: 0; font: inherit; text-align: left; }.image-tile:hover { border-color: #a9bfe9; box-shadow: 0 4px 13px #253f6b0b; }.image-tile > span { display: flex; align-items: center; gap: 7px; padding: 10px; color: #96a5bb; }.image-tile b { color: #617997; font-size: calc(10px * var(--app-font-scale, 1)); font-variant-numeric: tabular-nums; }.image-tile small { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: calc(10px * var(--app-font-scale, 1)); }.image-tile .el-icon { font-size: calc(12px * var(--app-font-scale, 1)); }.media-pagination { margin-top: 24px; display: flex; justify-content: center; overflow-x: auto; padding-bottom: 5px; }.full-image { min-height: 240px; display: flex; justify-content: center; align-items: center; }.full-image img { max-width: 100%; max-height: 80vh; object-fit: contain; }.viewer-error { text-align: center; color: #8c9aae; font-size: calc(13px * var(--app-font-scale, 1)); }
@media (max-width: 600px) { .image-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }.media-pagination { justify-content: flex-start; }.media-topline { align-items: flex-start; flex-direction: column; } }
.media-pagination { position: sticky; top: -20px; z-index: 2; margin: 0 0 18px; padding: 12px 0; background: #fffffff5; border-bottom: 1px solid #edf1f7; backdrop-filter: blur(8px); justify-content: flex-start; }
.image-tile:focus-visible { outline: 3px solid #9ab8f1; outline-offset: 3px; }
.media-actions { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.media-diagnostics { margin: 0 0 18px; padding: 13px 16px; border: 1px solid #e7edf6; border-radius: 8px; background: #f7f9fc; color: #71819b; font-size: calc(12px * var(--app-font-scale, 1)); line-height: 1.8; }
.media-diagnostics strong { font-size: calc(12px * var(--app-font-scale, 1)); font-weight: 600; color: #536a89; }
.media-diagnostics ul { margin: 7px 0 0; padding-left: 18px; overflow-wrap: anywhere; }
</style>
