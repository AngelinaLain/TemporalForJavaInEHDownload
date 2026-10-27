import api from '../api'

const MAX_CONCURRENT = 4
let active = 0
const queue = []
const canceled = () => Object.assign(new Error('图片加载已取消'), { code: 'ERR_CANCELED' })

function drain() {
  while (active < MAX_CONCURRENT && queue.length) {
    const task = queue.shift()
    task.signal?.removeEventListener('abort', task.cancel)
    if (task.signal?.aborted) { task.reject(canceled()); continue }
    active++
    Promise.resolve().then(task.run).then(task.resolve, task.reject).finally(() => { active--; drain() })
  }
}

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

/** Media URLs stay on our authenticated API; never attach a token to an external image URL. */
function apiPath(value) {
  const url = new URL(value, window.location.origin)
  if (url.origin !== window.location.origin || !url.pathname.startsWith('/api/dashboard/gallery-media/')) {
    throw new Error('不支持的图片地址')
  }
  return url.pathname.slice(4) + url.search
}

export function fetchGalleryBlob(url, signal) {
  return enqueue(async () => {
    const response = await api.get(apiPath(url), { responseType: 'blob', signal, silent: true, preserveSession: true })
    if (!(response.data instanceof Blob) || !response.data.type.startsWith('image/')) throw new Error('图片暂时不可用')
    return response.data
  }, signal)
}

/** A bounded, view-owned cover cache. Its object URLs are revoked on eviction and unmount. */
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
