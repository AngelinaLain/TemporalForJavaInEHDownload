<template>
  <div class="monitoring-page">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="可观测性栈需通过 `docker compose --profile observability up -d` 启用（Grafana / Prometheus / Zipkin / Loki）。日志（Loki）通过 Grafana Explore 查看。"
      class="tip"
    />

    <el-tabs v-model="activeTab" class="monitoring-tabs">
      <el-tab-pane label="Grafana 监控大盘" name="grafana">
        <toolbar :title="'Grafana'" :url="OBSERVABILITY.grafanaUrl" />
        <iframe :src="OBSERVABILITY.grafanaUrl" class="monitor-frame" />
      </el-tab-pane>

      <el-tab-pane label="Prometheus 指标" name="prometheus">
        <toolbar :title="'Prometheus'" :url="OBSERVABILITY.prometheusUrl" />
        <iframe :src="OBSERVABILITY.prometheusUrl" class="monitor-frame" />
      </el-tab-pane>

      <el-tab-pane label="Zipkin 链路追踪" name="zipkin">
        <toolbar :title="'Zipkin'" :url="OBSERVABILITY.zipkinUrl" />
        <iframe :src="OBSERVABILITY.zipkinUrl" class="monitor-frame" />
      </el-tab-pane>

      <el-tab-pane label="日志 (Loki)" name="loki">
        <toolbar :title="'Grafana Explore (Loki)'" :url="OBSERVABILITY.grafanaExploreUrl" />
        <iframe :src="OBSERVABILITY.grafanaExploreUrl" class="monitor-frame" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, h } from 'vue'
import { ElButton, ElSpace } from 'element-plus'
import { TopRight } from '@element-plus/icons-vue'
import { OBSERVABILITY } from '../config/observability'

const activeTab = ref('grafana')

// 顶部工具条：标题 + 「在新窗口打开」按钮
const toolbar = (props) =>
  h('div', { class: 'toolbar' }, [
    h('span', { class: 'toolbar-title' }, props.title),
    h(
      ElSpace,
      {},
      {
        default: () =>
          h(
            ElButton,
            {
              icon: TopRight,
              onClick: () => window.open(props.url, '_blank', 'noopener')
            },
            () => '在新窗口打开'
          )
      }
    )
  ])
</script>

<style scoped>
.monitoring-page {
  padding: 8px;
  display: flex;
  flex-direction: column;
  height: 100%;
}

.tip {
  margin-bottom: 12px;
}

.monitoring-tabs {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.monitoring-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
}

.monitoring-tabs :deep(.el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0 10px;
}

.toolbar-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.monitor-frame {
  flex: 1;
  width: 100%;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
  min-height: 480px;
}
</style>
