// 可观测性服务地址配置
// 默认按当前访问主机名 + 端口推导（docker-compose 中已暴露 3000/9090/9411）。
// 也可通过构建期环境变量 VITE_GRAFANA_URL / VITE_GRAFANA_DASHBOARD_URL /
// VITE_PROMETHEUS_URL / VITE_ZIPKIN_URL 覆盖，
// 例如指向独立域名或反向代理子路径。
const hostname = typeof window !== 'undefined' ? window.location.hostname : '10.10.10.161'

const grafana = (import.meta.env.VITE_GRAFANA_URL || `http://${hostname}:3000`).replace(/\/$/, '')
const dashboard = import.meta.env.VITE_GRAFANA_DASHBOARD_URL ||
  `${grafana}/d/galleryimport-monitoring/galleryimport-monitoring?orgId=1&refresh=30s`

export const OBSERVABILITY = Object.freeze({
  // Grafana 根地址
  grafanaUrl: grafana,
  // 预置的 GalleryImport 大盘。可单独覆盖，兼容独立域名或反向代理子路径。
  grafanaDashboardUrl: dashboard,
  // iframe 使用 kiosk 模式，Monitoring 页面打开时直接呈现大盘内容。
  grafanaDashboardEmbedUrl: `${dashboard}${dashboard.includes('?') ? '&' : '?'}kiosk`,
  // Prometheus 指标查询界面
  prometheusUrl: import.meta.env.VITE_PROMETHEUS_URL || `http://${hostname}:9090`,
  // Zipkin 链路追踪 UI
  zipkinUrl: import.meta.env.VITE_ZIPKIN_URL || `http://${hostname}:9411`,
  // 日志（Loki 无自带 UI，通过 Grafana Explore 查看）
  grafanaExploreUrl: `${grafana}/explore`
})
