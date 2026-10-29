<template>
  <div class="review-page">
    <PageHeading title="去重人工审核" eyebrow="DUPLICATE REVIEW" description="审核灰区候选并确认版本关系，人工结论会保留用于后续处理。">
      <template #actions>
        <div class="toolbar-actions">
          <el-tag type="warning" size="large">待处理 {{ pendingCount }}</el-tag>
          <el-select v-model="filters.decision" style="width: 150px" @change="reloadFromFirstPage">
            <el-option label="待审核" value="PENDING" />
            <el-option label="已确认同作品" value="MATCH" />
            <el-option label="已确认不同作品" value="DIFFERENT" />
            <el-option label="同源不同版本" value="VARIANT" />
            <el-option label="全部记录" value="ALL" />
          </el-select>
          <el-button :loading="loading" @click="loadData">刷新</el-button>
        </div>
      </template>
    </PageHeading>

    <DedupeSettingsPanel />

    <el-skeleton v-if="firstLoad" :rows="10" animated class="review-skeleton" />
    <el-empty v-else-if="!records.length" description="当前没有需要处理的去重审核" />

    <el-card v-for="item in records" :key="item.id" shadow="hover" class="review-card" v-loading="resolvingId === item.id || aiReviewId === item.id || refreshingVisualId === item.id">
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
      <div v-if="hasVisualEvidence(item)" class="visual-evidence">
        <div class="visual-summary">
          <div><span>视觉相似度</span><strong>{{ item.visualSimilarity }}%</strong></div>
          <div><span>匹配采样页</span><strong>{{ item.visualMatchedPages }}</strong></div>
          <div><span>采样覆盖率</span><strong>{{ item.visualSampleCoverage }}%</strong></div>
          <div><span>质量差</span><strong>{{ item.visualQualityDelta }}%</strong></div>
        </div>
        <el-alert
          :title="item.visualReason || '视觉分析已完成'"
          :type="item.visualSimilarity >= 80 ? 'success' : 'info'"
          :closable="false"
          show-icon
        />
        <p v-if="item.visualRecommendedGid" class="visual-recommendation">
          视觉质量推荐：GID {{ item.visualRecommendedGid }}；最终仍需结合汉化版本与内容差异人工确认。
        </p>
      </div>

      <div class="visual-refresh-actions">
        <p>按当前参数重新比较已有指纹，不重新读取归档、不外发图片，也不改变人工结论。</p>
        <el-button plain :loading="refreshingVisualId === item.id" :disabled="refreshingVisualId !== null" @click="refreshVisualEvidence(item)">更新视觉证据</el-button>
      </div>

      <div class="comparison">
        <template v-for="(candidate, index) in reviewCandidates(item)" :key="candidate.gallery?.gid || index">
          <div v-if="index" class="versus">VS</div>
          <div
            v-if="candidate.gallery"
            class="candidate"
            :class="{
              recommended: recommendedCandidate(item) === candidate.gallery,
              selected: item.preferredGid === candidate.gallery.gid
            }"
          >
            <div class="candidate-heading">
              <span>{{ candidate.label }}</span>
              <span v-if="recommendedCandidate(item) === candidate.gallery" class="recommend-badge">系统推荐</span>
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
        <el-button type="primary" plain :loading="aiReviewId === item.id" @click="startAiReview(item)">
          AI 视觉复核
        </el-button>
        <el-button type="primary" :disabled="!recommendedCandidate(item)" @click="confirmRecommended(item)">{{ recommendedCandidate(item) ? '确认系统推荐' : '暂无系统推荐' }}</el-button>
        <el-button @click="resolveReview(item, 'MATCH', item.left?.gid, `选择 GID ${item.left?.gid}`)">
          选择左侧
        </el-button>
        <el-button @click="resolveReview(item, 'MATCH', item.right?.gid, `选择 GID ${item.right?.gid}`)">
          选择右侧
        </el-button>
        <el-button type="danger" plain @click="resolveReview(item, 'DIFFERENT', null, '标记为不同作品')">
          不是同一作品
        </el-button>
        <el-button type="warning" plain @click="resolveReview(item, 'VARIANT', null, '标记为同源但不同内容版本，两个都保留')">
          同源不同汉化（都保留）
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
// 展示重复画廊候选及比较证据，提交人工判断或启动 AI 视觉复核。
import PageHeading from '../components/PageHeading.vue'
import DedupeSettingsPanel from '../components/DedupeSettingsPanel.vue'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { getStatusMeta } from '../constants/status'

