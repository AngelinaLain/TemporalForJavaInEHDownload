<template>
  <div class="galleries-page">
    <header class="page-heading">
      <div><p class="eyebrow">YOUR GALLERY LIBRARY</p><h1>画廊资料库<span>检索、归类与图片浏览</span></h1><p>组合关键词、标签、合集与内容指标定位作品。点击标题浏览图片，停留一秒查看封面。</p></div>
      <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新列表</el-button>
    </header>

    <section class="search-panel">
      <form @submit.prevent="applyFilters">
        <div class="primary-search">
          <div class="keyword-control"><el-icon><Search /></el-icon><el-autocomplete v-model="draft.keyword" :fetch-suggestions="fetchTitleSuggestions" placeholder="搜索标题、原始标题或文件名" :trigger-on-focus="false" :debounce="350" clearable aria-label="画廊关键词" @select="item => draft.keyword = item.value" /></div>
          <el-select v-model="draft.keywordMode" aria-label="关键词匹配方式" class="match-control"><el-option label="包含关键词" value="contains" /><el-option label="包含全部词" value="all" /><el-option label="包含任一词" value="any" /><el-option label="精确匹配" value="exact" /></el-select>
          <el-button type="primary" native-type="submit" :icon="Search" :loading="loading">检索画廊</el-button>
          <el-button @click="resetFilters" :icon="RefreshLeft">重置</el-button>
        </div>
        <div class="quick-filters">
          <label><span>状态</span><el-select v-model="draft.status" clearable placeholder="全部状态" aria-label="下载状态"><el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" /></el-select></label>
          <label><span>合集类型</span><el-select v-model="draft.collectionScope" aria-label="合集类型"><el-option label="全部作品" value="all" /><el-option label="已归入合集" value="assigned" /><el-option label="单篇 / 未归类" value="unassigned" /></el-select></label>
          <label><span>作品版本</span><el-select v-model="draft.dedupe" aria-label="作品版本"><el-option label="首选版本" value="preferred" /><el-option label="全部版本" value="all" /><el-option label="重复版本" value="duplicates" /></el-select></label>
          <label><span>Komga</span><el-select v-model="draft.komga" aria-label="Komga 关联"><el-option label="全部" value="all" /><el-option label="已关联" value="linked" /><el-option label="未关联" value="unlinked" /></el-select></label>
          <button type="button" class="advanced-toggle" :class="{ active: advancedVisible }" :aria-expanded="advancedVisible" @click="advancedVisible = !advancedVisible"><el-icon><Filter /></el-icon>{{ advancedVisible ? '收起高级检索' : '高级检索' }}<span v-if="advancedCount">{{ advancedCount }}</span><el-icon><ArrowUp v-if="advancedVisible" /><ArrowDown v-else /></el-icon></button>
        </div>

        <div v-if="advancedVisible" class="advanced-search">
          <div class="advanced-grid">
            <label class="field wide-field"><span>包含标签 <small>中文联想；回车添加标签</small></span><div class="tag-input-line"><el-autocomplete v-model="tagInput" :fetch-suggestions="fetchTagSuggestions" :trigger-on-focus="false" :debounce="300" placeholder="添加一个或多个标签" clearable aria-label="包含标签" @select="item => addTag(item.value)" @keydown.enter.prevent @keyup.enter="addTag(tagInput)"><template #default="{ item }"><span>{{ item.label || item.value }}</span></template></el-autocomplete><el-select v-model="draft.tagMode" aria-label="标签匹配方式"><el-option label="同时包含" value="all" /><el-option label="包含任一" value="any" /></el-select></div><div v-if="includedTags.length" class="tag-tokens"><el-tag v-for="tag in includedTags" :key="tag" closable @close="removeTag(tag)">{{ tagStore.translate(tag) }}</el-tag></div></label>
            <label class="field wide-field"><span>排除标签</span><el-autocomplete v-model="excludeInput" :fetch-suggestions="fetchExcludeSuggestions" :trigger-on-focus="false" :debounce="300" placeholder="不希望出现的标签" clearable aria-label="排除标签" @select="item => addTag(item.value, true)" @keydown.enter.prevent @keyup.enter="addTag(excludeInput, true)"><template #default="{ item }"><span>{{ item.label || item.value }}</span></template></el-autocomplete><div v-if="draft.excludeTags.length" class="tag-tokens"><el-tag v-for="tag in draft.excludeTags" :key="tag" closable type="danger" @close="draft.excludeTags = draft.excludeTags.filter(item => item !== tag)">{{ tagStore.translate(tag) }}</el-tag></div></label>
            <label class="field"><span>GID <small>逗号、空格或分号分隔</small></span><el-input v-model="draft.gid" clearable placeholder="例如 12345, 67890" aria-label="GID" /></label>
            <label class="field"><span>指定合集</span><el-select v-model="draft.collectionId" clearable filterable placeholder="全部合集" aria-label="指定合集"><el-option v-for="item in collections" :key="item.id" :value="String(item.id)" :label="item.name" /></el-select></label>
            <label class="field"><span>评分范围</span><div class="range-input"><input v-model="draft.minRating" type="number" min="0" max="5" step=".1" placeholder="最低" aria-label="最低评分" /><i>—</i><input v-model="draft.maxRating" type="number" min="0" max="5" step=".1" placeholder="最高" aria-label="最高评分" /></div></label>
            <label class="field"><span>页数范围</span><div class="range-input"><input v-model="draft.minPages" type="number" min="0" step="1" placeholder="最少" aria-label="最少页数" /><i>—</i><input v-model="draft.maxPages" type="number" min="0" step="1" placeholder="最多" aria-label="最多页数" /></div></label>
            <label class="field"><span>文件大小 <small>MB</small></span><div class="range-input"><input v-model="draft.minSizeMb" type="number" min="0" placeholder="最小" aria-label="最小文件大小" /><i>—</i><input v-model="draft.maxSizeMb" type="number" min="0" placeholder="最大" aria-label="最大文件大小" /></div></label>
            <label class="field"><span>抓取时间</span><div class="range-input date-input"><input v-model="draft.from" type="date" aria-label="抓取开始日期" /><i>—</i><input v-model="draft.to" type="date" aria-label="抓取结束日期" /></div></label>
            <label class="field"><span>检索来源 <small>精确匹配采集关键词</small></span><el-input v-model="draft.searchQuery" clearable placeholder="采集时使用的搜索词" aria-label="检索来源" /></label>
          </div>
        </div>
      </form>
      <div v-if="recentKeywords.length" class="search-history"><span>最近检索</span><button v-for="word in recentKeywords" :key="word" @click="useRecent(word)">{{ word }}</button><button class="clear-history" title="清空最近检索" @click="clearHistory">清空</button></div>
    </section>

    <div class="active-filters" v-if="activeChips.length"><span>当前条件</span><el-tag v-for="chip in activeChips" :key="chip.key" closable effect="plain" @close="clearChip(chip.key)">{{ chip.label }}</el-tag><button @click="resetFilters">清除全部</button></div>
    <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="list-error" />

    <section class="results-panel">
      <header class="result-heading"><div><h2>检索结果 <span>{{ pagination.total.toLocaleString('zh-CN') }}</span></h2><small>{{ pendingChanges ? '检索条件已修改，点击“检索画廊”应用' : '列表不预载图片 · 悬停同一行 1 秒显示封面' }}</small></div><div class="sort-controls"><span>排序</span><el-select :model-value="sort.prop" aria-label="排序字段" @change="setSort"><el-option v-for="item in sortOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select><el-button :icon="sort.order === 'desc' ? SortDown : SortUp" :aria-label="sort.order === 'desc' ? '切换为升序' : '切换为降序'" @click="toggleOrder">{{ sort.order === 'desc' ? '降序' : '升序' }}</el-button></div></header>
      <div v-if="firstLoad && loading" class="table-skeleton"><el-skeleton :rows="9" animated /></div>
      <div v-else class="table-wrap" v-loading="loading">
        <table class="gallery-table">
          <thead><tr><th scope="col">GID</th><th scope="col" class="title-column">画廊 / 标签</th><th scope="col">合集类型</th><th scope="col">状态 / 版本</th><th scope="col">评分 / 页数</th><th scope="col">文件大小</th><th scope="col">抓取时间</th><th scope="col">操作</th></tr></thead>
          <tbody><tr v-for="row in tableData" :key="row.gid" class="gallery-row" @pointerenter="scheduleHover(row, $event)" @pointermove="positionHover($event)" @pointerleave="hideHover">
            <td class="gid-cell">{{ row.gid }}</td>
            <td class="title-cell"><button class="gallery-title" @click="openMedia(row)">{{ row.title || '未命名画廊' }}<el-icon><Picture /></el-icon></button><div class="row-tags"><button v-for="tag in (row.tags || []).slice(0, 3)" :key="tag" :title="tagStore.translate(tag)" @click="searchTag(tag)">{{ tagStore.translate(tag) }}</button><span v-if="row.tags?.length > 3">+{{ row.tags.length - 3 }}</span><span v-if="!row.tags?.length" class="no-tags">暂无标签</span></div></td>
            <td><button v-if="row.collectionId" class="collection-link" :title="row.collectionName" @click="searchCollection(row)"><el-icon><Collection /></el-icon><span>合集</span><small>{{ row.collectionName }}</small></button><span v-else class="standalone-label">单篇 / 未归类</span></td>
            <td><el-tag :type="getStatusMeta(row.downloadStatus).type" size="small" effect="light">{{ getStatusMeta(row.downloadStatus).label }}</el-tag><small class="version-label" :class="{ duplicate: row.duplicateOfGid }">{{ row.duplicateOfGid ? '重复 → ' + row.duplicateOfGid : row.dedupeKey ? '首选版本' : '未识别版本' }}</small></td>
            <td><span class="rating"><el-icon><StarFilled /></el-icon>{{ rating(row.rating) }}</span><small class="muted-meta">{{ row.pageCount ?? '—' }} 页</small></td>
            <td class="size-cell">{{ row.fileSizeMb != null ? Number(row.fileSizeMb).toFixed(1) + ' MB' : '—' }}</td>
            <td><span class="date-cell">{{ formatDate(row.crawledAt) }}</span><small class="muted-meta">{{ formatTime(row.crawledAt) }}</small></td>
            <td><div class="row-actions"><button class="detail-action" @click="showDetail(row)">详情</button><button class="komga-action" :disabled="!!openingKomga" :title="row.komgaBookId ? '在 Komga 中打开此画廊' : '尚未关联 Komga 图书'" @click="openKomga(row)"><el-icon><TopRight /></el-icon>{{ openingKomga === row.gid ? '打开中' : 'Komga' }}</button></div></td>
          </tr></tbody>
        </table>
        <el-empty v-if="!tableData.length && !loading" description="没有符合条件的画廊，试试减少筛选条件" :image-size="95"><el-button @click="resetFilters">重置检索</el-button></el-empty>
      </div>
      <footer class="results-footer"><span>点击标题浏览图片，点击标签或合集可继续检索</span><el-pagination :current-page="pagination.page" :page-size="pagination.size" :page-sizes="[10, 20, 50, 100]" :total="pagination.total" layout="total, sizes, prev, pager, next, jumper" @current-change="value => goPage(value)" @size-change="value => goPage(1, value)" /></footer>
    </section>

    <Teleport to="body"><div v-if="hover.visible && hover.row" class="gallery-hover-card" :style="{ left: hover.left + 'px', top: hover.top + 'px' }" role="tooltip"><div class="hover-cover"><img v-if="hover.url" :src="hover.url" :alt="hover.row.title" /><span v-else-if="hover.loading" class="cover-spinner" /><span v-else><el-icon><Picture /></el-icon>暂无封面</span></div><div class="hover-copy"><span class="hover-kicker">画廊速览</span><strong>{{ hover.row.title }}</strong><p class="hover-gid">GID {{ hover.row.gid }}</p><div class="hover-stats"><span><el-icon><StarFilled /></el-icon>{{ rating(hover.row.rating) }}</span><span>{{ hover.row.pageCount ?? '—' }} 页</span></div><el-tag :type="getStatusMeta(hover.row.downloadStatus).type" size="small">{{ getStatusMeta(hover.row.downloadStatus).label }}</el-tag><small>{{ hover.row.collectionName ? '合集：' + hover.row.collectionName : '单篇 / 未归类' }}</small></div></div></Teleport>

    <GalleryMediaDrawer v-model="mediaVisible" :gallery="mediaGallery" />
    <el-drawer v-model="detailVisible" title="画廊详情" size="min(560px, 95vw)" destroy-on-close>
      <template v-if="detailRow">
        <div class="detail-title"><span>#{{ detailRow.gid }}</span><h2>{{ detailRow.title }}</h2><div><el-button type="primary" plain :icon="Picture" @click="openMedia(detailRow)">浏览图片</el-button><el-button :icon="TopRight" :loading="openingKomga === detailRow.gid" @click="openKomga(detailRow)">打开 Komga</el-button></div></div>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="合集类型">{{ detailRow.collectionId ? '合集 · ' + detailRow.collectionName : '单篇 / 未归类' }}</el-descriptions-item><el-descriptions-item label="原始标题">{{ detailRow.originalTitle || '—' }}</el-descriptions-item><el-descriptions-item label="评分 / 页数">{{ rating(detailRow.rating) }} / {{ detailRow.pageCount ?? '—' }}</el-descriptions-item><el-descriptions-item label="下载状态">{{ getStatusMeta(detailRow.downloadStatus).label }}</el-descriptions-item><el-descriptions-item label="版本">{{ detailRow.duplicateOfGid ? '重复版本，首选 GID：' + detailRow.duplicateOfGid : detailRow.dedupeKey ? '首选版本' : '未识别版本' }}</el-descriptions-item><el-descriptions-item label="匹配依据">{{ detailRow.dedupeMatchReason || '—' }}</el-descriptions-item><el-descriptions-item label="文件名">{{ detailRow.filename || '—' }}</el-descriptions-item><el-descriptions-item label="大小">{{ detailRow.fileSizeMb != null ? Number(detailRow.fileSizeMb).toFixed(1) + ' MB' : '—' }}</el-descriptions-item><el-descriptions-item label="检索来源">{{ detailRow.searchQuery || '—' }}</el-descriptions-item><el-descriptions-item label="抓取时间">{{ detailRow.crawledAt ? new Date(detailRow.crawledAt).toLocaleString('zh-CN') : '—' }}</el-descriptions-item><el-descriptions-item label="Komga 图书">{{ detailRow.komgaBookId || '尚未关联' }}</el-descriptions-item><el-descriptions-item label="原画廊"><a v-if="safeUrl(detailRow.galleryUrl)" :href="safeUrl(detailRow.galleryUrl)" target="_blank" rel="noopener noreferrer">打开原画廊 ↗</a><span v-else>—</span></el-descriptions-item>
        </el-descriptions>
        <section v-if="detailRow.summary" class="detail-summary"><h3>内容概述</h3><p>{{ detailRow.summary }}</p></section>
        <section class="detail-tags"><h3>标签 <small>点击查看说明</small></h3><el-popover v-for="tag in detailRow.tags || []" :key="tag" trigger="click" :width="320" @show="loadTagDetail(tag)"><template #reference><el-tag class="detail-tag" size="small" type="info">{{ tagStore.translate(tag) }}</el-tag></template><strong>{{ tagDetails[tag]?.name || tagStore.translate(tag) }}</strong><p class="tag-raw">{{ tag }}</p><p class="tag-intro">{{ tagDetails[tag]?.intro || '暂无标签说明' }}</p><el-button link type="primary" @click="searchTag(tag)">检索此标签</el-button></el-popover></section>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, ArrowUp, Collection, Filter, Picture, Refresh, RefreshLeft, Search, SortDown, SortUp, StarFilled, TopRight } from '@element-plus/icons-vue'
