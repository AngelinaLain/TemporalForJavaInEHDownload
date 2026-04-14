import { defineStore } from 'pinia'
import api from '../api'

/**
 * 命名空间 UI 信息：标签搜索下拉中显示的简短标签和颜色。
 * Operations.vue 直接引用此常量，消除重复定义。
 */
export const NS_INFO = {
  female:    { label: '女',    color: '#ad0024' },
  male:      { label: '男',    color: '#0044bb' },
  parody:    { label: '原作',  color: '#6600aa' },
  character: { label: '角色',  color: '#b08000' },
  group:     { label: '团队',  color: '#007a00' },
  artist:    { label: '绘师',  color: '#bb1100' },
  cosplayer: { label: 'Cos',   color: '#555555' },
  mixed:     { label: '混合',  color: '#888888' },
  other:     { label: '其他',  color: '#777777' },
  reclass:   { label: '重分类',color: '#006699' },
  language:  { label: '语言',  color: '#444444' },
}

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
    },
    /** 返回命名空间的 UI 展示信息（简短标签 + 颜色），供搜索下拉使用 */
    getNsInfo(ns) {
      return NS_INFO[ns?.toLowerCase()] || { label: ns || 'other', color: '#888888' }
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
