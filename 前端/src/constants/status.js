export const STATUS_OPTIONS = Object.freeze([
  { value: 'PENDING', label: '未下载', type: 'info', color: '#909399' },
  { value: 'DOWNLOADING', label: '下载中', type: 'primary', color: '#409EFF' },
  { value: 'DOWNLOADED', label: '已下载', type: 'success', color: '#67C23A' },
  { value: 'WAITING_KOMGA', label: '等待 Komga', type: 'primary', color: '#409EFF' },
  { value: 'PARTIAL', label: '不完整', type: 'warning', color: '#E6A23C' },
  { value: 'DOWNLOAD_FAILED', label: '下载失败', type: 'danger', color: '#F56C6C' },
  { value: 'KOMGA_IMPORT_FAILED', label: 'Komga 入库失败', type: 'danger', color: '#F56C6C' },
  { value: 'IMPORTED', label: '已入库', type: 'warning', color: '#E6A23C' },
  { value: 'REVIEW_REQUIRED', label: '待去重审核', type: 'warning', color: '#D97706' },
  { value: 'BLOCKED', label: '阻断', type: 'danger', color: '#F56C6C' },
  { value: 'IGNORED', label: '已忽略', type: 'info', color: '#C0C4CC' }
])

const statusByValue = Object.fromEntries(STATUS_OPTIONS.map(status => [status.value, status]))
const valueByLegacyLabel = Object.fromEntries(STATUS_OPTIONS.map(status => [status.label, status.value]))

export const normalizeStatusValue = value => valueByLegacyLabel[value] || value

export const getStatusMeta = value =>
  statusByValue[normalizeStatusValue(value)] || {
    value,
    label: value || '-',
    type: 'info',
    color: '#909399'
  }
