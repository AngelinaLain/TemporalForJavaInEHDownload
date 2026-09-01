<template>
  <div class="review-page">
    <el-card shadow="hover" class="toolbar">
      <div class="toolbar-content">
        <div>
          <h3>去重人工审核</h3>
          <p>只有 65–84 分的灰区候选会暂停下载并出现在这里；人工结论不会被后续自动计算覆盖。</p>
        </div>
        <div class="toolbar-actions">
          <el-tag type="warning" size="large">待处理 {{ pendingCount }}</el-tag>
          <el-select v-model="filters.decision" style="width: 150px" @change="reloadFromFirstPage">
            <el-option label="待审核" value="PENDING" />
            <el-option label="已确认同作品" value="MATCH" />
            <el-option label="已确认不同作品" value="DIFFERENT" />
            <el-option label="全部记录" value="ALL" />
          </el-select>
          <el-button :loading="loading" @click="loadData">刷新</el-button>
        </div>
      </div>
    </el-card>

    <el-skeleton v-if="firstLoad" :rows="10" animated class="review-skeleton" />
    <el-empty v-else-if="!records.length" description="当前没有需要处理的去重审核" />

    <el-card v-for="item in records" :key="item.id" shadow="hover" class="review-card" v-loading="resolvingId === item.id">
      <template #header>
        <div class="case-header">
          <div class="case-title">
            <el-tag :type="decisionMeta(item.decision).type">{{ decisionMeta(item.decision).label }}</el-tag>
            <strong>候选匹配 #{{ item.id }}</strong>
            <span class="score" :class="scoreClass(item.matchScore)">{{ item.matchScore }} 分</span>
          </div>
          <span class="created-at">{{ formatDate(item.createdAt) }}</span>
        </div>
      </template>

      <el-alert :title="item.matchReason || '没有详细匹配理由'" type="warning" :closable="false" show-icon />

      <div class="comparison">
        <template v-for="(candidate, index) in reviewCandidates(item)" :key="candidate.gallery?.gid || index">
          <div v-if="index" class="versus">VS</div>
          <div
            v-if="candidate.gallery"
            class="candidate"
            :class="{
              recommended: item.recommendedGid === candidate.gallery.gid,
              selected: item.preferredGid === candidate.gallery.gid
            }"
          >
            <div class="candidate-heading">
              <span>{{ candidate.label }}</span>
              <span v-if="item.recommendedGid === candidate.gallery.gid" class="recommend-badge">系统推荐</span>
              <span v-if="item.preferredGid === candidate.gallery.gid" class="selected-badge">人工首选</span>
            </div>
            <div class="candidate-title">{{ candidate.gallery.title || '-' }}</div>
            <div class="original-title">原始标题：{{ candidate.gallery.originalTitle || '-' }}</div>
            <dl class="candidate-facts">
              <div><dt>GID</dt><dd>{{ candidate.gallery.gid }}</dd></div>
              <div><dt>评分</dt><dd>{{ candidate.gallery.rating ?? '-' }}</dd></div>
              <div><dt>页数</dt><dd>{{ candidate.gallery.pageCount ?? '-' }}</dd></div>
              <div><dt>状态</dt><dd>{{ getStatusMeta(candidate.gallery.downloadStatus).label }}</dd></div>
            </dl>
            <div class="candidate-tags">
              <span v-for="tag in (candidate.gallery.tags || []).slice(0, 10)" :key="tag" class="mini-tag" :title="tag">
                {{ tag }}
              </span>
            </div>
            <a v-if="candidate.gallery.galleryUrl" class="gallery-link" :href="candidate.gallery.galleryUrl"
               target="_blank" rel="noopener">打开 EH 画廊 ↗</a>
          </div>
          <div v-else class="candidate missing">画廊记录不存在</div>
        </template>
      </div>

      <div v-if="item.decision === 'PENDING'" class="case-actions">
        <el-button type="primary" @click="confirmRecommended(item)">确认系统推荐</el-button>
        <el-button @click="resolveReview(item, 'MATCH', item.left?.gid, `选择 GID ${item.left?.gid}`)">
          选择左侧
        </el-button>
        <el-button @click="resolveReview(item, 'MATCH', item.right?.gid, `选择 GID ${item.right?.gid}`)">
          选择右侧
        </el-button>
        <el-button type="danger" plain @click="resolveReview(item, 'DIFFERENT', null, '标记为不同作品')">
          不是同一作品
        </el-button>
      </div>
      <div v-else class="review-result">
        审核人：{{ item.reviewedBy || '-' }}；审核时间：{{ formatDate(item.reviewedAt) }}
        <template v-if="item.decision === 'MATCH'">；首选 GID：{{ item.preferredGid }}</template>
      </div>
    </el-card>

    <div v-if="pagination.total" class="pagination-wrapper">
      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :page-sizes="[10, 20, 50]"
        :total="pagination.total"
        layout="total, sizes, prev, pager, next"
        @size-change="reloadFromFirstPage"
        @current-change="loadData"
      />
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { getStatusMeta } from '../constants/status'

const records = ref([])
const loading = ref(false)
const firstLoad = ref(true)
const resolvingId = ref(null)
const pendingCount = ref(0)
const filters = reactive({ decision: 'PENDING' })
const pagination = reactive({ page: 1, size: 10, total: 0 })

