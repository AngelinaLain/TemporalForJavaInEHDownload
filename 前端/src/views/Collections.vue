<template>
  <div class="collections-page">
    <section class="hero">
      <div>
        <p class="eyebrow">SERIES CURATION</p>
        <h2>画廊合集</h2>
        <p>用 EH 标题结构、封面感知指纹和作品标签发现同系列，再由人工确认归类。</p>
      </div>
      <div class="hero-actions">
        <el-button size="large" :loading="syncStatus.running" @click="startKomgaSync">
          {{ syncStatus.running ? '正在应用到 Komga' : '应用到 Komga 系列' }}
        </el-button>
        <el-button type="primary" size="large" @click="openEditor()">
          <el-icon><Plus /></el-icon> 新建合集
        </el-button>
      </div>
    </section>

    <el-card v-if="syncStatus.running || syncStatus.finishedAt || syncStatus.lastError" class="sync-card" shadow="never">
      <div class="sync-line">
        <strong>Komga 系列同步</strong>
        <span>{{ syncStatus.processed || 0 }} / {{ syncStatus.total || 0 }}，成功 {{ syncStatus.succeeded || 0 }}，失败 {{ syncStatus.failed || 0 }}<template v-if="syncStatus.cleanupPending">，旧文件待清理 {{ syncStatus.cleanupPending }}</template></span>
      </div>
      <el-progress v-if="syncStatus.running" :percentage="syncPercent" :stroke-width="8" />
      <el-alert v-if="syncStatus.lastError" :title="syncStatus.lastError" type="warning" show-icon :closable="false" />
      <p v-if="syncStatus.cleanupPending" class="hint">再次点击“应用到 Komga 系列”可重试旧文件清理，无需重新下载。</p>
    </el-card>

    <div class="workspace">
      <el-card class="collection-list" shadow="never">
        <template #header>
          <div class="panel-title"><span>合集</span><el-tag round>{{ collections.length }}</el-tag></div>
        </template>
        <el-empty v-if="!collections.length && !loadingCollections" description="先创建一个合集" />
        <button v-for="item in collections" :key="item.id" type="button"
                class="collection-row" :class="{ active: selectedId === item.id }"
                @click="selectCollection(item.id)">
          <span class="collection-mark">{{ item.name.slice(0, 1).toUpperCase() }}</span>
          <span class="collection-copy">
            <strong>{{ item.name }}</strong>
            <small>{{ item.itemCount }} 个画廊</small>
          </span>
          <el-icon><ArrowRight /></el-icon>
        </button>
      </el-card>

      <el-card class="detail-panel" shadow="never" v-loading="loadingDetail">
        <el-empty v-if="!selected" description="从左侧选择合集开始整理" />
        <template v-else>
          <header class="detail-header">
            <div>
              <h3>{{ selected.name }}</h3>
              <p>{{ selected.description || '暂无说明' }}</p>
            </div>
            <div class="header-actions">
              <el-button @click="openEditor(selected)">编辑</el-button>
              <el-popconfirm title="删除合集？画廊本身不会被删除。" @confirm="deleteCollection">
                <template #reference><el-button type="danger" plain>删除</el-button></template>
              </el-popconfirm>
            </div>
          </header>

          <el-tabs v-model="activeTab" @tab-change="handleTabChange">
            <el-tab-pane :label="`已归类 ${members.length}`" name="members">
              <GalleryRows :rows="members" action-label="移出" action-type="danger"
                           empty-text="这个合集还没有画廊" @action="removeGallery" />
            </el-tab-pane>
            <el-tab-pane name="suggestions">
              <template #label>
                <span>智能建议 <el-badge v-if="suggestions.length" :value="suggestions.length" /></span>
              </template>
              <div class="tab-toolbar">
                <span class="hint">先加入一个代表性画廊，系统才有比较基准。</span>
                <el-button :loading="loadingSuggestions" @click="loadSuggestions">重新分析</el-button>
              </div>
              <GalleryRows :rows="suggestions" action-label="加入" action-type="primary"
                           empty-text="暂无高可信建议；可切换到人工搜索" show-score @action="addSuggested" />
            </el-tab-pane>
            <el-tab-pane label="人工搜索" name="search">
              <div class="search-bar">
                <el-input v-model="keyword" clearable placeholder="输入标题、原始标题或文件名"
                          @keyup.enter="searchGalleries">
                  <template #prefix><el-icon><Search /></el-icon></template>
                </el-input>
                <el-select v-model="searchScope" style="width: 150px" @change="searchGalleries">
                  <el-option label="仅未归类" value="unassigned" />
                  <el-option label="全部画廊" value="all" />
                </el-select>
                <el-button type="primary" :loading="searching" @click="searchGalleries">搜索</el-button>
              </div>
              <GalleryRows :rows="searchResults" action-label="归入此合集" action-type="primary"
                           empty-text="输入关键词搜索画廊" show-membership @action="addManual" />
            </el-tab-pane>
          </el-tabs>
        </template>
      </el-card>
    </div>

    <el-dialog v-model="editorVisible" :title="editingId ? '编辑合集' : '新建合集'" width="480px">
      <el-form label-position="top" @submit.prevent="saveCollection">
        <el-form-item label="合集名称" required>
          <el-input v-model="form.name" maxlength="200" show-word-limit autofocus />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCollection">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 管理合集与成员，提供候选建议、人工归组和向 Komga 同步系列的操作。
