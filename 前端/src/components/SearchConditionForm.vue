<template><div class="search-condition-form">
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="关键词">
              <el-input
                v-model="searchForm.keyword"
                placeholder="可输入普通关键词，支持与标签组合搜索"
                clearable
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="搜索标签" required>
              <el-select-v2
                v-model="searchTags"
                :options="tagOptions"
                multiple
                filterable
                remote
                reserve-keyword
                :remote-method="searchTagMethod"
                placeholder="输入中/英文关键词搜索标签，支持多选"
                style="width: 100%"
                popper-class="tag-suggest-popper"
                :item-height="36"
                :height="350"
                @visible-change="handleTagDropdownVisible"
              >
                <template #default="{ item }">
                  <div class="tag-suggest-row">
                    <span>
                      <span class="tag-ns" :style="{ color: item.nsColor }">{{ item.ns }}：</span>
                      <span v-html="highlightMatch(item.translated, tagSearchQuery)"></span>
                    </span>
                    <span class="tag-original">- {{ item.original }}</span>
                  </div>
                </template>
              </el-select-v2>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="24">
            <el-form-item label="画廊类型">
              <el-checkbox-group v-model="selectedGalleryCats">
                <el-checkbox
                  v-for="cat in galleryCategories"
                  :key="cat.code"
                  :value="cat.code"
                >
                  {{ cat.label }}
                </el-checkbox>
              </el-checkbox-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="语言">
              <el-select v-model="searchForm.language" placeholder="不限" clearable style="width: 100%">
                <el-option label="中文" value="chinese" />
                <el-option label="日文" value="japanese" />
                <el-option label="英文" value="english" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="最低星级">
              <el-rate v-model="searchForm.minimumRating" :max="5" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="最少页数">
              <el-input-number v-model="searchForm.pageAtLeast" :min="0" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="最多页数">
              <el-input-number v-model="searchForm.pageAtMost" :min="0" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="搜索已删除画廊">
              <el-switch v-model="searchForm.searchExpungedGalleries" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="仅显示有种子">
              <el-switch v-model="searchForm.showOnlyWithTorrents" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="禁用语言过滤">
              <el-switch v-model="searchForm.disableLanguageFilter" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="禁用上传者过滤">
              <el-switch v-model="searchForm.disableUploaderFilter" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="禁用标签过滤">
              <el-switch v-model="searchForm.disableTagsFilter" />
            </el-form-item>
          </el-col>
        </el-row>
</div></template>
<script setup>
import { computed, ref, reactive } from 'vue'
import { useTagStore } from '../stores/tagStore'
const props = defineProps({ modelValue: { type: Object, required: true }, tags: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue', 'update:tags'])
const searchForm = computed(() => props.modelValue)
const searchTags = computed({ get: () => props.tags, set: value => emit('update:tags', value) })
const tagStore = useTagStore()
tagStore.loadTranslations()
const galleryCategories = [
  { code: 1, label: 'Misc / 其他' },
  { code: 2, label: 'Doujinshi / 同人志' },
  { code: 4, label: 'Manga / 漫画' },
  { code: 8, label: 'Artist CG / 艺术CG' },
  { code: 16, label: 'Game CG / 游戏CG' },
  { code: 32, label: 'Image Set / 图像集' },
  { code: 64, label: 'Cosplay' },
  { code: 128, label: 'Asian Porn / 亚洲成人视频' },
  { code: 256, label: 'Non-H / 无码作品' },
  { code: 512, label: 'Western / 西方作品' }
]

const selectedGalleryCats = computed({
  get: () => galleryCategories.filter(cat => !((searchForm.value.filterCats || 0) & cat.code)).map(cat => cat.code),
  set: codes => { searchForm.value.filterCats = galleryCategories.filter(cat => !codes.includes(cat.code)).reduce((sum, cat) => sum + cat.code, 0) }
})
// 转义建议文本中的 HTML 特殊字符，避免高亮渲染把标签内容当成页面代码。
const escapeHtml = (str) => str.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))

// 在已转义的建议文字中标出匹配词，保留原始内容的安全展示。
const highlightMatch = (text, query) => {
  if (!query) return escapeHtml(text)
  const lt = text.toLowerCase()
  const lq = query.toLowerCase()
  const idx = lt.indexOf(lq)
  if (idx === -1) return escapeHtml(text)
  return escapeHtml(text.substring(0, idx)) + '<b>' + escapeHtml(text.substring(idx, idx + query.length)) + '</b>' + escapeHtml(text.substring(idx + query.length))
}

// 将后端标签建议整理成下拉组件需要的展示格式。
const buildSuggestionItem = (key, val, query) => {
  const colonIdx = key.indexOf(':')
  const ns = colonIdx > -1 ? key.substring(0, colonIdx) : 'other'
  const original = colonIdx > -1 ? key.substring(colonIdx + 1) : key
  const nsInfo = tagStore.getNsInfo(ns)
  return { value: key, label: val, ns: nsInfo.label, nsColor: nsInfo.color, translated: val, original, query }
}

const tagOptions = ref([])
const tagSearchQuery = ref('')
const tagLabelCache = reactive({})

// 保留当前已选标签，避免搜索新关键词后下拉列表丢掉选中项。
const getSelectedOptions = () => {
  return searchTags.value.map(v => {
    const label = tagLabelCache[v] || tagStore.translate(v) || v
    return buildSuggestionItem(v, label, '')
  })
}

// 向后端查询标签建议，同时合并已选标签供多选框显示。
const searchTagMethod = (query) => {
  tagSearchQuery.value = query
  if (!query) {
    tagOptions.value = getSelectedOptions()
    return
  }
  const lw = query.toLowerCase()
  const map = tagStore.translations
  const results = []
  for (const [key, val] of Object.entries(map)) {
    if (key.toLowerCase().includes(lw) || val.toLowerCase().includes(lw)) {
      tagLabelCache[key] = val
      results.push(buildSuggestionItem(key, val, query))
    }
  }
  const selectedExtras = getSelectedOptions().filter(o => !results.some(r => r.value === o.value))
  tagOptions.value = [...selectedExtras, ...results]
}

const handleTagDropdownVisible = (visible) => {
  if (visible && !tagSearchQuery.value) {
    tagOptions.value = getSelectedOptions()
  }
}

</script>
<style scoped>
.tag-suggest-row { display:flex; justify-content:space-between; gap:12px; }
.tag-original { color:var(--el-text-color-secondary); }
</style>
