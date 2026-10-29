<template>
  <section id="dedupe-settings" class="dedupe-settings" aria-labelledby="dedupe-settings-title">
    <div class="settings-heading">
      <div class="heading-copy">
        <p class="settings-eyebrow">去重规则</p>
        <h2 id="dedupe-settings-title">去重参数</h2>
        <p>调整作品判定和视觉证据的严格程度，保存后全系统生效。</p>
      </div>
      <div class="heading-actions">
        <span class="settings-state" :class="{ changed: dirty }" role="status">{{ stateLabel }}</span>
        <el-button :aria-expanded="expanded" aria-controls="dedupe-settings-content" @click="expanded = !expanded">{{ expanded ? '收起参数' : '展开参数' }}</el-button>
      </div>
    </div>

    <p v-if="loaded && !expanded" class="settings-summary">人工审核起点 {{ saved.reviewScore }} 分 · 自动同作起点 {{ saved.autoMatchScore }} 分 · 标题相似度起点 {{ saved.titleSimilarityPercent }}%</p>
    <p v-if="error" class="settings-error" role="alert">{{ error }} <el-button link type="primary" :disabled="busy" @click="loadSettings">重新读取</el-button></p>

    <div v-show="expanded" id="dedupe-settings-content" class="settings-content">
      <el-skeleton v-if="loading && !loaded" :rows="5" animated />
      <template v-else-if="loaded">
        <div class="settings-notice">
          <p>仅影响后续比较和复核，不会自动重分历史画廊，也不会覆盖人工结论。</p>
          <p>视觉参数使用已有指纹即可重新比较，不必重算图片指纹；视觉结果只提供审核证据，不会单独触发自动合并。AI 向量和模型参数仍在 <router-link to="/ai-settings">AI 设置</router-link> 中调整。</p>
        </div>
        <form @submit.prevent="saveSettings">
          <fieldset v-for="group in groups" :key="group.title" :disabled="busy">
            <legend>{{ group.title }}</legend>
            <p class="group-description">{{ group.description }}</p>
            <div class="parameter-grid">
              <div v-for="field in group.fields" :key="field.key" class="parameter-field" :class="{ modified: draft[field.key] !== saved[field.key] }">
                <label :for="`dedupe-${field.key}`">{{ field.label }} <span>{{ field.unit }}</span></label>
                <el-input-number :id="`dedupe-${field.key}`" v-model="draft[field.key]" :min="field.min" :max="field.max" :step="1" :precision="0" step-strictly :disabled="busy" :aria-label="field.label" :aria-describedby="`dedupe-help-${field.key}`" />
                <p class="parameter-values">已保存 {{ saved[field.key] }}{{ field.unit }} · 默认 {{ defaults[field.key] }}{{ field.unit }}<span v-if="draft[field.key] !== saved[field.key]"> · 未保存</span></p>
                <p :id="`dedupe-help-${field.key}`" class="parameter-help">{{ field.help }}</p>
              </div>
            </div>
          </fieldset>
          <p v-if="validationError" class="validation-error" role="alert">{{ validationError }}</p>
          <div class="settings-footer">
            <p>{{ dirty ? '参数尚未保存，当前仍使用已保存的值。' : '当前显示的是全系统已保存的配置。' }}</p>
            <div class="settings-actions">
              <el-button :disabled="busy || !dirty" @click="restoreSaved">放弃修改</el-button>
              <el-button :disabled="busy || isDefaultDraft" @click="restoreDefaults">填入默认值</el-button>
              <el-button type="primary" native-type="submit" :loading="saving" :disabled="busy || !dirty || Boolean(validationError)">保存参数</el-button>
            </div>
          </div>
          <p class="default-hint">“填入默认值”只修改此表单，点击“保存参数”后才会生效。</p>
        </form>
      </template>
      <el-empty v-else description="尚未取得当前配置，读取成功后才能修改和保存。" :image-size="64" />
    </div>
  </section>
</template>