import api from '../api'
import { STATUS_OPTIONS, getStatusMeta, normalizeStatusValue } from '../constants/status'
import { useTagStore } from '../stores/tagStore'
import { createCoverCache, fetchGalleryBlob } from '../utils/galleryMedia'
import GalleryMediaDrawer from '../components/GalleryMediaDrawer.vue'

const route = useRoute(), router = useRouter(), tagStore = useTagStore()
const HISTORY_KEY = 'gallery-search-history-v1'
const scalarFields = ['status', 'gid', 'tag', 'collectionId', 'minRating', 'maxRating', 'minPages', 'maxPages', 'minSizeMb', 'maxSizeMb', 'from', 'to', 'searchQuery', 'ratingBucket', 'pageBucket', 'sizeBucket', 'quality']
const numericFields = ['minRating', 'maxRating', 'minPages', 'maxPages', 'minSizeMb', 'maxSizeMb']
const bucketLabels = {
  ratingBucket: { unrated: '未评分', under3: '低于 3 分', '3to4': '3–4 分', '4to4_5': '4–4.5 分', '4_5plus': '4.5 分以上' },
  pageBucket: { unknown: '页数未知', under20: '少于 20 页', '20to49': '20–49 页', '50to99': '50–99 页', '100plus': '100 页以上' },
  sizeBucket: { unknown: '大小未知', under50: '小于 50 MB', '50to100': '50–100 MB', '100to200': '100–200 MB', '200to500': '200–500 MB', '500to1024': '500 MB–1 GB', '1024plus': '1 GB 以上' },
  quality: { missingTags: '缺少标签', missingRating: '缺少评分', missingPages: '缺少页数', missingSummary: '缺少概述', missingSource: '缺少来源' }
}
const sortOptions = [{ value: 'crawledAt', label: '抓取时间' }, { value: 'updatedAt', label: '更新时间' }, { value: 'gid', label: 'GID' }, { value: 'title', label: '标题' }, { value: 'rating', label: '评分' }, { value: 'pageCount', label: '页数' }, { value: 'fileSizeMb', label: '文件大小' }, { value: 'downloadStatus', label: '下载状态' }]
const defaults = () => ({ keyword: '', keywordMode: 'contains', status: '', gid: '', tag: '', tags: [], excludeTags: [], tagMode: 'all', dedupe: 'preferred', collectionScope: 'all', collectionId: '', minRating: '', maxRating: '', minPages: '', maxPages: '', minSizeMb: '', maxSizeMb: '', from: '', to: '', searchQuery: '', komga: 'all', ratingBucket: '', pageBucket: '', sizeBucket: '', quality: '' })
const draft = reactive(defaults()), committed = reactive(defaults())
const pagination = reactive({ page: 1, size: 20, total: 0 }), sort = reactive({ prop: 'crawledAt', order: 'desc' })
const loading = ref(false), firstLoad = ref(true), listError = ref(''), tableData = ref([]), collections = ref([]), advancedVisible = ref(false)
const tagInput = ref(''), excludeInput = ref(''), recentKeywords = ref(readHistory())
const mediaVisible = ref(false), mediaGallery = ref(null), detailVisible = ref(false), detailRow = ref(null), tagDetails = reactive({}), openingKomga = ref(null)
const hover = reactive({ visible: false, row: null, left: 0, top: 0, url: '', loading: false })
const coverCache = createCoverCache(24)
let listController, titleController, tagController, excludeController, hoverController, hoverTimer, hoverCandidate, isDisposed = false
const includedTags = computed(() => [...new Set([draft.tag, ...draft.tags].filter(Boolean))])
const pendingChanges = computed(() => JSON.stringify(draft) !== JSON.stringify(committed))
const advancedCount = computed(() => ['gid', 'collectionId', ...numericFields, 'from', 'to', 'searchQuery', 'ratingBucket', 'pageBucket', 'sizeBucket', 'quality'].filter(key => draft[key] !== '').length + includedTags.value.length + draft.excludeTags.length)
const activeChips = computed(() => {
  const chips = [], f = committed
  if (f.keyword) chips.push({ key: 'keyword', label: '关键词：' + f.keyword })
  if (f.status) chips.push({ key: 'status', label: getStatusMeta(f.status).label })
  if (f.gid) chips.push({ key: 'gid', label: 'GID：' + f.gid })
  if (f.tag || f.tags.length) chips.push({ key: 'tags', label: (f.tagMode === 'any' ? '任一标签：' : '包含标签：') + [...new Set([f.tag, ...f.tags].filter(Boolean))].map(tag => tagStore.translate(tag)).join('、') })
  if (f.excludeTags.length) chips.push({ key: 'excludeTags', label: '排除：' + f.excludeTags.map(tag => tagStore.translate(tag)).join('、') })
  if (f.collectionScope !== 'all') chips.push({ key: 'collectionScope', label: f.collectionScope === 'assigned' ? '已归入合集' : '单篇 / 未归类' })
  if (f.collectionId) chips.push({ key: 'collectionId', label: '合集：' + (collections.value.find(item => String(item.id) === String(f.collectionId))?.name || f.collectionId) })
  if (f.dedupe !== 'preferred') chips.push({ key: 'dedupe', label: f.dedupe === 'all' ? '全部版本' : '仅重复版本' })
  if (f.komga !== 'all') chips.push({ key: 'komga', label: f.komga === 'linked' ? 'Komga 已关联' : 'Komga 未关联' })
  const ranges = [['minRating', 'maxRating', '评分'], ['minPages', 'maxPages', '页数'], ['minSizeMb', 'maxSizeMb', '大小 MB'], ['from', 'to', '抓取时间']]
  ranges.forEach(([min, max, label]) => { if (f[min] !== '' || f[max] !== '') chips.push({ key: min, label: label + '：' + (f[min] || '不限') + ' ～ ' + (f[max] || '不限') }) })
  if (f.searchQuery) chips.push({ key: 'searchQuery', label: '来源：' + f.searchQuery })
  ;['ratingBucket', 'pageBucket', 'sizeBucket', 'quality'].forEach(key => { if (f[key]) chips.push({ key, label: '统计筛选：' + (bucketLabels[key][f[key]] || f[key]) }) })
  return chips
})