const records = ref([])
const loading = ref(false)
const firstLoad = ref(true)
const resolvingId = ref(null)
const aiReviewId = ref(null)
const refreshingVisualId = ref(null)
const pendingCount = ref(0)
const filters = reactive({ decision: 'PENDING' })
const pagination = reactive({ page: 1, size: 10, total: 0 })

// 读取当前审核页和待处理数量，完成后结束首次加载占位。
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

// 整理一条审核记录的左右候选，供比较卡共用。
const reviewCandidates = item => [
  { label: '左侧版本', gallery: item.left },
  { label: '右侧版本', gallery: item.right }
]

const hasVisualEvidence = item => item.visualSimilarity !== null && item.visualSimilarity !== undefined

const reloadFromFirstPage = () => {
  pagination.page = 1
  void loadData()
}

// 推荐必须明确对应仍存在的左侧或右侧记录；缺失或过时的推荐不替用户选择版本。
const recommendedCandidate = item => {
  if (item?.recommendedGid == null) return null
  return [item.left, item.right].find(gallery => gallery?.gid > 0 && gallery.gid === item.recommendedGid) || null
}

const confirmRecommended = item => {
  const gallery = recommendedCandidate(item)
  if (!gallery) return ElMessage.warning('当前没有有效的系统推荐，请查看两侧证据后手动选择版本。')
  return resolveReview(item, 'MATCH', gallery.gid, `确认系统推荐 GID ${gallery.gid}`)
}

// 参数修改后可单独重比这条审核的已有指纹，随后重新读取当前页展示最新证据。
const refreshVisualEvidence = async item => {
  if (refreshingVisualId.value !== null) return
  refreshingVisualId.value = item.id
  try {
    await api.post(`/dedupe-reviews/${item.id}/refresh-visual`)
    await loadData()
    ElMessage.success('视觉证据已按当前参数更新，人工结论保持不变')
  } finally {
    refreshingVisualId.value = null
  }
}

