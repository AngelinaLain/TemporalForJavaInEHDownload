/* 管理带认证的图片请求队列和封面缓存，限制并发并及时释放浏览器图片资源。 */
import api from '../api'

const MAX_CONCURRENT = 4
let active = 0
const queue = []
const canceled = () => Object.assign(new Error('图片加载已取消'), { code: 'ERR_CANCELED' })

// 从等待队列中启动图片请求，每次最多保留四个正在进行的任务。
function drain() {
  while (active < MAX_CONCURRENT && queue.length) {
    const task = queue.shift()
    task.signal?.removeEventListener('abort', task.cancel)
    if (task.signal?.aborted) { task.reject(canceled()); continue }
    active++
    // 不论成功、失败还是取消，都归还并发名额，再继续处理后面的图片。
    Promise.resolve().then(task.run).then(task.resolve, task.reject).finally(() => { active--; drain() })
  }
}

// 把图片加入队列；未开始的任务被取消时直接移除，不占用网络连接。
function enqueue(run, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(canceled())
    const task = { run, signal, resolve, reject }
    task.cancel = () => {
      const index = queue.indexOf(task)
      if (index >= 0) queue.splice(index, 1)
      reject(canceled())
    }
    signal?.addEventListener('abort', task.cancel, { once: true })
    queue.push(task)
    drain()
  })
}

/** 只接受本站媒体接口地址，避免把登录凭据带到外部图片地址。 */
function apiPath(value) {
  const url = new URL(value, window.location.origin)
  if (url.origin !== window.location.origin || !url.pathname.startsWith('/api/dashboard/gallery-media/')) {
    throw new Error('不支持的图片地址')
  }
  return url.pathname.slice(4) + url.search
}

// 携带登录凭据取得图片，只接受有效图片响应；单张图片失败不会结束整页会话。
export function fetchGalleryBlob(url, signal) {
  return enqueue(async () => {
    const response = await api.get(apiPath(url), { responseType: 'blob', signal, silent: true, preserveSession: true })
    if (!(response.data instanceof Blob) || !response.data.type.startsWith('image/')) throw new Error('图片暂时不可用')
    return response.data
  }, signal)
}

/** 保存少量近期封面；淘汰或页面卸载时释放临时图片地址，避免内存持续增长。 */
export function createCoverCache(limit = 24) {
  const entries = new Map()
  return {
    get(gid) {
      const entry = entries.get(gid)
      if (!entry) return ''
      if (Date.now() - entry.created > 5 * 60 * 1000) {
        URL.revokeObjectURL(entry.url); entries.delete(gid); return ''
      }
      entries.delete(gid); entries.set(gid, entry)
      // 命中后移到末尾，超出容量时优先释放最久没有使用的封面。
      return entry.url
    },
    set(gid, blob) {
      const previous = entries.get(gid)
      if (previous) URL.revokeObjectURL(previous.url)
      const url = URL.createObjectURL(blob)
      entries.delete(gid); entries.set(gid, { url, created: Date.now() })
      while (entries.size > limit) {
        const first = entries.keys().next().value
        URL.revokeObjectURL(entries.get(first).url); entries.delete(first)
      }
      return url
    },
    clear() { entries.forEach(entry => URL.revokeObjectURL(entry.url)); entries.clear() }
  }
}