function readHistory() { try { const value = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]'); return Array.isArray(value) ? value.filter(item => typeof item === 'string').slice(0, 8) : [] } catch { return [] } }
function saveHistory() { const value = draft.keyword.trim(); if (!value) return; recentKeywords.value = [value, ...recentKeywords.value.filter(word => word !== value)].slice(0, 8); try { localStorage.setItem(HISTORY_KEY, JSON.stringify(recentKeywords.value)) } catch {} }
function clearHistory() { recentKeywords.value = []; try { localStorage.removeItem(HISTORY_KEY) } catch {} }
function parseTags(value) { try { const tags = JSON.parse(typeof value === 'string' ? value : '[]'); return Array.isArray(tags) ? [...new Set(tags.filter(tag => typeof tag === 'string' && tag.trim()).map(tag => tag.trim()))].slice(0, 20) : [] } catch { return [] } }
function str(value) { return typeof value === 'string' ? value : '' }
function positive(value, fallback, max) { const number = Number(value); return Number.isInteger(number) && number > 0 ? Math.min(number, max) : fallback }
function fromRoute(query) {
  const f = defaults()
  scalarFields.forEach(key => { f[key] = str(query[key]) })
  f.keyword = str(query.q) || str(query.keyword)
  f.status = normalizeStatusValue(f.status)
  if (!STATUS_OPTIONS.some(item => item.value === f.status)) f.status = ''
  f.tags = parseTags(query.tags); f.excludeTags = parseTags(query.excludeTags)
  f.keywordMode = ['contains', 'all', 'any', 'exact'].includes(query.keywordMode) ? query.keywordMode : 'contains'
  f.tagMode = query.tagMode === 'any' ? 'any' : 'all'
  f.dedupe = ['preferred', 'all', 'duplicates'].includes(query.dedupe) ? query.dedupe : 'preferred'
  f.collectionScope = ['all', 'assigned', 'unassigned'].includes(query.collectionScope) ? query.collectionScope : 'all'
  f.komga = ['all', 'linked', 'unlinked'].includes(query.komga) ? query.komga : 'all'
  return f
}
function buildRoute(filters, page = pagination.page, size = pagination.size, sortProp = sort.prop, order = sort.order) {
  const query = { page: String(page), size: String(size), sort: sortProp, order }
  scalarFields.forEach(key => { if (filters[key] !== '' && filters[key] != null) query[key] = String(filters[key]).trim() })
  if (filters.keyword.trim()) query.q = filters.keyword.trim()
  if (filters.keywordMode !== 'contains') query.keywordMode = filters.keywordMode
  if (filters.tags.length) query.tags = JSON.stringify(filters.tags)
  if (filters.excludeTags.length) query.excludeTags = JSON.stringify(filters.excludeTags)
  if (filters.tagMode !== 'all') query.tagMode = filters.tagMode
  if (filters.dedupe !== 'preferred') query.dedupe = filters.dedupe
  if (filters.collectionScope !== 'all') query.collectionScope = filters.collectionScope
  if (filters.komga !== 'all') query.komga = filters.komga
  return query
}
function sameQuery(a, b) { const keys = new Set([...Object.keys(a), ...Object.keys(b)]); return [...keys].every(key => String(a[key] ?? '') === String(b[key] ?? '')) }
async function navigate(query, refreshIfSame = false) { hideHover(); if (!sameQuery(route.query, query)) await router.push({ path: '/galleries', query }); else if (refreshIfSame) loadData() }
function validate() {
  for (const [min, max, label] of [['minRating', 'maxRating', '评分'], ['minPages', 'maxPages', '页数'], ['minSizeMb', 'maxSizeMb', '大小']]) {
    if (draft[min] !== '' && draft[max] !== '' && Number(draft[min]) > Number(draft[max])) { ElMessage.warning(label + '下限不能超过上限'); return false }
  }
  if (draft.from && draft.to && draft.from > draft.to) { ElMessage.warning('开始日期不能晚于结束日期'); return false }
  return true
}
function applyFilters() { if (!validate()) return; saveHistory(); navigate(buildRoute(draft, 1), true) }
function resetFilters() { Object.assign(draft, defaults()); tagInput.value = ''; excludeInput.value = ''; navigate(buildRoute(draft, 1), true) }
function useRecent(word) { draft.keyword = word; applyFilters() }
function clearChip(key) {
  Object.assign(draft, JSON.parse(JSON.stringify(committed)))
  if (key === 'tags') { draft.tags = []; draft.tag = '' }
  else if (key === 'excludeTags') draft.excludeTags = []
  else if (key === 'dedupe') draft.dedupe = 'preferred'
  else if (['collectionScope', 'komga'].includes(key)) draft[key] = 'all'
  else if (key === 'minRating') { draft.minRating = ''; draft.maxRating = '' }
  else if (key === 'minPages') { draft.minPages = ''; draft.maxPages = '' }
  else if (key === 'minSizeMb') { draft.minSizeMb = ''; draft.maxSizeMb = '' }
  else if (key === 'from') { draft.from = ''; draft.to = '' }
  else draft[key] = ''
  navigate(buildRoute(draft, 1), true)
}
function goPage(value, size = pagination.size) { navigate(buildRoute(committed, value, size)) }
function setSort(value) { navigate(buildRoute(committed, 1, pagination.size, value)) }
function toggleOrder() { navigate(buildRoute(committed, 1, pagination.size, sort.prop, sort.order === 'desc' ? 'asc' : 'desc')) }
function addTag(value, excluded = false) {
  const tag = String(value || '').trim()
  if (!tag) return
  if (excluded) { draft.excludeTags = [...new Set([...draft.excludeTags, tag])].slice(0, 20); excludeInput.value = '' }
  else { if (tag !== draft.tag) draft.tags = [...new Set([...draft.tags, tag])].slice(0, 20); tagInput.value = '' }
}
function removeTag(tag) { if (draft.tag === tag) draft.tag = ''; draft.tags = draft.tags.filter(item => item !== tag) }
function searchTag(tag) { hideHover(); detailVisible.value = false; Object.assign(draft, JSON.parse(JSON.stringify(committed))); draft.tag = tag; draft.tags = []; navigate(buildRoute(draft, 1), true) }
function searchCollection(row) { Object.assign(draft, JSON.parse(JSON.stringify(committed))); draft.collectionId = String(row.collectionId); draft.collectionScope = 'assigned'; navigate(buildRoute(draft, 1), true) }
async function loadData() {
  hideHover(); listController?.abort()
  const request = new AbortController(); listController = request
  loading.value = true; listError.value = ''
  const params = { ...committed, keyword: committed.keyword.trim(), page: pagination.page, size: pagination.size, sortBy: sort.prop, sortOrder: sort.order }
  params.tags = params.tags.length ? JSON.stringify(params.tags) : undefined; params.excludeTags = params.excludeTags.length ? JSON.stringify(params.excludeTags) : undefined
  Object.keys(params).forEach(key => { if (params[key] === '' || params[key] == null) delete params[key] })
  try {
    const response = await api.get('/dashboard/galleries', { params, signal: request.signal })
    if (listController !== request) return
    tableData.value = response.data.records || []; pagination.total = Number(response.data.total) || 0
  } catch (failure) { if (listController === request && failure.code !== 'ERR_CANCELED') listError.value = tableData.value.length ? '检索失败，当前保留上次成功的结果。' : '检索失败，请调整条件后重试。' }
  finally { if (listController === request) { loading.value = false; firstLoad.value = false } }
}
async function fetchSuggestions(value, callback, type, kind) {
  const text = value?.trim()
  const previous = kind === 'title' ? titleController : kind === 'exclude' ? excludeController : tagController
  previous?.abort()
  if (!text || (type === 'title' && text.length < 2)) return callback([])
  const request = new AbortController()
  if (kind === 'title') titleController = request
  else if (kind === 'exclude') excludeController = request
  else tagController = request
  try {
    const response = await api.get('/dashboard/suggestions', { params: { q: text, type, limit: 12 }, signal: request.signal })
    if (!request.signal.aborted) callback((response.data || []).map(item => type === 'tag' ? { ...item, value: item.value, label: item.label || tagStore.translate(item.value) } : item))
  } catch { if (!request.signal.aborted) callback([]) }
}
const fetchTitleSuggestions = (value, callback) => fetchSuggestions(value, callback, 'title', 'title')
const fetchTagSuggestions = (value, callback) => fetchSuggestions(value, callback, 'tag', 'tag')
const fetchExcludeSuggestions = (value, callback) => fetchSuggestions(value, callback, 'tag', 'exclude')
function rating(value) { return value != null && Number(value) > 0 ? Number(value).toFixed(2) : '未评分' }
function formatDate(value) { return value ? new Date(value).toLocaleDateString('zh-CN') : '—' }
function formatTime(value) { return value ? new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '' }
function safeUrl(value) { try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) ? url.href : '' } catch { return '' } }
function openMedia(row) { hideHover(); mediaGallery.value = row; mediaVisible.value = true }
function showDetail(row) { hideHover(); detailRow.value = row; detailVisible.value = true }
async function loadTagDetail(tag) { if (!tagDetails[tag]) tagDetails[tag] = await tagStore.fetchDetail(tag) }
async function openKomga(row) {
  hideHover()
  if (openingKomga.value) return
  const target = window.open('about:blank', '_blank')
  if (!target) return ElMessage.warning('浏览器阻止了新窗口，请允许本站弹出窗口后重试。')
  target.opener = null; openingKomga.value = row.gid
  try {
    const response = await api.get('/dashboard/komga-link/' + row.gid)
    const url = safeUrl(response.data?.url)
    if (!response.data?.available || !url) { target.close(); ElMessage.info(response.data?.message || '此画廊尚未关联 Komga 图书'); return }
    target.location.replace(url)
  } catch { target.close() }
  finally { if (!isDisposed) openingKomga.value = null }
}
function positionHover(event) {
  const width = Math.min(366, window.innerWidth - 24), height = 236
  hover.left = Math.max(12, Math.min(event.clientX + 20, window.innerWidth - width - 12))
  hover.top = Math.max(12, Math.min(event.clientY + 16, window.innerHeight - height - 12))
}
function scheduleHover(row, event) {
  if (event.pointerType === 'touch' || mediaVisible.value || detailVisible.value) return
  hideHover(); hoverCandidate = row.gid; positionHover(event)
  hoverTimer = window.setTimeout(async () => {
    if (hoverCandidate !== row.gid) return
    hover.row = row; hover.visible = true; hover.url = coverCache.get(row.gid); hover.loading = !hover.url
    if (hover.url) return
    const request = new AbortController(); hoverController = request
    try {
      const blob = await fetchGalleryBlob('/api/dashboard/gallery-media/' + row.gid + '/cover', request.signal)
      if (hoverController === request && hoverCandidate === row.gid && !request.signal.aborted) hover.url = coverCache.set(row.gid, blob)
    } catch {}
    finally { if (hoverController === request) hover.loading = false }
  }, 1000)
}
function hideHover() { clearTimeout(hoverTimer); hoverCandidate = null; hoverController?.abort(); hoverController = null; hover.visible = false; hover.row = null; hover.url = ''; hover.loading = false }
function closeOnScroll(event) { if (!event.target?.closest?.('.gallery-hover-card')) hideHover() }
watch(() => route.fullPath, () => {
  if (route.path !== '/galleries') return
  const filters = fromRoute(route.query)
  Object.assign(committed, filters); Object.assign(draft, JSON.parse(JSON.stringify(filters)))
  pagination.page = positive(route.query.page, 1, Number.MAX_SAFE_INTEGER); pagination.size = positive(route.query.size, 20, 100)
  sort.prop = sortOptions.some(item => item.value === route.query.sort) ? route.query.sort : 'crawledAt'; sort.order = route.query.order === 'asc' ? 'asc' : 'desc'
  if (advancedCount.value) advancedVisible.value = true
  loadData()
}, { immediate: true })
onMounted(() => {
  tagStore.loadTranslations()
  api.get('/collections').then(response => { if (!isDisposed) collections.value = response.data || [] }).catch(() => {})
  window.addEventListener('scroll', closeOnScroll, true); window.addEventListener('resize', hideHover); window.addEventListener('blur', hideHover)
})
onUnmounted(() => {
  isDisposed = true; hideHover(); coverCache.clear(); listController?.abort(); titleController?.abort(); tagController?.abort(); excludeController?.abort()
  window.removeEventListener('scroll', closeOnScroll, true); window.removeEventListener('resize', hideHover); window.removeEventListener('blur', hideHover)
})
</script>

