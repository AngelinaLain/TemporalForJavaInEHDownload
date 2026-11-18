/* 保存当前浏览器的字号偏好，并通知页面图表同步调整文字大小。 */
import { defineStore } from 'pinia'

const STORAGE_KEY = 'gallery-ui-preferences-v1'
export const FONT_PRESETS = Object.freeze([
  { value: .9, label: '小号', description: '更紧凑 · 90%' },
  { value: 1, label: '标准', description: '默认大小 · 100%' },
  { value: 1.15, label: '大号', description: '更易阅读 · 115%' },
  { value: 1.3, label: '特大', description: '舒适阅读 · 130%' }
])

// 只接受预设字号，损坏或未知的偏好回到标准大小。
function validScale(value) {
  const scale = Number(value)
  return FONT_PRESETS.some(preset => preset.value === scale) ? scale : 1
}

// 从当前浏览器读取偏好，无法读取或解析时使用默认字号。
function readPreferences() {
  try { return validScale(JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}').fontScale) }
  catch { return 1 }
}

// 更新全局字号变量，并通知图表和画布重绘文字。
function applyScale(value) {
  const scale = validScale(value)
  document.documentElement.style.setProperty('--app-font-scale', String(scale))
  document.documentElement.dataset.fontScale = String(scale)
  window.dispatchEvent(new CustomEvent('app-font-scale-change', { detail: { scale } }))
  return scale
}

/** 页面挂载前恢复字号，避免打开页面时先显示默认字号再跳变。 */
export function initializeUiPreferences() { return applyScale(readPreferences()) }

export const useUiPreferences = defineStore('uiPreferences', {
  state: () => ({ fontScale: readPreferences() }),
  actions: {
    setFontScale(value) {
      this.fontScale = applyScale(value)
      try { localStorage.setItem(STORAGE_KEY, JSON.stringify({ fontScale: this.fontScale })) }
      catch { /* 浏览器禁止保存偏好时，当前页面仍按所选字号显示。 */ }
    },
    restore() { this.fontScale = applyScale(readPreferences()) }
  }
})
