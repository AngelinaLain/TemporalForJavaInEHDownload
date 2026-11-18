<template>
  <div class="rss-page">
    <PageHeading title="RSS 订阅" eyebrow="RSS SUBSCRIPTIONS" description="订阅关键词更新，自动补查遗漏画廊，下载后归档到群晖并同步 Komga。" />
    <el-alert v-if="!systemEnabled" title="RSS 后台调度已在服务器配置中关闭" type="warning" :closable="false" />
    <el-card>
      <template #header><div class="toolbar"><span>关键词订阅</span><el-button @click="refresh" :loading="busy">刷新</el-button><el-button type="primary" @click="openEditor()">新建订阅</el-button></div></template>
      <el-table :data="subscriptions" v-loading="busy" empty-text="还没有订阅，可以新建，或在工作流操作中保存搜索条件。">
        <el-table-column label="订阅" min-width="190"><template #default="{ row }"><strong>{{ row.name }}</strong><div class="muted">{{ row.config.searchOptions.keyword }}</div><div class="muted">每 {{ row.config.intervalMinutes }} 分钟 · {{ row.autoDownload ? '自动下载' : '仅收集' }}</div></template></el-table-column>
        <el-table-column label="目标合集" min-width="185"><template #default="{ row }"><template v-if="row.config.collectionEnabled"><router-link to="/collections">{{ collectionName(row.config.collectionId, row.collectionName) }}</router-link><div class="muted">下载后归入合集目录与 Komga 系列</div></template><span v-else class="muted">未设置</span><div v-if="row.collectionCounts?.length" class="counts collection-counts"><el-tag v-for="count in row.collectionCounts" :key="count.status" size="small" :type="collectionStatusType(count.status)">{{ collectionStatusLabel(count.status) }} {{ count.total }}</el-tag></div></template></el-table-column>
        <el-table-column label="启用" width="90"><template #default="{ row }"><el-switch :model-value="Boolean(row.enabled)" :disabled="acting" @change="enabled => action(row, 'enabled', { enabled })" /></template></el-table-column>
        <el-table-column label="检查与补查" min-width="210"><template #default="{ row }"><div>检查：{{ date(row.lastSuccessAt) }}</div><div>覆盖至：{{ date(row.coverageAt) }}</div><el-tag v-if="row.scanStartedAt" type="warning" size="small">补查未完成 · 已扫描 {{ row.scanPages }} 页</el-tag><div v-if="row.lastError" class="error">{{ row.lastError }}</div></template></el-table-column>
        <el-table-column label="处理情况" min-width="190"><template #default="{ row }"><div class="counts"><el-tag v-for="count in row.counts" :key="count.status" size="small" :type="statusType(count.status)">{{ label(count.status) }} {{ count.total }}</el-tag><span v-if="!row.counts.length" class="muted">尚无命中</span></div></template></el-table-column>
        <el-table-column label="操作" width="250"><template #default="{ row }"><el-button link type="primary" @click="openEditor(row)">编辑</el-button><el-button link type="primary" @click="showDetails(row)">记录</el-button><el-button link :disabled="!row.enabled || acting || !systemEnabled" @click="action(row, 'poll')">立即检查</el-button><el-button link :disabled="acting" @click="action(row, 'retry')">重试失败</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="editing" :title="editId ? '编辑订阅' : '新建订阅'" width="min(1050px, 95vw)" :close-on-click-modal="false">
      <el-form label-width="140px" @submit.prevent="save">
        <el-form-item label="订阅名称" required><el-input v-model="form.name" maxlength="100" /></el-form-item>
        <SearchConditionForm v-model="form.searchOptions" v-model:tags="form.tags" />
        <el-divider>更新与下载</el-divider>
        <el-row><el-col :sm="12"><el-form-item label="检查间隔（分钟）"><el-input-number v-model="form.intervalMinutes" :min="5" :max="1440" /></el-form-item></el-col><el-col :sm="12"><el-form-item label="启用订阅"><el-switch v-model="form.enabled" /></el-form-item></el-col></el-row>
        <el-row><el-col :sm="12"><el-form-item label="自动下载"><el-switch v-model="form.autoDownload" /><span class="hint">关闭时只收集命中画廊</span></el-form-item></el-col><el-col :sm="12"><el-form-item label="首次处理"><el-select v-model="form.initialMode"><el-option value="NEW_ONLY" label="从现在开始订阅新增" /><el-option value="CURRENT" label="同时补查近期历史" /></el-select></el-form-item></el-col></el-row>
        <el-form-item label="归入画廊合集"><el-switch v-model="form.collectionEnabled" @change="enabled => enabled && loadCollections()" /><span class="hint">使用“画廊合集”中的合集及归档规则</span></el-form-item>
        <el-form-item v-if="form.collectionEnabled" label="目标合集" required>
          <div class="collection-field">
            <div class="collection-controls">
              <el-select v-model="form.collectionId" filterable clearable :loading="loadingCollections" placeholder="选择已有画廊合集" aria-label="目标画廊合集">
                <el-option v-for="collection in collections" :key="collection.id" :value="Number(collection.id)" :label="collection.name" />
                <el-option v-if="form.collectionId && !selectedCollection" :value="form.collectionId" :label="collectionName(form.collectionId)" disabled />
              </el-select>
              <el-button :disabled="saving" @click="openCollectionEditor">新建合集</el-button>
              <el-button :loading="loadingCollections" @click="loadCollections">刷新合集</el-button>
            </div>
            <p v-if="collectionLoadError" class="error">{{ collectionLoadError }}</p>
            <p v-else-if="collectionsLoaded && !collections.length" class="muted">还没有画廊合集，可以先新建。</p>
            <p v-else-if="collectionsLoaded && form.collectionId && !selectedCollection" class="error">所选合集已不存在，请重新选择或新建合集。</p>
            <p v-if="selectedCollection?.description" class="muted">{{ selectedCollection.description }}</p>
            <p class="muted">下载完成后归入此合集，并按合集目录同步到 Komga 系列。订阅已收集且已入库的画廊也可补归档。{{ form.autoDownload ? '' : '当前仅收集，尚未下载的画廊需完成下载后才能归档。' }}</p>
            <p class="muted">与“画廊合集”使用相同的目录和 Komga 系列规则。画廊已有的合集归属会保留，命中多个订阅时不会来回移动；关闭此设置或停用订阅不会移除已有归档。</p>
          </div>
        </el-form-item>
        <el-form-item v-if="form.initialMode === 'CURRENT'" label="历史范围（天）"><el-input-number v-model="form.historyDays" :min="1" :max="365" /></el-form-item>
        <el-row><el-col :sm="12"><el-form-item label="补查间隔（小时）"><el-input-number v-model="form.backfillIntervalHours" :min="1" :max="168" /></el-form-item></el-col><el-col :sm="12"><el-form-item label="每轮补查页数"><el-input-number v-model="form.maxBackfillPages" :min="1" :max="100" /></el-form-item></el-col></el-row>
        <el-alert :closable="false" type="info" title="补查达到本轮页数上限后会保留游标，下轮继续。新版本画廊仍遵循现有作品去重规则；同一 GID 的元数据变化不会自动重复下载。" />
        <el-alert v-if="editId" class="spaced" :closable="false" type="warning" title="修改检索条件或首次处理范围会建立新的订阅边界。暂停停止新派发，已经启动的下载继续完成。" />
      </el-form>
      <el-table v-if="previewItems.length" :data="previewItems" max-height="250" class="spaced"><el-table-column label="当前 RSS 匹配结果"><template #default="{ row }"><a :href="row.url" target="_blank" rel="noopener noreferrer">{{ row.title }}</a></template></el-table-column><el-table-column label="发布时间" width="175"><template #default="{ row }">{{ date(row.publishedAt) }}</template></el-table-column></el-table>
      <p v-if="previewed && !previewItems.length" class="muted">当前 RSS 没有匹配结果。</p>
      <template #footer><el-button @click="preview" :loading="previewing">预览当前匹配</el-button><el-button @click="editing = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存订阅</el-button></template>
    </el-dialog>
    <el-dialog v-model="collectionEditorVisible" title="新建画廊合集" width="min(480px, 95vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!creatingCollection" :show-close="!creatingCollection">
      <el-form label-position="top" :disabled="creatingCollection" @submit.prevent="createCollection">
        <el-form-item label="合集名称" required><el-input v-model="collectionForm.name" maxlength="120" show-word-limit autofocus /><p class="muted">名称用作群晖目录名，最多 120 个字符，不支持 &lt; &gt; : &quot; / \ | ? *、控制字符或末尾句点。</p></el-form-item>
        <el-form-item label="说明"><el-input v-model="collectionForm.description" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
        <p class="muted">合集会保存到“画廊合集”，创建后自动选为此订阅的目标合集。</p>
      </el-form>
      <template #footer><el-button :disabled="creatingCollection" @click="collectionEditorVisible = false">取消</el-button><el-button type="primary" :loading="creatingCollection" @click="createCollection">创建并选中</el-button></template>
    </el-dialog>
    <el-dialog v-model="details" :title="selected?.name + ' · 订阅记录'" width="min(1200px, 95vw)">
      <div class="toolbar"><el-button @click="loadDetails" :loading="detailBusy">刷新记录</el-button><span class="muted">同一画廊可命中多个订阅，共用下载结果。</span></div>
      <el-tabs v-model="detailTab">
        <el-tab-pane label="命中画廊" name="items">
          <el-table :data="items" v-loading="detailBusy" max-height="500"><el-table-column prop="gid" label="GID" width="95" /><el-table-column label="画廊" min-width="250"><template #default="{ row }"><a :href="row.url" target="_blank" rel="noopener noreferrer">{{ row.title }}</a><div class="muted">条件版本 {{ row.revision }} · {{ date(row.discoveredAt) }}</div><div class="muted">{{ row.searchQuery }}</div><div v-if="row.workflowId" class="muted">{{ row.workflowId }}</div></template></el-table-column><el-table-column label="状态" width="150"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ label(row.status) }}</el-tag><div class="muted">{{ row.downloadStatus }}</div></template></el-table-column><el-table-column label="合集归档" min-width="210"><template #default="{ row }"><el-tag v-if="row.collectionStatus" :type="collectionStatusType(row.collectionStatus)">{{ collectionStatusLabel(row.collectionStatus) }}</el-tag><span v-else class="muted">暂无归档记录</span><div v-if="row.collectionError" class="error">{{ row.collectionError }}</div></template></el-table-column><el-table-column prop="lastError" label="最近原因" min-width="230" /></el-table>
          <el-pagination class="spaced" v-model:current-page="page" :page-size="25" :total="total" layout="prev, pager, next, total" @current-change="loadDetails" />
        </el-tab-pane>
        <el-tab-pane label="检查记录（最近 100 次）" name="runs"><el-table :data="runs" max-height="500"><el-table-column label="开始时间" width="175"><template #default="{ row }">{{ date(row.startedAt) }}</template></el-table-column><el-table-column prop="status" label="状态" width="110" /><el-table-column prop="discoveredCount" label="新记录" width="85" /><el-table-column prop="pages" label="补查页数" width="95" /><el-table-column prop="message" label="说明" /></el-table></el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>