<style scoped>
.galleries-page { --accent: #4169e1; color: #25354b; max-width: 1920px; margin: 0 auto; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif; }
button, input { font: inherit; } button { cursor: pointer; } button:focus-visible { outline: 3px solid #b7ccff; outline-offset: 3px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin: 5px 0 22px; }.eyebrow { margin: 0 0 9px; color: var(--accent); font-size: calc(10px * var(--app-font-scale, 1)); letter-spacing: .16em; font-weight: 700; }.page-heading h1 { margin: 0; font-size: calc(27px * var(--app-font-scale, 1)); display: flex; align-items: center; gap: 18px; letter-spacing: -.03em; }.page-heading h1 span { color: #9aa6b8; font-size: calc(13px * var(--app-font-scale, 1)); font-weight: 400; padding-left: 18px; border-left: 1px solid #d6deeb; letter-spacing: 0; }.page-heading p:last-child { margin: 9px 0 0; font-size: calc(12px * var(--app-font-scale, 1)); color: #8a98ae; line-height: 1.7; }
.search-panel, .results-panel { background: #fff; border: 1px solid #e0e7f1; border-radius: 12px; overflow: hidden; }.search-panel { padding: 20px; margin-bottom: 16px; }.primary-search { display: flex; gap: 10px; align-items: center; }.keyword-control { flex: 1; display: flex; align-items: center; border: 1px solid #dce4f1; background: #fafcfe; border-radius: 8px; min-width: 160px; height: 38px; padding-left: 12px; color: #93a4bc; }.keyword-control:focus-within { border-color: #89a7ed; box-shadow: 0 0 0 2px #edf2fd; }.keyword-control > .el-icon { margin-right: 5px; }.keyword-control :deep(.el-autocomplete) { width: 100%; }.keyword-control :deep(.el-input__wrapper) { box-shadow: none; background: none; }.match-control { width: 146px; }.quick-filters { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; margin-top: 18px; }.quick-filters > label { display: flex; align-items: center; gap: 8px; }.quick-filters label > span { color: #7e8ba0; font-size: calc(11px * var(--app-font-scale, 1)); white-space: nowrap; }.quick-filters .el-select { width: 142px; }.quick-filters label:last-of-type .el-select { width: 110px; }.advanced-toggle { margin-left: auto; display: flex; align-items: center; gap: 6px; font-size: calc(11px * var(--app-font-scale, 1)); color: #7284a4; background: none; border: 0; padding: 6px 0; }.advanced-toggle.active { color: var(--accent); }.advanced-toggle > span { min-width: 17px; height: 17px; border-radius: 5px; display: grid; place-items: center; background: #edf3ff; font-size: calc(10px * var(--app-font-scale, 1)); }.advanced-search { border-top: 1px solid #edf0f6; margin-top: 19px; padding-top: 19px; }.advanced-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 19px 20px; }.field { min-width: 0; display: flex; flex-direction: column; gap: 8px; }.field > span { color: #7d8ca3; font-size: calc(11px * var(--app-font-scale, 1)); }.field > span small { color: #b0b8c6; font-size: calc(10px * var(--app-font-scale, 1)); margin-left: 6px; }.field :deep(.el-select), .field :deep(.el-autocomplete) { width: 100%; }.wide-field { grid-column: span 2; }.tag-input-line { display: flex; gap: 8px; }.tag-input-line > .el-select { flex: 0 0 108px; }.tag-input-line > .el-autocomplete { flex: 1; min-width: 0; }.tag-tokens { display: flex; flex-wrap: wrap; gap: 5px; }.tag-tokens .el-tag { font-size: calc(10px * var(--app-font-scale, 1)); }.range-input { display: flex; align-items: center; border: 1px solid #dce3ef; border-radius: 6px; min-height: 31px; background: #fff; }.range-input input { width: 50%; min-width: 0; padding: 6px 8px; border: 0; outline: 0; color: #536985; font-size: calc(12px * var(--app-font-scale, 1)); background: transparent; box-sizing: border-box; }.range-input:focus-within { box-shadow: 0 0 0 1px #8aa8e7; }.range-input i { color: #b7c1cf; font-size: calc(10px * var(--app-font-scale, 1)); font-style: normal; }.date-input { min-width: 0; }.date-input input { font-size: calc(10px * var(--app-font-scale, 1)); padding-left: 5px; padding-right: 0; }.search-history { display: flex; gap: 7px; align-items: center; flex-wrap: wrap; border-top: 1px solid #f0f3f8; padding-top: 12px; margin-top: 17px; font-size: calc(10px * var(--app-font-scale, 1)); color: #a6b1c1; }.search-history button { background: #f5f7fb; border: 0; color: #8395b0; border-radius: 4px; padding: 4px 8px; font-size: calc(10px * var(--app-font-scale, 1)); max-width: 180px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }.search-history .clear-history { background: none; margin-left: auto; }
.active-filters { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; margin: 0 1px 16px; font-size: calc(10px * var(--app-font-scale, 1)); color: #97a5ba; }.active-filters > span { margin-right: 3px; }.active-filters :deep(.el-tag) { font-size: calc(10px * var(--app-font-scale, 1)); max-width: min(580px, 90vw); height: auto; min-height: 24px; white-space: normal; padding-top: 4px; padding-bottom: 4px; line-height: 1.5; }.active-filters > button { border: 0; background: none; color: #7a91b8; font-size: calc(10px * var(--app-font-scale, 1)); }.list-error { margin-bottom: 14px; }.result-heading { padding: 18px 20px; display: flex; justify-content: space-between; align-items: center; gap: 16px; border-bottom: 1px solid #edf1f7; }.result-heading h2 { margin: 0; font-size: calc(14px * var(--app-font-scale, 1)); font-weight: 650; }.result-heading h2 > span { margin-left: 8px; padding: 3px 7px; border-radius: 5px; background: #eff4ff; color: #547bd0; font-size: calc(11px * var(--app-font-scale, 1)); }.result-heading small { display: block; color: #a8b2c1; font-size: calc(10px * var(--app-font-scale, 1)); margin-top: 8px; }.sort-controls { display: flex; align-items: center; gap: 8px; color: #93a2b9; font-size: calc(11px * var(--app-font-scale, 1)); }.sort-controls > .el-select { width: 125px; }.sort-controls > .el-button { padding-left: 10px; padding-right: 10px; font-size: calc(11px * var(--app-font-scale, 1)); }.table-wrap { width: 100%; overflow-x: auto; }.table-skeleton { padding: 25px; }.gallery-table { width: 100%; border-collapse: collapse; font-size: calc(12px * var(--app-font-scale, 1)); table-layout: fixed; min-width: 1150px; }.gallery-table th { font-weight: 500; text-align: left; color: #99a6b9; font-size: calc(10px * var(--app-font-scale, 1)); padding: 13px 14px; background: #fafbfd; border-bottom: 1px solid #ecf0f6; }.gallery-table th:nth-child(1) { width: 74px; }.gallery-table th:nth-child(2) { width: auto; }.gallery-table th:nth-child(3) { width: 138px; }.gallery-table th:nth-child(4) { width: 112px; }.gallery-table th:nth-child(5) { width: 85px; }.gallery-table th:nth-child(6) { width: 83px; }.gallery-table th:nth-child(7) { width: 96px; }.gallery-table th:nth-child(8) { width: 86px; }.gallery-table td { padding: 16px 14px; border-bottom: 1px solid #eff3f8; vertical-align: middle; }.gallery-row:hover { background: #f7faff; }.gallery-row:last-child td { border-bottom: 0; }.gid-cell { color: #8395ad; font: calc(11px * var(--app-font-scale, 1)) ui-monospace, SFMono-Regular, Consolas, monospace; }.gallery-title { border: 0; padding: 0; background: none; font: inherit; font-weight: 550; line-height: 1.7; color: #3c506c; text-align: left; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }.gallery-title:hover { color: var(--accent); }.gallery-title > .el-icon { color: #9cb2d6; font-size: calc(11px * var(--app-font-scale, 1)); margin-left: 6px; }.row-tags { display: flex; align-items: center; gap: 5px; margin-top: 8px; min-width: 0; overflow: hidden; }.row-tags > button { max-width: 125px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; padding: 3px 6px; border-radius: 3px; border: 0; background: #f0f3f8; color: #90a0b7; font-size: calc(9px * var(--app-font-scale, 1)); }.row-tags > button:hover { background: #e5edff; color: #5c7ecc; }.row-tags > span { color: #aab5c5; font-size: calc(9px * var(--app-font-scale, 1)); white-space: nowrap; }.collection-link { border: 0; padding: 0; color: #6581b5; background: none; text-align: left; display: flex; align-items: center; flex-wrap: wrap; gap: 4px; font-size: calc(10px * var(--app-font-scale, 1)); width: 100%; }.collection-link small { flex-basis: 100%; color: #9caac0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-top: 3px; }.standalone-label { font-size: calc(10px * var(--app-font-scale, 1)); color: #a2aec1; }.version-label { display: block; color: #a5b1c2; font-size: calc(9px * var(--app-font-scale, 1)); margin-top: 7px; }.version-label.duplicate { color: #c8a06b; }.gallery-table :deep(.el-tag) { font-size: calc(10px * var(--app-font-scale, 1)); height: 22px; }.rating { display: flex; align-items: center; gap: 4px; color: #b99453; font-size: calc(11px * var(--app-font-scale, 1)); font-variant-numeric: tabular-nums; }.rating .el-icon { color: #dfb86f; font-size: calc(12px * var(--app-font-scale, 1)); }.muted-meta { display: block; font-size: calc(10px * var(--app-font-scale, 1)); color: #a5b2c4; margin-top: 7px; }.size-cell { color: #8698b2; font-size: calc(10px * var(--app-font-scale, 1)); font-variant-numeric: tabular-nums; }.date-cell { color: #899bb4; font-size: calc(10px * var(--app-font-scale, 1)); }.row-actions { display: flex; flex-direction: column; align-items: flex-start; gap: 9px; }.row-actions > button { padding: 0; border: 0; background: none; font-size: calc(10px * var(--app-font-scale, 1)); }.detail-action { color: #8c9fba; }.komga-action { display: flex; gap: 3px; align-items: center; color: #6687c8; }.komga-action:disabled { opacity: .45; cursor: wait; }.results-footer { border-top: 1px solid #edf1f7; padding: 18px 20px; display: flex; justify-content: space-between; align-items: center; gap: 16px; overflow-x: auto; }.results-footer > span { color: #a6b2c4; font-size: calc(10px * var(--app-font-scale, 1)); flex-shrink: 0; }.results-footer :deep(.el-pagination) { font-size: calc(11px * var(--app-font-scale, 1)); }
.detail-title { margin: 0 0 23px; }.detail-title > span { color: #9aaac1; font-size: calc(11px * var(--app-font-scale, 1)); }.detail-title h2 { font-size: calc(20px * var(--app-font-scale, 1)); color: #3e516c; line-height: 1.5; margin: 9px 0 18px; }.detail-summary, .detail-tags { margin-top: 24px; }.detail-summary h3, .detail-tags h3 { font-size: calc(13px * var(--app-font-scale, 1)); color: #6b7c94; }.detail-tags h3 small { color: #b0b9c6; font-weight: 400; font-size: calc(10px * var(--app-font-scale, 1)); margin-left: 6px; }.detail-summary p { color: #8a9bb2; font-size: calc(12px * var(--app-font-scale, 1)); line-height: 1.9; }.detail-tag { margin: 0 6px 6px 0; cursor: pointer; }.tag-raw { color: #a7b2c1; font-size: calc(10px * var(--app-font-scale, 1)); }.tag-intro { white-space: pre-wrap; font-size: calc(12px * var(--app-font-scale, 1)); line-height: 1.8; color: #8496ae; }
@media (max-width: 1350px) { .quick-filters { gap: 12px; }.advanced-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }.wide-field { grid-column: span 3; }.results-footer > span { display: none; }.results-footer { justify-content: flex-end; } }
@media (max-width: 1000px) { .page-heading h1 span { display: none; }.primary-search { flex-wrap: wrap; }.keyword-control { flex-basis: calc(100% - 170px); }.match-control { width: 145px; }.primary-search > .el-button { flex: 1; }.quick-filters > label { flex: 1 1 42%; justify-content: space-between; }.quick-filters .el-select { width: min(180px, 70%); }.advanced-toggle { margin-left: 0; }.advanced-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.wide-field { grid-column: span 2; }.result-heading { flex-wrap: wrap; }.sort-controls { width: 100%; justify-content: flex-end; }.page-heading { align-items: flex-start; }.page-heading h1 { font-size: calc(23px * var(--app-font-scale, 1)); } }
@media (max-width: 620px) { .search-panel { padding: 14px; }.page-heading > .el-button { padding: 8px; font-size: calc(11px * var(--app-font-scale, 1)); }.advanced-grid { display: flex; flex-direction: column; }.keyword-control { flex-basis: 100%; }.match-control { flex: 1; width: auto; }.primary-search > .el-button { flex: 0 0 auto; }.primary-search { gap: 7px; }.primary-search :deep(.el-button) { margin-left: 0; padding: 8px 10px; font-size: calc(11px * var(--app-font-scale, 1)); }.results-footer { justify-content: flex-start; padding: 15px 12px; }.tag-input-line > .el-select { flex-basis: 105px; } }
</style>

<style>
.gallery-hover-card { position: fixed; z-index: 4000; width: min(366px, calc(100vw - 24px)); min-height: 208px; box-sizing: border-box; display: grid; grid-template-columns: 112px minmax(0, 1fr); gap: 15px; background: #fff; border: 1px solid #dce5f3; border-radius: 12px; padding: 14px; box-shadow: 0 15px 45px #1d32572e; pointer-events: none; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif; }
.hover-cover { min-height: 172px; border-radius: 7px; background: #eef2f8; overflow: hidden; display: grid; place-items: center; }.hover-cover img { width: 100%; height: 176px; object-fit: contain; }.hover-cover > span:not(.cover-spinner) { color: #a8b5c8; font-size: calc(10px * var(--app-font-scale, 1)); display: flex; align-items: center; gap: 7px; flex-direction: column; }.hover-cover .el-icon { font-size: calc(25px * var(--app-font-scale, 1)); }.cover-spinner { height: 20px; width: 20px; border: 2px solid #d2deef; border-top-color: #7396dd; border-radius: 50%; animation: gallery-cover-spin 1s linear infinite; }.hover-copy { min-width: 0; }.hover-kicker { color: #a8b7cb; font-size: calc(9px * var(--app-font-scale, 1)); letter-spacing: .05em; }.hover-copy > strong { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; color: #455b7b; font-size: calc(12px * var(--app-font-scale, 1)); line-height: 1.7; font-weight: 600; margin-top: 6px; }.hover-gid { color: #a4b3c8; font: calc(10px * var(--app-font-scale, 1)) ui-monospace, monospace; margin: 7px 0 11px; }.hover-stats { display: flex; gap: 15px; font-size: calc(11px * var(--app-font-scale, 1)); color: #95a6bf; margin-bottom: 12px; }.hover-stats > span { display: flex; align-items: center; gap: 4px; }.hover-stats .el-icon { color: #d9b25d; }.hover-copy > small { display: block; color: #a2b0c5; font-size: calc(10px * var(--app-font-scale, 1)); margin-top: 10px; line-height: 1.6; overflow-wrap: anywhere; }@keyframes gallery-cover-spin { to { transform: rotate(360deg); } }
</style>