import { computed, defineComponent, h, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ArrowRight, Plus, Search } from '@element-plus/icons-vue'
import { ElButton, ElEmpty, ElMessage, ElMessageBox, ElTag, ElTooltip } from 'element-plus'
import api from '../api'

const GalleryRows = defineComponent({
  props: {
    rows: { type: Array, default: () => [] },
    actionLabel: String,
    actionType: String,
    emptyText: String,
    showScore: Boolean,
    showMembership: Boolean
  },
  emits: ['action'],
  setup(props, { emit }) {
    return () => props.rows.length ? h('div', { class: 'gallery-rows' }, props.rows.map(row =>
      h('article', { class: 'gallery-row', key: row.gid }, [
        h('div', { class: 'gallery-id' }, `#${row.gid}`),
        h('div', { class: 'gallery-copy' }, [
          h('strong', { title: row.title }, row.title || '未命名画廊'),
          row.originalTitle && row.originalTitle !== row.title ? h('small', { title: row.originalTitle }, row.originalTitle) : null,
          props.showScore && row.reason ? h('div', { class: 'evidence' }, [
            h(ElTooltip, { content: row.reason, placement: 'top' }, () =>
              h(ElTag, { type: row.score >= 75 ? 'success' : 'warning', effect: 'plain' }, () => `相似度 ${row.score}%`)),
            h('span', {}, `标题 ${row.titleSimilarity}%`),
            row.coverSimilarity == null ? h('span', {}, '封面待采集') : h('span', {}, `封面 ${row.coverSimilarity}%`)
          ]) : null,
          props.showMembership && row.collectionName ? h(ElTag, { type: 'info', size: 'small' }, () => `当前：${row.collectionName}`) : null
        ]),
        h('div', { class: 'gallery-meta' }, [
          h('span', {}, row.pageCount ? `${row.pageCount} 页` : '页数未知'),
          h(ElButton, { type: props.actionType, plain: props.actionType === 'danger', onClick: () => emit('action', row) }, () => props.actionLabel)
        ])
      ])
    )) : h(ElEmpty, { description: props.emptyText, imageSize: 90 })
  }
})

const collections = ref([])
const selectedId = ref(null)
const members = ref([])
const suggestions = ref([])
const searchResults = ref([])
const activeTab = ref('members')
const keyword = ref('')
const searchScope = ref('unassigned')
const loadingCollections = ref(false)
const loadingDetail = ref(false)
const loadingSuggestions = ref(false)
const searching = ref(false)
const editorVisible = ref(false)
const editingId = ref(null)
const saving = ref(false)
const form = reactive({ name: '', description: '' })
const syncStatus = reactive({ running: false, total: 0, processed: 0, succeeded: 0, failed: 0, cleanupPending: 0, currentGid: null, lastError: null, finishedAt: null })
const syncPercent = computed(() => syncStatus.total ? Math.min(100, Math.round(syncStatus.processed * 100 / syncStatus.total)) : 0)
const selected = computed(() => collections.value.find(item => item.id === selectedId.value))
let syncTimer

// 读取系列同步任务进度，供页面状态卡和运行中轮询共用。
async function loadSyncStatus() {
  const res = await api.get('/collections/komga-series-sync/status')
  Object.assign(syncStatus, res.data || {})
}

