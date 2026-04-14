import { defineStore } from 'pinia'
import api from '../api'

export const useTagStore = defineStore('tags', {
  state: () => ({
    /** @type {Record<string, string>} namespace:tag → 中文名 */
    translations: {},
    /** @type {Record<string, {name: string, intro: string}>} tag detail cache */
    detailCache: {},
    loaded: false,
    loading: false
  }),
  actions: {
    async loadTranslations() {
      if (this.loaded || this.loading) return
      this.loading = true
      try {
        const res = await api.get('/dashboard/tag-translations')
        this.translations = res.data || {}
        this.loaded = true
      } catch {
        // 静默失败，标签保持英文显示
      } finally {
        this.loading = false
      }
    },
    /**
     * 获取标签详情（中文名 + 描述），带本地缓存
     */
    async fetchDetail(tag) {
      if (!tag) return { name: '', intro: '' }
      if (this.detailCache[tag]) return this.detailCache[tag]
      try {
        const res = await api.get('/dashboard/tag-detail', { params: { tag } })
        const detail = res.data || {}
        this.detailCache[tag] = { name: detail.name || tag, intro: detail.intro || '' }
        return this.detailCache[tag]
      } catch {
        return { name: tag, intro: '' }
      }
    },
    /**
     * 翻译单个标签，如 "female:stockings" → "女性:丝袜"
     */
    translate(tag) {
      if (!tag) return ''
      const lower = tag.toLowerCase()

      // 直接完整匹配
      if (this.translations[lower]) {
        const colonIdx = lower.indexOf(':')
        if (colonIdx > 0) {
          const ns = lower.substring(0, colonIdx)
          return (NS_MAP[ns] || ns) + ':' + this.translations[lower]
        }
        return this.translations[lower]
      }

      // 尝试 namespace:tag 匹配
      const colonIdx = lower.indexOf(':')
      if (colonIdx > 0) {
        const ns = lower.substring(0, colonIdx)
        const name = this.translations[lower]
        const nsZh = NS_MAP[ns] || ns
        return name ? `${nsZh}:${name}` : `${nsZh}:${lower.substring(colonIdx + 1)}`
      }

      return tag
    },
    translateNs(ns) {
      return NS_MAP[ns?.toLowerCase()] || ns
    }
  }
})

const NS_MAP = {
  reclass: '重新分类',
  female: '女性',
  male: '男性',
  mixed: '混合',
  language: '语言',
  other: '其他',
  group: '团体',
  artist: '艺术家',
  cosplayer: 'Cosplayer',
  parody: '原作',
  character: '角色',
  location: '地点',
  temp: '临时',
  misc: '杂项'
}