// 提交人工的同作、不同或版本判断，再刷新审核列表。
const resolveReview = async (item, decision, preferredGid, actionLabel) => {
  if (decision === 'MATCH' && !preferredGid) return
  try {
    await ElMessageBox.confirm(
      `${actionLabel}。该人工结论会覆盖后续自动判重，是否继续？`,
      '确认去重审核',
      { type: ['DIFFERENT', 'VARIANT'].includes(decision) ? 'warning' : 'info', confirmButtonText: '确认', cancelButtonText: '取消' }
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

// 将当前候选交给后台 AI 视觉复核，人工审核关系仍由用户决定。
const startAiReview = async item => {
  aiReviewId.value = item.id
  try {
    const res = await api.post(`/dedupe-reviews/${item.id}/ai-review`)
    localStorage.setItem('visualAiReviewJobId', res.data.jobId)
    ElMessage.success('AI 视觉复核已提交；可在“视觉去重”查看进度和结果')
  } finally {
    aiReviewId.value = null
  }
}
const decisionMeta = decision => ({
  PENDING: { label: '待审核', type: 'warning' },
  MATCH: { label: '同一作品', type: 'success' },
  DIFFERENT: { label: '不同作品', type: 'danger' },
  VARIANT: { label: '同源不同版本', type: 'warning' }
}[decision] || { label: decision || '-', type: 'info' })

const scoreClass = score => score >= 80 ? 'score-high' : score >= 70 ? 'score-medium' : 'score-low'

const formatDate = value => {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(loadData)
</script>

<style scoped>
.review-page { max-width: 1920px; margin: 0 auto; }
.toolbar-content, .case-header, .case-title, .toolbar-actions, .candidate-heading, .case-actions {
  display: flex; align-items: center;
}
.toolbar-content { justify-content: space-between; gap: 24px; }
.toolbar h3 { margin: 0 0 8px; color: var(--app-text, #25354b); }
.toolbar p { margin: 0; color: var(--app-text-secondary, #64748b); font-size: calc(14px * var(--app-font-scale, 1)); }
.toolbar-actions { gap: 10px; flex-shrink: 0; }
.review-skeleton, .review-card { margin-top: 16px; }
.case-header { justify-content: space-between; }
.case-title { gap: 10px; }
.created-at, .review-result { color: var(--app-text-muted, #8492a8); font-size: calc(13px * var(--app-font-scale, 1)); }
.score { font-size: calc(18px * var(--app-font-scale, 1)); font-weight: 700; }
.score-high { color: #d97706; }
.score-medium { color: #e6a23c; }
.score-low { color: var(--app-text-muted, #8492a8); }
.comparison { display: grid; grid-template-columns: minmax(0, 1fr) 48px minmax(0, 1fr); align-items: stretch; margin-top: 16px; }
.visual-evidence { margin-top: 14px; padding: 14px; border: 1px solid #d9ecff; border-radius: 8px; background: #f4f9ff; }
.visual-summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 12px; }
.visual-summary div { display: flex; flex-direction: column; gap: 4px; padding: 10px; border-radius: 6px; background: #fff; }
.visual-summary span { color: var(--app-text-muted, #8492a8); font-size: calc(12px * var(--app-font-scale, 1)); }
.visual-summary strong { color: var(--app-text, #25354b); font-size: calc(18px * var(--app-font-scale, 1)); }
.visual-recommendation { margin: 10px 0 0; color: var(--app-text-secondary, #64748b); font-size: calc(13px * var(--app-font-scale, 1)); }
.visual-refresh-actions { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; margin-top: 14px; }.visual-refresh-actions p { flex: 1; min-width: 200px; margin: 0; color: var(--app-text-muted); font-size: calc(11px * var(--app-font-scale, 1)); line-height: 1.8; }
.versus { display: flex; align-items: center; justify-content: center; color: var(--app-text-muted, #8492a8); font-weight: 700; }
.candidate { border: 1px solid var(--app-border, #e0e7f1); border-radius: 8px; padding: 18px; background: #fff; min-width: 0; }
.candidate.recommended { border-color: var(--app-accent, #4169e1); box-shadow: inset 0 0 0 1px var(--app-accent, #4169e1); }
.candidate.selected { border-color: #67c23a; box-shadow: inset 0 0 0 1px #67c23a; }
.candidate-heading { justify-content: space-between; color: var(--app-text-secondary, #64748b); font-size: calc(13px * var(--app-font-scale, 1)); gap: 8px; }
.recommend-badge, .selected-badge { padding: 2px 8px; border-radius: 10px; color: #fff; background: var(--app-accent, #4169e1); }
.selected-badge { background: #67c23a; }
.candidate-title { margin-top: 14px; font-size: calc(17px * var(--app-font-scale, 1)); font-weight: 600; color: var(--app-text, #25354b); line-height: 1.5; }
.original-title { margin-top: 8px; color: var(--app-text-secondary, #64748b); font-size: calc(13px * var(--app-font-scale, 1)); word-break: break-all; }
.candidate-facts { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin: 16px 0; }
.candidate-facts div { padding: 8px; background: var(--app-surface-muted, #f6f8fc); border-radius: 6px; text-align: center; }
.candidate-facts dt { color: var(--app-text-muted, #8492a8); font-size: calc(12px * var(--app-font-scale, 1)); }
.candidate-facts dd { margin: 4px 0 0; color: var(--app-text, #25354b); font-weight: 600; }
.candidate-tags { display: flex; flex-wrap: wrap; gap: 6px; min-height: 24px; }
.mini-tag { padding: 3px 7px; border-radius: 4px; background: var(--app-surface-muted, #f6f8fc); color: var(--app-text-secondary, #64748b); font-size: calc(12px * var(--app-font-scale, 1)); }
.gallery-link { display: inline-block; margin-top: 14px; color: var(--app-accent, #4169e1); text-decoration: none; }
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
  .visual-summary { grid-template-columns: repeat(2, 1fr); }
}
</style>
