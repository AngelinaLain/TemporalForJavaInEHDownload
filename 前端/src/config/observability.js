/* 集中配置监控服务地址，默认使用当前主机，也支持构建时指定独立地址。 */
// 未指定地址时，按当前访问主机和监控端口生成链接。
// 需要独立域名或代理子路径时，可通过对应的 VITE_* 构建变量覆盖。
const hostname = typeof window !== 'undefined' ? window.location.hostname : '10.10.10.161'

const grafana = (import.meta.env.VITE_GRAFANA_URL || `http://${hostname}:3000`).replace(/\/$/, '')
const dashboard = import.meta.env.VITE_GRAFANA_DASHBOARD_URL ||
  `${grafana}/d/galleryimport-monitoring/galleryimport-monitoring?orgId=1&refresh=30s`

export const OBSERVABILITY = Object.freeze({
  // Grafana 根地址
  grafanaUrl: grafana,
  // 预置的 GalleryImport 大盘。可单独覆盖，兼容独立域名或反向代理子路径。
  grafanaDashboardUrl: dashboard,
  // 内嵌页面隐藏监控系统外围导航，直接显示大盘内容。
  grafanaDashboardEmbedUrl: `${dashboard}${dashboard.includes('?') ? '&' : '?'}kiosk`,
  // Prometheus 指标查询界面
  prometheusUrl: import.meta.env.VITE_PROMETHEUS_URL || `http://${hostname}:9090`,
  // Zipkin 链路追踪页面
  zipkinUrl: import.meta.env.VITE_ZIPKIN_URL || `http://${hostname}:9411`,
  // Loki 日志通过 Grafana 的查询页面查看。
  grafanaExploreUrl: `${grafana}/explore`
})