<script setup>
// 展示全局去重配置，先读取已保存值，再校验并提交用户修改，避免读取失败时用默认值覆盖实际配置。
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const groups = [
  {
    title: '作品判定',
    description: '先按标题和标签计算综合分数。章节或编号冲突、页数相差至少一倍时，即使分数较高也不能自动判为同作。',
    fields: [
      { key: 'reviewScore', label: '人工审核起点', unit: '分', min: 40, max: 94, help: '综合分达到此值、但未通过自动判断的候选会进入人工审核；高分但章节、编号或篇幅冲突的候选也需要人工确认。调低会发现更多可疑重复并增加审核量，调高会减少审核量。' },
      { key: 'autoMatchScore', label: '自动同作起点', unit: '分', min: 60, max: 100, help: '综合分达到此值、没有人工结论阻止，且章节、编号和篇幅保护均通过时，可自动识别为同一作品。调高更保守，调低更容易自动归组；必须高于人工审核起点。' },
      { key: 'titleSimilarityPercent', label: '标题近似起点', unit: '%', min: 70, max: 100, help: '不同标题的相似度达到此值才获得标题加分。调高会更严格地检查标题差异；调低可容忍更多错字或写法变化，也更容易命中名称相近的作品。' }
    ]
  },
  {
    title: '视觉证据',
    description: '按阅读顺序寻找相似采样页；证据充分且画质差距明显时，才推荐保留的版本。',
    fields: [
      { key: 'visualPageDistance', label: '页面哈希容差', unit: '', min: 0, max: 24, help: '两张采样页的哈希距离不超过此值才算相似，0 表示只接受完全一致的哈希。调高可容忍更多压缩、边框或画面变化；调低可减少相似页面的误匹配。' },
      { key: 'visualMinMatchedPages', label: '最少匹配采样页', unit: '页', min: 2, max: 16, help: '至少匹配这么多张采样页，视觉证据才足以参与质量推荐。调高可降低仅因封面相似就推荐版本的情况；调低更适合短篇，但证据更少。' },
      { key: 'visualMinCoverage', label: '最低采样覆盖率', unit: '%', min: 30, max: 100, help: '匹配页数占采样较少一侧的比例，达到此值才参与质量推荐。调高要求更多内容一致；调低可容忍更多增页、缺页或版本差异。' },
      { key: 'visualQualityDelta', label: '最小质量差', unit: '%', min: 1, max: 30, help: '证据充分后，两侧画质评分差达到此值才推荐更清晰的版本。调高只在差距明显时推荐；调低会更容易给出推荐，细微评分波动也可能影响结果。' }
    ]
  }
]
const fields = groups.flatMap(group => group.fields)
const draft = reactive({}), saved = reactive({}), defaults = reactive({})
const expanded = ref(window.location.hash === '#dedupe-settings'), loaded = ref(false), loading = ref(false), saving = ref(false), error = ref('')
const busy = computed(() => loading.value || saving.value)
const dirty = computed(() => loaded.value && fields.some(field => draft[field.key] !== saved[field.key]))
const isDefaultDraft = computed(() => fields.every(field => draft[field.key] === defaults[field.key]))
const stateLabel = computed(() => loading.value ? '正在读取' : saving.value ? '正在保存' : !loaded.value ? '未读取配置' : dirty.value ? '有未保存修改' : '已保存')
const validationError = computed(() => loaded.value ? validate(draft) : '')
let requestController, disposed = false

// 数值范围和审核先后关系同时校验；接口数据也经过校验，缺字段时停止编辑。
function validate(values) {
  for (const field of fields) {
    const value = values?.[field.key]
    if (!Number.isInteger(value) || value < field.min || value > field.max) return `${field.label}请输入 ${field.min}～${field.max} 之间的整数。`
  }
  if (values.reviewScore >= values.autoMatchScore) return '人工审核起点必须小于自动同作起点，请为人工审核保留分数区间。'
  return ''
}

// 只接收约定字段，防止后端增加的说明字段进入保存请求。
function pickSettings(values) { return Object.fromEntries(fields.map(field => [field.key, values[field.key]])) }
function applyResponse(data) {
  if (validate(data?.settings) || validate(data?.defaults)) throw new Error('配置响应不完整或超出可调整范围，请重新读取。')
  Object.assign(saved, pickSettings(data.settings))
  Object.assign(defaults, pickSettings(data.defaults))
  Object.assign(draft, saved)
  loaded.value = true
}

// 初次读取失败时保留只读状态，让用户重试，禁止直接保存预设默认值。
async function loadSettings() {
  if (busy.value || disposed) return
  const controller = new AbortController()
  requestController = controller; loading.value = true; error.value = ''
  try {
    const response = await api.get('/dedupe-settings', { signal: controller.signal, silent: true })
    if (!disposed) applyResponse(response.data)
  } catch (failure) {
    if (!disposed && failure.code !== 'ERR_CANCELED') {
      loaded.value = false
      error.value = failure.response?.data?.msg || failure.message || '读取去重配置失败，请重试。'
    }
  } finally {
    if (requestController === controller) { loading.value = false; requestController = null }
  }
}

// 提交前复查整数范围；只有服务端确认成功后才更新“已保存值”。响应不确定时要求重读核对。
async function saveSettings() {
  if (busy.value || disposed || !loaded.value || !dirty.value || validationError.value) return
  const controller = new AbortController()
  requestController = controller; saving.value = true; error.value = ''
  try {
    const response = await api.put('/dedupe-settings', pickSettings(draft), { signal: controller.signal, silent: true })
    if (!disposed) { applyResponse(response.data); ElMessage.success('去重参数已保存，后续比较和复核使用新配置') }
  } catch (failure) {
    if (!disposed && failure.code !== 'ERR_CANCELED') {
      loaded.value = false
      error.value = `${failure.response?.data?.msg || failure.message || '保存未确认'} 请重新读取，核对当前配置后再修改。`
    }
  } finally {
    if (requestController === controller) { saving.value = false; requestController = null }
  }
}

