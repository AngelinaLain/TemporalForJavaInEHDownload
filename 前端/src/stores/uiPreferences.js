import { defineStore } from 'pinia'

const STORAGE_KEY = 'gallery-ui-preferences-v1'
export const FONT_PRESETS = Object.freeze([
  { value: .9, label: '小号', description: '更紧凑 · 90%' },
  { value: 1, label: '标准', description: '默认大小 · 100%' },
  { value: 1.15, label: '大号', description: '更易阅读 · 115%' },
  { value: 1.3, label: '特大', description: '舒适阅读 · 130%' }
])

function validScale(value) {
  const scale = Number(value)
  return FONT_PRESETS.some(preset => preset.value === scale) ? scale : 1
}

function readPreferences() {
  try { return validScale(JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}').fontScale) }
  catch { return 1 }
}

function applyScale(value) {
  const scale = validScale(value)
  document.documentElement.style.setProperty('--app-font-scale', String(scale))
  document.documentElement.dataset.fontScale = String(scale)
  window.dispatchEvent(new CustomEvent('app-font-scale-change', { detail: { scale } }))
  return scale
}

/** Called before mount, so returning to the app does not flash the default font size. */
export function initializeUiPreferences() { return applyScale(readPreferences()) }

export const useUiPreferences = defineStore('uiPreferences', {
  state: () => ({ fontScale: readPreferences() }),
  actions: {
    setFontScale(value) {
      this.fontScale = applyScale(value)
      try { localStorage.setItem(STORAGE_KEY, JSON.stringify({ fontScale: this.fontScale })) }
      catch { /* The current session still works when browser storage is unavailable. */ }
    },
    restore() { this.fontScale = applyScale(readPreferences()) }
  }
})
