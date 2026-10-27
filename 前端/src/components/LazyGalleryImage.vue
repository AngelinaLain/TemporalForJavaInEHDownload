<template>
  <div ref="host" class="lazy-gallery-image" :class="{ failed }">
    <img v-if="objectUrl" :src="objectUrl" :alt="alt" loading="lazy" decoding="async" @error="failImage" />
    <div v-else-if="failed" class="image-state"><el-icon><Picture /></el-icon><span>图片暂不可用</span><button @click.stop="load">重试</button></div>
    <div v-else class="image-state"><span v-if="loading" class="image-spinner" /><el-icon v-else><Picture /></el-icon><span>{{ loading ? '正在加载' : '滚动到此处加载' }}</span></div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { Picture } from '@element-plus/icons-vue'
import { fetchGalleryBlob } from '../utils/galleryMedia'
const props = defineProps({ src: { type: String, required: true }, alt: { type: String, default: '' } })
const host = ref(null), objectUrl = ref(''), loading = ref(false), failed = ref(false)
let observer, controller, visible = false
function release() { controller?.abort(); if (objectUrl.value) URL.revokeObjectURL(objectUrl.value); objectUrl.value = '' }
function failImage() { if (objectUrl.value) URL.revokeObjectURL(objectUrl.value); objectUrl.value = ''; failed.value = true }
async function load() {
  if (loading.value || !props.src) return
  const request = new AbortController()
  controller = request; loading.value = true; failed.value = false
  try {
    const blob = await fetchGalleryBlob(props.src, request.signal)
    if (controller === request && !request.signal.aborted) objectUrl.value = URL.createObjectURL(blob)
  } catch (error) { if (controller === request && error.code !== 'ERR_CANCELED') failed.value = true }
  finally { if (controller === request) loading.value = false }
}
watch(() => props.src, () => { release(); loading.value = false; failed.value = false; if (visible) load() })
onMounted(() => {
  observer = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting)) { visible = true; observer.disconnect(); load() }
  }, { rootMargin: '120px' })
  observer.observe(host.value)
})
onUnmounted(() => { observer?.disconnect(); release() })
</script>

<style scoped>
.lazy-gallery-image { width: 100%; aspect-ratio: 3 / 4; background: #f1f4f8; border-radius: 8px; overflow: hidden; position: relative; }
.lazy-gallery-image img { width: 100%; height: 100%; object-fit: contain; display: block; }
.image-state { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 9px; font-size: calc(11px * var(--app-font-scale, 1)); color: #9aa8bd; }
.image-state .el-icon { font-size: calc(24px * var(--app-font-scale, 1)); }.image-state button { border: 0; background: #e7edfa; color: #5475c8; padding: 5px 10px; border-radius: 4px; cursor: pointer; font: inherit; }
.image-spinner { width: 20px; height: 20px; border: 2px solid #dce4f0; border-top-color: #6086df; border-radius: 50%; animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