<script setup>
import { computed, ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeading from '../../../shared/components/PageHeading.vue'
import SearchConditionForm from '../../../shared/search/SearchConditionForm.vue'
import { defaultSearchOptions } from '../../../shared/search/searchOptions'
import * as rssApi from '../api'
import * as collectionApi from '../../collections/api'
const route = useRoute(), router = useRouter()
const subscriptions = ref([]), busy = ref(false), systemEnabled = ref(true), acting = ref(false)
const editing = ref(false), editId = ref(null), saving = ref(false), previewing = ref(false), previewed = ref(false), previewItems = ref([])
const defaults = () => ({ name: '', keyword: '', tags: [], searchOptions: defaultSearchOptions(), intervalMinutes: 15, enabled: true, autoDownload: true, collectionEnabled: false, collectionId: null, initialMode: 'NEW_ONLY', historyDays: 7, backfillIntervalHours: 6, maxBackfillPages: 10 })
const form = reactive(defaults())
const collections = ref([]), loadingCollections = ref(false), collectionsLoaded = ref(false), collectionLoadError = ref('')
const collectionEditorVisible = ref(false), creatingCollection = ref(false), collectionForm = reactive({ name: '', description: '' })
const selectedCollection = computed(() => collections.value.find(collection => Number(collection.id) === Number(form.collectionId)))
let collectionsRequest
const details = ref(false), selected = ref(null), detailTab = ref('items'), detailBusy = ref(false), items = ref([]), runs = ref([]), page = ref(1), total = ref(0)
const labels = { PENDING: '待下载', QUEUED: '已派发', RETRY: '等待重试', FAILED: '失败', IMPORTED: '已入库', SKIPPED: '重复或忽略', BLOCKED: '访问阻断', REVIEW_REQUIRED: '待人工审核', WAITING_EXISTING: '等待已有任务', COLLECTED: '已收集', BASELINE: '历史基线' }
const label = status => labels[status] || status
const statusType = status => status === 'IMPORTED' ? 'success' : ['FAILED', 'BLOCKED'].includes(status) ? 'danger' : ['RETRY', 'REVIEW_REQUIRED'].includes(status) ? 'warning' : 'info'
const collectionLabels = { WAITING: '待归入', WAITING_IMPORT: '等待入库', WAITING_KOMGA: '等待扫描', RUNNING: '整理中', CANCELLED: '已暂停', SYNCED: '已归入', CONFLICT: '保留已有合集', RETRY: '等待重试', FAILED: '归档失败' }
const collectionStatusLabel = status => collectionLabels[status] || status
const collectionStatusType = status => status === 'SYNCED' ? 'success' : status === 'RUNNING' ? 'primary' : status === 'FAILED' ? 'danger' : ['CONFLICT', 'RETRY'].includes(status) ? 'warning' : 'info'
const date = value => value == null ? '尚未完成' : new Date(Number(value)).toLocaleString('zh-CN')
async function refresh() { busy.value = true; try { const res = await rssApi.listSubscriptions(); subscriptions.value = res.data.records; systemEnabled.value = res.data.enabled } catch {} finally { busy.value = false } }
function collectionName(id, fallback) { return collections.value.find(collection => Number(collection.id) === Number(id))?.name || fallback || (id ? `合集 #${id}${collectionsLoaded.value ? '（不可用）' : ''}` : '未选择合集') }
// 与画廊合集页面共用接口，保留完整列表用于名称展示和保存前校验。
async function loadCollections() {
  if (collectionsRequest) return collectionsRequest
  loadingCollections.value = true; collectionLoadError.value = ''
  collectionsRequest = (async () => {
    try { const res = await collectionApi.listCollections({ silent: true }); collections.value = res.data || []; collectionsLoaded.value = true }
    catch { collectionLoadError.value = '合集加载失败，请点击“刷新合集”重试。'; collectionsLoaded.value = false }
    finally { loadingCollections.value = false; collectionsRequest = null }
  })()
  return collectionsRequest
}
function openCollectionEditor() { Object.assign(collectionForm, { name: '', description: '' }); collectionEditorVisible.value = true }
async function createCollection() {
  if (creatingCollection.value) return
  const name = collectionForm.name.trim(), description = collectionForm.description.trim()
  if (!name) return ElMessage.warning('请输入合集名称')
  if (name.length > 120 || description.length > 1000) return ElMessage.warning('合集名称最多 120 个字符，说明最多 1000 个字符')
  if (/[<>:"/\\|?*\u0000-\u001f\u007f]/.test(name) || name.endsWith('.')) return ElMessage.warning('合集名称包含群晖文件夹不支持的字符，请移除非法符号、控制字符或末尾句点')
  creatingCollection.value = true
  try {
    const res = await collectionApi.createCollection({ name, description })
    await loadCollections()
    if (!collections.value.some(collection => Number(collection.id) === Number(res.data.id))) collections.value.unshift({ ...res.data, itemCount: 0 })
    form.collectionEnabled = true; form.collectionId = Number(res.data.id); collectionEditorVisible.value = false
    ElMessage.success('合集已创建并选中，保存订阅后生效')
  } catch {} finally { creatingCollection.value = false }
}
function openEditor(row) {
  editId.value = row?.id || null
  const next = row ? JSON.parse(JSON.stringify(row.config)) : defaults()
  if (row) { next.enabled = Boolean(row.enabled); next.autoDownload = Boolean(row.autoDownload); next.searchOptions.keyword = next.keyword || '' }
  Object.assign(form, defaults(), next); form.collectionId = form.collectionId == null ? null : Number(form.collectionId); previewItems.value = []; previewed.value = false; editing.value = true
  loadCollections()
}
function payload() { return { ...form, collectionId: form.collectionEnabled ? form.collectionId : null, keyword: form.searchOptions.keyword, searchOptions: { ...form.searchOptions } } }
async function save() {
  if (saving.value) return
  if (!form.name.trim() || (!form.searchOptions.keyword.trim() && !form.tags.length)) return ElMessage.warning('请填写订阅名称和关键词或标签')
  if (form.collectionEnabled) {
    if (!Number.isSafeInteger(form.collectionId) || form.collectionId <= 0) return ElMessage.warning('请选择或新建一个画廊合集')
    if (!collectionsLoaded.value || loadingCollections.value) return ElMessage.warning('请先成功加载合集列表，再保存订阅')
    if (!selectedCollection.value) return ElMessage.warning('所选合集已不存在，请重新选择或新建合集')
  }
  saving.value = true
  try { if (editId.value) await rssApi.updateSubscription(editId.value, payload()); else await rssApi.createSubscription(payload()); editing.value = false; ElMessage.success('订阅已保存'); await refresh() } catch {} finally { saving.value = false }
}
async function preview() { previewing.value = true; previewed.value = false; try { previewItems.value = (await rssApi.previewSubscription(payload())).data; previewed.value = true } catch {} finally { previewing.value = false } }
async function action(row, operation, body) { acting.value = true; try { await rssApi.runSubscriptionAction(row.id, operation, body); ElMessage.success(operation === 'poll' ? '已请求检查' : '操作已保存'); await refresh() } catch {} finally { acting.value = false } }
async function remove(row) { try { await ElMessageBox.confirm(`删除订阅「${row.name}」？已归档的画廊会保留。`, '删除订阅', { type: 'warning' }); await rssApi.deleteSubscription(row.id); await refresh() } catch {} }
async function showDetails(row) { selected.value = row; page.value = 1; details.value = true; await loadDetails() }
async function loadDetails() {
  if (!selected.value) return
  detailBusy.value = true
  try { const [itemRes, runRes] = await Promise.all([rssApi.listSubscriptionItems(selected.value.id, { page: page.value, size: 25 }), rssApi.listSubscriptionRuns(selected.value.id)]); items.value = itemRes.data.records; total.value = itemRes.data.total; runs.value = runRes.data } catch {} finally { detailBusy.value = false }
}
onMounted(async () => {
  await Promise.all([refresh(), loadCollections()])
  if (route.query.create === '1') {
    openEditor()
    try { const draft = JSON.parse(sessionStorage.getItem('rss-subscription-draft') || 'null'); if (draft) Object.assign(form, draft) } catch {}
    sessionStorage.removeItem('rss-subscription-draft'); router.replace('/rss-subscriptions')
  }
})
</script>
<style scoped>
.rss-page { display:flex; flex-direction:column; gap:20px; }
.toolbar { display:flex; align-items:center; gap:12px; flex-wrap:wrap; }
.toolbar > span:first-child { margin-right:auto; }
.counts { display:flex; flex-wrap:wrap; gap:6px; }
.muted,.hint { color:var(--el-text-color-secondary); font-size:12px; word-break:break-word; }
.hint { margin-left:10px; }
.error { color:var(--el-color-danger); font-size:12px; word-break:break-word; }
.spaced { margin-top:16px; }
.collection-field { width:100%; }
.collection-controls { display:flex; gap:10px; flex-wrap:wrap; }
.collection-controls .el-select { flex:1; min-width:200px; }
.collection-controls .el-button + .el-button { margin-left:0; }
.collection-field p { margin:8px 0 0; line-height:1.7; }
.collection-counts { margin-top:6px; }
a { color:var(--el-color-primary); text-decoration:none; }
</style>