// 启动已归组合集向 Komga 系列的同步，并显示后台进度。
async function startKomgaSync() {
  try {
    await ElMessageBox.confirm(
      '将读取并重写需要迁移的 CBZ，把它们移动到合集子目录，然后触发 Komga 扫描。任务可重试，但运行期间请勿手工移动这些文件。',
      '应用合集到 Komga 系列',
      { type: 'warning', confirmButtonText: '开始同步', cancelButtonText: '取消' }
    )
  } catch { return }
  await api.post('/collections/komga-series-sync')
  ElMessage.success('合集正在后台应用到 Komga 系列')
  await loadSyncStatus()
}

// 重新载入合集，并尽量保留用户当前选择。
async function loadCollections(preferredId) {
  loadingCollections.value = true
  try {
    const res = await api.get('/collections')
    collections.value = res.data || []
    const nextId = preferredId ?? selectedId.value
    if (nextId && collections.value.some(item => item.id === nextId)) selectedId.value = nextId
    else selectedId.value = collections.value[0]?.id ?? null
  } finally { loadingCollections.value = false }
}

// 切换当前合集后读取成员，清除上一合集的候选状态。
async function selectCollection(id) {
  selectedId.value = id
  activeTab.value = 'members'
  suggestions.value = []
  searchResults.value = []
  await loadMembers()
}

// 读取选中合集成员及相关信息，不在合集未选中时请求。
async function loadMembers() {
  if (!selectedId.value) return
  loadingDetail.value = true
  try {
    const res = await api.get(`/collections/${selectedId.value}/items`)
    members.value = res.data || []
  } finally { loadingDetail.value = false }
}

function openEditor(item) {
  editingId.value = item?.id ?? null
  form.name = item?.name ?? ''
  form.description = item?.description ?? ''
  editorVisible.value = true
}

// 校验并保存合集信息，完成后重新选中保存的合集。
async function saveCollection() {
  if (!form.name.trim()) return ElMessage.warning('请输入合集名称')
  saving.value = true
  try {
    const payload = { name: form.name.trim(), description: form.description.trim() }
    const res = editingId.value
      ? await api.put(`/collections/${editingId.value}`, payload)
      : await api.post('/collections', payload)
    editorVisible.value = false
    await loadCollections(res.data.id)
    await loadMembers()
    ElMessage.success('合集已保存')
  } finally { saving.value = false }
}

// 经用户确认后删除当前合集，再刷新合集和成员列表。
async function deleteCollection() {
  await api.delete(`/collections/${selectedId.value}`)
  selectedId.value = null
  members.value = []
  await loadCollections()
  if (selectedId.value) await loadMembers()
  ElMessage.success('合集已删除，画廊未受影响')
}

// 将候选或手工检索结果加入合集，并记录其来源以便后续区分。
async function add(row, source) {
  await api.post(`/collections/${selectedId.value}/items`, { gids: [row.gid], source })
  await Promise.all([loadMembers(), loadCollections(selectedId.value)])
  suggestions.value = suggestions.value.filter(item => item.gid !== row.gid)
  searchResults.value = searchResults.value.filter(item => item.gid !== row.gid)
  ElMessage.success(source === 'SUGGESTED' ? '已采纳建议' : '已归入合集')
}

const addSuggested = row => add(row, 'SUGGESTED')
const addManual = row => add(row, 'MANUAL')

// 从当前合集移除成员，并刷新相关列表。
async function removeGallery(row) {
  await api.delete(`/collections/${selectedId.value}/items/${row.gid}`)
  await Promise.all([loadMembers(), loadCollections(selectedId.value)])
  ElMessage.success('已移出合集')
}

// 按当前合集获取归组建议，不直接替用户决定成员关系。
async function loadSuggestions() {
  if (!selectedId.value) return
  loadingSuggestions.value = true
  try {
    const res = await api.get(`/collections/${selectedId.value}/suggestions`, { params: { limit: 50 } })
    suggestions.value = res.data || []
  } finally { loadingSuggestions.value = false }
}

// 检索可供人工加入合集的画廊。
async function searchGalleries() {
  searching.value = true
  try {
    const res = await api.get('/collections/gallery-search', {
      params: { keyword: keyword.value.trim() || undefined, scope: searchScope.value, limit: 50 }
    })
    searchResults.value = res.data || []
  } finally { searching.value = false }
}

function handleTabChange(name) {
  if (name === 'suggestions' && !suggestions.value.length) loadSuggestions()
  if (name === 'search' && !searchResults.value.length) searchGalleries()
}