function restoreSaved() { if (!busy.value && loaded.value) Object.assign(draft, saved) }
// 恢复默认只回填草稿，用户仍需主动保存才会改变全局行为。
function restoreDefaults() { if (!busy.value && loaded.value) Object.assign(draft, defaults) }
onMounted(() => { void loadSettings() })
onBeforeUnmount(() => { disposed = true; requestController?.abort() })
</script>

<style scoped>
.dedupe-settings { min-width: 0; margin-bottom: 22px; padding: 22px; border: 1px solid var(--app-border, #e0e7f1); border-radius: var(--app-radius, 12px); background: var(--app-surface, #fff); }
.settings-heading, .heading-actions, .settings-footer, .settings-actions { display: flex; align-items: center; gap: 12px; }
.settings-heading { justify-content: space-between; align-items: flex-start; }
.heading-copy { min-width: 0; }.settings-eyebrow { margin: 0 0 6px; color: var(--app-accent); font-size: calc(10px * var(--app-font-scale, 1)); font-weight: 600; }
h2 { margin: 0; color: var(--app-text); font-size: calc(17px * var(--app-font-scale, 1)); line-height: 1.5; }
.heading-copy > p:last-child, .settings-summary { margin: 8px 0 0; font-size: calc(12px * var(--app-font-scale, 1)); line-height: 1.8; color: var(--app-text-muted); }
.heading-actions { flex-shrink: 0; flex-wrap: wrap; justify-content: flex-end; }.settings-state { color: var(--app-text-muted); font-size: calc(11px * var(--app-font-scale, 1)); }.settings-state.changed { color: #b27d32; }
.settings-summary { margin-top: 15px; }.settings-content { margin-top: 20px; padding-top: 18px; border-top: 1px solid var(--app-border, #e0e7f1); }
.settings-notice { padding: 12px 14px; border-radius: 8px; background: var(--app-surface-muted, #f7f9fc); color: var(--app-text-secondary); font-size: calc(12px * var(--app-font-scale, 1)); line-height: 1.8; }.settings-notice p { margin: 0; }.settings-notice p + p { margin-top: 5px; }.settings-notice a { color: var(--app-accent); }
fieldset { min-width: 0; border: 0; margin: 24px 0 0; padding: 0; }legend { color: var(--app-text); font-size: calc(14px * var(--app-font-scale, 1)); font-weight: 600; padding: 0; }.group-description { color: var(--app-text-muted); font-size: calc(11px * var(--app-font-scale, 1)); line-height: 1.8; margin: 6px 0 14px; }
.parameter-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }.parameter-field { min-width: 0; border: 1px solid var(--app-border, #e0e7f1); padding: 16px; border-radius: 8px; }.parameter-field.modified { border-color: #dcb97d; }
.parameter-field label { display: block; color: var(--app-text-secondary); font-size: calc(12px * var(--app-font-scale, 1)); line-height: 1.7; margin-bottom: 10px; }.parameter-field label > span { color: var(--app-text-muted); font-weight: 400; margin-left: 5px; }.parameter-field :deep(.el-input-number) { width: 100%; max-width: 240px; }
.parameter-values { color: var(--app-text-muted); font-size: calc(10px * var(--app-font-scale, 1)); margin: 9px 0 7px; line-height: 1.8; }.parameter-values > span { color: #b27d32; }.parameter-help { color: var(--app-text-secondary); font-size: calc(11px * var(--app-font-scale, 1)); line-height: 1.9; margin: 0; overflow-wrap: anywhere; }
.settings-footer { justify-content: space-between; margin-top: 22px; flex-wrap: wrap; }.settings-footer > p, .default-hint { color: var(--app-text-muted); font-size: calc(11px * var(--app-font-scale, 1)); line-height: 1.8; margin: 0; }.settings-actions { flex-wrap: wrap; gap: 8px; }.settings-actions :deep(.el-button + .el-button) { margin-left: 0; }.default-hint { margin-top: 10px; }
.settings-error, .validation-error { padding: 10px 12px; background: #fff6f2; color: #a75f47; border-radius: 6px; font-size: calc(12px * var(--app-font-scale, 1)); line-height: 1.8; overflow-wrap: anywhere; }.settings-error { margin: 16px 0 0; }.settings-error :deep(.el-button) { height: auto; padding: 0; font-size: inherit; }.validation-error { margin: 18px 0 0; }
@media (max-width: 1100px) { .parameter-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 650px) { .dedupe-settings { padding: 16px; }.settings-heading { flex-wrap: wrap; gap: 12px; }.heading-copy { width: 100%; }.heading-actions { justify-content: flex-start; }.parameter-grid { grid-template-columns: minmax(0, 1fr); }.parameter-field { padding: 14px; }.settings-actions { width: 100%; }.settings-actions :deep(.el-button) { max-width: 100%; }.settings-footer { align-items: flex-start; } }
</style>
