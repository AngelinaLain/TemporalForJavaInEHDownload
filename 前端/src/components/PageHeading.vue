<template>
  <header class="app-page-heading">
    <div class="heading-copy">
      <p v-if="eyebrow" class="heading-eyebrow">{{ eyebrow }}</p>
      <h1>{{ title }}</h1>
      <p v-if="description" class="heading-description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="heading-actions"><slot name="actions" /></div>
  </header>
</template>

<script setup>
defineProps({ title: { type: String, required: true }, eyebrow: { type: String, default: '' }, description: { type: String, default: '' } })
</script>

<style scoped>
.app-page-heading { display: flex; justify-content: space-between; align-items: center; gap: 20px; margin: 5px 0 22px; color: var(--app-text, #25354b); }
.heading-copy { min-width: 0; }
.heading-eyebrow { margin: 0 0 9px; color: var(--app-accent, #4169e1); font-size: calc(10px * var(--app-font-scale, 1)); font-weight: 700; letter-spacing: .16em; }
h1 { margin: 0; font-size: calc(27px * var(--app-font-scale, 1)); line-height: 1.35; letter-spacing: -.03em; font-weight: 700; }
.heading-description { margin: 9px 0 0; font-size: calc(12px * var(--app-font-scale, 1)); line-height: 1.8; color: var(--app-text-muted, #8492a8); }
.heading-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; flex-wrap: wrap; max-width: 100%; min-width: 0; }
.heading-actions > :deep(*) { max-width: 100%; min-width: 0; }
.heading-actions :deep(.el-button + .el-button) { margin-left: 0; }
@media (max-width: 1000px) { .app-page-heading { align-items: flex-start; flex-wrap: wrap; } h1 { font-size: calc(23px * var(--app-font-scale, 1)); } }
</style>