onMounted(async () => {
  await Promise.all([loadCollections(), loadSyncStatus()])
  if (selectedId.value) await loadMembers()
  syncTimer = window.setInterval(async () => {
    if (syncStatus.running) await loadSyncStatus()
  }, 2500)
})
onBeforeUnmount(() => window.clearInterval(syncTimer))
</script>

<style scoped>
.collections-page { max-width: 1920px; margin: 0 auto; color: var(--app-text, #25354b); }
.hero { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin: 5px 0 22px; padding: 0; color: var(--app-text, #25354b); }
.hero h2 { margin: 9px 0; font-size: calc(27px * var(--app-font-scale, 1)); line-height: 1.35; letter-spacing: -.03em; }
.hero p { margin: 0; color: var(--app-text-muted, #8492a8); font-size: calc(12px * var(--app-font-scale, 1)); line-height: 1.8; }
.hero .eyebrow { color: var(--app-accent, #4169e1); font-size: calc(10px * var(--app-font-scale, 1)); font-weight: 700; letter-spacing: .16em; }
.hero-actions { display: flex; gap: 10px; }
.sync-card { margin: -4px 0 18px; border: 0; }
.sync-line { display: flex; justify-content: space-between; gap: 16px; margin-bottom: 10px; color: #52606d; }
.workspace { display: grid; grid-template-columns: 290px minmax(0, 1fr); gap: 18px; align-items: start; }
.collection-list, .detail-panel { border: 0; border-radius: 12px; }
.panel-title { display: flex; align-items: center; justify-content: space-between; font-weight: 700; }
.collection-row { width: 100%; display: flex; align-items: center; gap: 12px; padding: 12px; margin-bottom: 7px; border: 0; border-radius: 9px; color: #52606d; background: transparent; text-align: left; cursor: pointer; transition: .2s ease; }
.collection-row:hover { background: var(--app-surface-muted, #f6f8fc); }
.collection-row.active { color: var(--app-accent, #4169e1); background: #edf3ff; }
.collection-mark { width: 36px; height: 36px; display: grid; place-items: center; flex: 0 0 auto; border-radius: 9px; color: var(--app-accent, #4169e1); background: #edf3ff; font-weight: 700; }
.collection-copy { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 3px; }
.collection-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.collection-copy small { color: #91a0ae; }
.detail-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding-bottom: 12px; }
.detail-header h3 { margin: 0 0 5px; font-size: calc(23px * var(--app-font-scale, 1)); }
.detail-header p { margin: 0; color: #8693a0; }
.header-actions { display: flex; flex-shrink: 0; }
.tab-toolbar, .search-bar { display: flex; align-items: center; gap: 10px; margin: 8px 0 16px; }
.tab-toolbar { justify-content: space-between; }
.hint { color: #8a97a5; font-size: calc(13px * var(--app-font-scale, 1)); }
:deep(.gallery-rows) { display: flex; flex-direction: column; gap: 8px; }
:deep(.gallery-row) { display: grid; grid-template-columns: 82px minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 14px; border: 1px solid #edf0f3; border-radius: 9px; background: #fff; transition: border-color .2s, box-shadow .2s; }
:deep(.gallery-row:hover) { border-color: #acd8d0; box-shadow: 0 5px 16px rgba(38, 92, 83, .08); }
:deep(.gallery-id) { color: #668078; font: 700 calc(12px * var(--app-font-scale, 1)) ui-monospace, monospace; }
:deep(.gallery-copy) { display: flex; min-width: 0; flex-direction: column; gap: 5px; }
:deep(.gallery-copy strong), :deep(.gallery-copy small) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
:deep(.gallery-copy small) { color: #909ca7; }
:deep(.gallery-meta) { display: flex; align-items: center; gap: 12px; color: #98a2ad; font-size: calc(12px * var(--app-font-scale, 1)); }
:deep(.evidence) { display: flex; align-items: center; gap: 9px; color: #87939e; font-size: calc(12px * var(--app-font-scale, 1)); }
@media (max-width: 900px) { .workspace { grid-template-columns: 1fr; } .hero { align-items: flex-start; gap: 18px; flex-direction: column; } :deep(.gallery-row) { grid-template-columns: 60px minmax(0, 1fr); } :deep(.gallery-meta) { grid-column: 2; justify-content: space-between; } }
</style>
