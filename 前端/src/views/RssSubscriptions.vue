<template>
  <div class="rss-page">
    <PageHeading title="RSS 订阅" eyebrow="RSS SUBSCRIPTIONS" description="订阅关键词更新，自动补查遗漏画廊，下载后归档到群晖并同步 Komga。" />
    <el-alert v-if="!systemEnabled" title="RSS 后台调度已在服务器配置中关闭" type="warning" :closable="false" />
    <el-card>
      <template #header><div class="toolbar"><span>关键词订阅</span><el-button @click="refresh" :loading="busy">刷新</el-button><el-button type="primary" @click="openEditor()">新建订阅</el-button></div></template>
      <el-table :data="subscriptions" v-loading="busy" empty-text="还没有订阅，可以新建，或在工作流操作中保存搜索条件。">
        <el-table-column label="订阅" min-width="190"><template #default="{ row }"><strong>{{ row.name }}</strong><div class="muted">{{ row.config.searchOptions.keyword }}</div><div class="muted">每 {{ row.config.intervalMinutes }} 分钟 · {{ row.autoDownload ? '自动下载' : '仅收集' }}</div></template></el-table-column>
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
        <el-form-item v-if="form.initialMode === 'CURRENT'" label="历史范围（天）"><el-input-number v-model="form.historyDays" :min="1" :max="365" /></el-form-item>
        <el-row><el-col :sm="12"><el-form-item label="补查间隔（小时）"><el-input-number v-model="form.backfillIntervalHours" :min="1" :max="168" /></el-form-item></el-col><el-col :sm="12"><el-form-item label="每轮补查页数"><el-input-number v-model="form.maxBackfillPages" :min="1" :max="100" /></el-form-item></el-col></el-row>
        <el-alert :closable="false" type="info" title="补查达到本轮页数上限后会保留游标，下轮继续。新版本画廊仍遵循现有作品去重规则；同一 GID 的元数据变化不会自动重复下载。" />
        <el-alert v-if="editId" class="spaced" :closable="false" type="warning" title="修改检索条件或首次处理范围会建立新的订阅边界。暂停停止新派发，已经启动的下载继续完成。" />
      </el-form>
      <el-table v-if="previewItems.length" :data="previewItems" max-height="250" class="spaced"><el-table-column label="当前 RSS 匹配结果"><template #default="{ row }"><a :href="row.url" target="_blank" rel="noopener noreferrer">{{ row.title }}</a></template></el-table-column><el-table-column label="发布时间" width="175"><template #default="{ row }">{{ date(row.publishedAt) }}</template></el-table-column></el-table>
      <p v-if="previewed && !previewItems.length" class="muted">当前 RSS 没有匹配结果。</p>
      <template #footer><el-button @click="preview" :loading="previewing">预览当前匹配</el-button><el-button @click="editing = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存订阅</el-button></template>
    </el-dialog>
    <el-dialog v-model="details" :title="selected?.name + ' · 订阅记录'" width="min(1200px, 95vw)">
      <div class="toolbar"><el-button @click="loadDetails" :loading="detailBusy">刷新记录</el-button><span class="muted">同一画廊可命中多个订阅，共用下载结果。</span></div>
      <el-tabs v-model="detailTab">
        <el-tab-pane label="命中画廊" name="items">
          <el-table :data="items" v-loading="detailBusy" max-height="500"><el-table-column prop="gid" label="GID" width="95" /><el-table-column label="画廊" min-width="250"><template #default="{ row }"><a :href="row.url" target="_blank" rel="noopener noreferrer">{{ row.title }}</a><div class="muted">条件版本 {{ row.revision }} · {{ date(row.discoveredAt) }}</div><div class="muted">{{ row.searchQuery }}</div><div v-if="row.workflowId" class="muted">{{ row.workflowId }}</div></template></el-table-column><el-table-column label="状态" width="150"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ label(row.status) }}</el-tag><div class="muted">{{ row.downloadStatus }}</div></template></el-table-column><el-table-column prop="lastError" label="最近原因" min-width="230" /></el-table>
          <el-pagination class="spaced" v-model:current-page="page" :page-size="25" :total="total" layout="prev, pager, next, total" @current-change="loadDetails" />
        </el-tab-pane>
        <el-tab-pane label="检查记录（最近 100 次）" name="runs"><el-table :data="runs" max-height="500"><el-table-column label="开始时间" width="175"><template #default="{ row }">{{ date(row.startedAt) }}</template></el-table-column><el-table-column prop="status" label="状态" width="110" /><el-table-column prop="discoveredCount" label="新记录" width="85" /><el-table-column prop="pages" label="补查页数" width="95" /><el-table-column prop="message" label="说明" /></el-table></el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeading from '../components/PageHeading.vue'