const loadData = async () => {
  loading.value = true
  try {
    const res = await api.get('/dedupe-reviews', {
      params: {
        page: pagination.page,
        size: pagination.size,
        decision: filters.decision
      }
    })
    records.value = res.data.records || []
    pagination.total = Number(res.data.total || 0)
    pendingCount.value = Number(res.data.pendingCount || 0)
    firstLoad.value = false
  } finally {
    loading.value = false
  }
}

const reviewCandidates = item => [
  { label: '左侧版本', gallery: item.left },
  { label: '右侧版本', gallery: item.right }
]

const reloadFromFirstPage = () => {
  pagination.page = 1
  void loadData()
}

const confirmRecommended = item => {
  const gallery = item.recommendedGid === item.left?.gid ? item.left : item.right
  return resolveReview(item, 'MATCH', gallery?.gid, `确认系统推荐 GID ${gallery?.gid}`)
}

const resolveReview = async (item, decision, preferredGid, actionLabel) => {
  if (decision === 'MATCH' && !preferredGid) return
  try {
    await ElMessageBox.confirm(
      `${actionLabel}。该人工结论会覆盖后续自动判重，是否继续？`,
      '确认去重审核',
      { type: decision === 'DIFFERENT' ? 'warning' : 'info', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  resolvingId.value = item.id
  try {
    const res = await api.post(`/dedupe-reviews/${item.id}/resolve`, { decision, preferredGid })
    const workflowCount = res.data.workflows?.length || 0
    ElMessage.success(workflowCount ? `审核已保存，已派发 ${workflowCount} 个下载流程` : '审核结论已保存')
    await loadData()
  } finally {
    resolvingId.value = null
  }
}

const decisionMeta = decision => ({
  PENDING: { label: '待审核', type: 'warning' },
  MATCH: { label: '同一作品', type: 'success' },
  DIFFERENT: { label: '不同作品', type: 'danger' }
}[decision] || { label: decision || '-', type: 'info' })

const scoreClass = score => score >= 80 ? 'score-high' : score >= 70 ? 'score-medium' : 'score-low'

const formatDate = value => {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(loadData)
</script>

<style scoped>
.review-page { max-width: 1440px; margin: 0 auto; }
.toolbar-content, .case-header, .case-title, .toolbar-actions, .candidate-heading, .case-actions {
  display: flex; align-items: center;
}
.toolbar-content { justify-content: space-between; gap: 24px; }
.toolbar h3 { margin: 0 0 8px; color: #303133; }
.toolbar p { margin: 0; color: #606266; font-size: 14px; }
.toolbar-actions { gap: 10px; flex-shrink: 0; }
.review-skeleton, .review-card { margin-top: 16px; }
.case-header { justify-content: space-between; }
.case-title { gap: 10px; }
.created-at, .review-result { color: #909399; font-size: 13px; }
.score { font-size: 18px; font-weight: 700; }
.score-high { color: #d97706; }
.score-medium { color: #e6a23c; }
.score-low { color: #909399; }
.comparison { display: grid; grid-template-columns: minmax(0, 1fr) 48px minmax(0, 1fr); align-items: stretch; margin-top: 16px; }
.versus { display: flex; align-items: center; justify-content: center; color: #909399; font-weight: 700; }
.candidate { border: 1px solid #dcdfe6; border-radius: 8px; padding: 18px; background: #fff; min-width: 0; }
.candidate.recommended { border-color: #409eff; box-shadow: inset 0 0 0 1px #409eff; }
.candidate.selected { border-color: #67c23a; box-shadow: inset 0 0 0 1px #67c23a; }
.candidate-heading { justify-content: space-between; color: #606266; font-size: 13px; gap: 8px; }
.recommend-badge, .selected-badge { padding: 2px 8px; border-radius: 10px; color: #fff; background: #409eff; }
.selected-badge { background: #67c23a; }
.candidate-title { margin-top: 14px; font-size: 17px; font-weight: 600; color: #303133; line-height: 1.5; }
.original-title { margin-top: 8px; color: #606266; font-size: 13px; word-break: break-all; }
.candidate-facts { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin: 16px 0; }
.candidate-facts div { padding: 8px; background: #f5f7fa; border-radius: 6px; text-align: center; }
.candidate-facts dt { color: #909399; font-size: 12px; }
.candidate-facts dd { margin: 4px 0 0; color: #303133; font-weight: 600; }
.candidate-tags { display: flex; flex-wrap: wrap; gap: 6px; min-height: 24px; }
.mini-tag { padding: 3px 7px; border-radius: 4px; background: #f0f2f5; color: #606266; font-size: 12px; }
.gallery-link { display: inline-block; margin-top: 14px; color: #409eff; text-decoration: none; }
.case-actions { justify-content: flex-end; margin-top: 18px; flex-wrap: wrap; gap: 8px; }
.case-actions .el-button + .el-button { margin-left: 0; }
.review-result { margin-top: 16px; text-align: right; }
.pagination-wrapper { display: flex; justify-content: flex-end; margin: 20px 0; }
@media (max-width: 900px) {
  .toolbar-content { align-items: flex-start; flex-direction: column; }
  .toolbar-actions { flex-wrap: wrap; }
  .comparison { grid-template-columns: 1fr; gap: 12px; }
  .versus { height: 24px; }
  .candidate-facts { grid-template-columns: repeat(2, 1fr); }
}
</style>
