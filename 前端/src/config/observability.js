// 可观测性服务地址配置
// 默认按当前访问主机名 + 端口推导（docker-compose 中已暴露 3000/9090/9411）。
// 也可通过构建期环境变量 VITE_GRAFANA_URL / VITE_PROMETHEUS_URL / VITE_ZIPKIN_URL 覆盖，
// 例如指向独立域名或反向代理子路径。
const hostname = typeof window !== 'undefined' ? window.location.hostname : '10.10.10.161'

const grafana = import.meta.env.VITE_GRAFANA_URL || `http://${hostname}:3000`

export const OBSERVABILITY = Object.freeze({
  // Grafana 监控大盘（嵌入首页，也可通过 VITE_GRAFANA_URL 指向某个具体 dashboard）
  grafanaUrl: grafana,
  // Prometheus 指标查询界面
  prometheusUrl: import.meta.env.VITE_PROMETHEUS_URL || `http://${hostname}:9090`,
  // Zipkin 链路追踪 UI
  zipkinUrl: import.meta.env.VITE_ZIPKIN_URL || `http://${hostname}:9411`,
  // 日志（Loki 无自带 UI，通过 Grafana Explore 查看）
  grafanaExploreUrl: `${grafana.replace(/\/$/, '')}/explore`
})