import SearchConditionForm from '../components/SearchConditionForm.vue'
import { defaultSearchOptions } from '../utils/searchOptions'
import api from '../api'
const route = useRoute(), router = useRouter()
const subscriptions = ref([]), busy = ref(false), systemEnabled = ref(true), acting = ref(false)
const editing = ref(false), editId = ref(null), saving = ref(false), previewing = ref(false), previewed = ref(false), previewItems = ref([])
const defaults = () => ({ name: '', keyword: '', tags: [], searchOptions: defaultSearchOptions(), intervalMinutes: 15, enabled: true, autoDownload: true, initialMode: 'NEW_ONLY', historyDays: 7, backfillIntervalHours: 6, maxBackfillPages: 10 })
const form = reactive(defaults())
const details = ref(false), selected = ref(null), detailTab = ref('items'), detailBusy = ref(false), items = ref([]), runs = ref([]), page = ref(1), total = ref(0)
const labels = { PENDING: '待下载', QUEUED: '已派发', RETRY: '等待重试', FAILED: '失败', IMPORTED: '已入库', SKIPPED: '重复或忽略', BLOCKED: '访问阻断', REVIEW_REQUIRED: '待人工审核', WAITING_EXISTING: '等待已有任务', COLLECTED: '已收集', BASELINE: '历史基线' }
const label = status => labels[status] || status
const statusType = status => status === 'IMPORTED' ? 'success' : ['FAILED', 'BLOCKED'].includes(status) ? 'danger' : ['RETRY', 'REVIEW_REQUIRED'].includes(status) ? 'warning' : 'info'
const date = value => value == null ? '尚未完成' : new Date(Number(value)).toLocaleString('zh-CN')
async function refresh() { busy.value = true; try { const res = await api.get('/rss/subscriptions'); subscriptions.value = res.data.records; systemEnabled.value = res.data.enabled } catch {} finally { busy.value = false } }
function openEditor(row) {
  editId.value = row?.id || null
  const next = row ? JSON.parse(JSON.stringify(row.config)) : defaults()
  if (row) { next.enabled = Boolean(row.enabled); next.autoDownload = Boolean(row.autoDownload); next.searchOptions.keyword = next.keyword || '' }
  Object.assign(form, defaults(), next); previewItems.value = []; previewed.value = false; editing.value = true
}
function payload() { return { ...form, keyword: form.searchOptions.keyword, searchOptions: { ...form.searchOptions } } }
async function save() {
  if (!form.name.trim() || (!form.searchOptions.keyword.trim() && !form.tags.length)) return ElMessage.warning('请填写订阅名称和关键词或标签')
  saving.value = true
  try { if (editId.value) await api.put(`/rss/subscriptions/${editId.value}`, payload()); else await api.post('/rss/subscriptions', payload()); editing.value = false; ElMessage.success('订阅已保存'); await refresh() } catch {} finally { saving.value = false }
}
async function preview() { previewing.value = true; previewed.value = false; try { previewItems.value = (await api.post('/rss/subscriptions/preview', payload(), { timeout: 100000 })).data; previewed.value = true } catch {} finally { previewing.value = false } }
async function action(row, operation, body) { acting.value = true; try { await api.post(`/rss/subscriptions/${row.id}/${operation}`, body); ElMessage.success(operation === 'poll' ? '已请求检查' : '操作已保存'); await refresh() } catch {} finally { acting.value = false } }
async function remove(row) { try { await ElMessageBox.confirm(`删除订阅「${row.name}」？已归档的画廊会保留。`, '删除订阅', { type: 'warning' }); await api.delete(`/rss/subscriptions/${row.id}`); await refresh() } catch {} }
async function showDetails(row) { selected.value = row; page.value = 1; details.value = true; await loadDetails() }
async function loadDetails() {
  if (!selected.value) return
  detailBusy.value = true
  try { const [itemRes, runRes] = await Promise.all([api.get(`/rss/subscriptions/${selected.value.id}/items`, { params: { page: page.value, size: 25 } }), api.get(`/rss/subscriptions/${selected.value.id}/runs`)]); items.value = itemRes.data.records; total.value = itemRes.data.total; runs.value = runRes.data } catch {} finally { detailBusy.value = false }
}
onMounted(async () => {
  await refresh()
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
a { color:var(--el-color-primary); text-decoration:none; }
</style>
