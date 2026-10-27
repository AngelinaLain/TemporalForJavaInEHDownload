/* 共享标签翻译和说明缓存，让列表、搜索和详情使用相同的标签信息。 */
import { defineStore } from 'pinia'
import api from '../api'

/**
 * 给标签分类统一设置简短名称和颜色，供各页面的搜索下拉共用。
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
    /** @type {Record<string, {name: string, intro: string}>} 标签中文名和说明的本地缓存 */
    detailCache: {},
    loaded: false,
    loading: false
  }),
  actions: {
    // 翻译表只加载一次；同时进入多个页面时共用正在进行的请求状态。
    async loadTranslations() {
      if (this.loaded || this.loading) return
      this.loading = true
      try {
        const res = await api.get('/dashboard/tag-translations')
        this.translations = res.data || {}
        this.loaded = true
      } catch {
        // 加载失败时继续显示原标签，用户仍能搜索和浏览。
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

      // 优先查完整标签，找到后补上分类的中文名称。
      if (this.translations[lower]) {
        const colonIdx = lower.indexOf(':')
        if (colonIdx > 0) {
          const ns = lower.substring(0, colonIdx)
          return (NS_MAP[ns] || ns) + ':' + this.translations[lower]
        }
        return this.translations[lower]
      }

      // 没有完整译名时保留原标签内容，只将可识别的分类翻译成中文。
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
    /** 返回标签分类的简短名称和颜色，供搜索下拉展示。 */
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
