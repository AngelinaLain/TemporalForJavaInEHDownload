package com.checker.controllers;

import com.checker.common.Result;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Temporal 流程监控接口：
 * <ul>
 *   <li>{@code GET /workflows}：主流程 + 子流程树；</li>
 *   <li>{@code GET /workflows/{id}/history}：流程历史事件（即运行日志）；</li>
 *   <li>{@code POST /workflows/{id}/terminate}：终止流程。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/temporal/monitor")
@PreAuthorize("hasRole('ADMIN')")
public class TemporalMonitorController {

    /** 拉取最近多少个工作流执行用于构建树 */
    private static final int MAX_WORKFLOWS = 1000;

    @Autowired
    private WorkflowClient workflowClient;

    @GetMapping("/workflows")
    public Result<List<Map<String, Object>>> listWorkflows() {
        List<WorkflowExecutionMetadata> metas;
        try {
            metas = workflowClient.listExecutions("").limit(MAX_WORKFLOWS).toList();
        } catch (Exception e) {
            return Result.error(500, "查询工作流失败: " + e.getMessage());
        }

        Map<String, Map<String, Object>> byWorkflowId = new LinkedHashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();

        for (WorkflowExecutionMetadata meta : metas) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("workflowId", meta.getExecution().getWorkflowId());
            node.put("runId", meta.getExecution().getRunId());
            node.put("type", meta.getWorkflowType());
            node.put("status", meta.getStatus().name());
            node.put("startTime", meta.getStartTime() != null ? meta.getStartTime().toString() : null);
            node.put("closeTime", meta.getCloseTime() != null ? meta.getCloseTime().toString() : null);
            node.put("historyLength", meta.getHistoryLength());
            node.put("children", new ArrayList<Map<String, Object>>());
            byWorkflowId.put(meta.getExecution().getWorkflowId(), node);
        }

        for (WorkflowExecutionMetadata meta : metas) {
            Map<String, Object> node = byWorkflowId.get(meta.getExecution().getWorkflowId());
            if (node == null) {
                continue;
            }
            WorkflowExecution parent = meta.getParentExecution();
            Map<String, Object> parentNode =
                    parent != null ? byWorkflowId.get(parent.getWorkflowId()) : null;
            if (parentNode != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children =
                        (List<Map<String, Object>>) parentNode.get("children");
                children.add(node);
            } else {
                roots.add(node);
            }
        }

        Comparator<Map<String, Object>> byStartTimeDesc = Comparator.comparing(
                node -> (String) node.get("startTime"), Comparator.nullsLast(Comparator.reverseOrder()));
        roots.sort(byStartTimeDesc);
        sortTree(roots, byStartTimeDesc);
        return Result.success(roots);
    }

    private static void sortTree(List<Map<String, Object>> nodes, Comparator<Map<String, Object>> comparator) {
        for (Map<String, Object> node : nodes) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
            children.sort(comparator);
            sortTree(children, comparator);
        }
    }

    @GetMapping("/workflows/{workflowId}/history")
    public Result<List<Map<String, Object>>> getHistory(@PathVariable String workflowId,
                                                        @RequestParam(required = false) String runId) {
        try {
            WorkflowExecutionHistory history = (runId != null && !runId.isBlank())
                    ? workflowClient.fetchHistory(workflowId, runId)
                    : workflowClient.fetchHistory(workflowId);
            Map<Long, HistoryEvent> eventsById = new LinkedHashMap<>();
            for (HistoryEvent event : history.getHistory().getEventsList()) {
                eventsById.put(event.getEventId(), event);
            }
            List<Map<String, Object>> lines = new ArrayList<>();
            for (HistoryEvent event : history.getHistory().getEventsList()) {
                lines.add(describeEvent(event, eventsById));
            }
            return Result.success(lines);
        } catch (Exception e) {
            return Result.error(500, "获取工作流历史失败: " + e.getMessage());
        }
    }

    @PostMapping("/workflows/{workflowId}/terminate")
    public Result<String> terminate(@PathVariable String workflowId,
                                    @RequestParam(required = false) String runId,
                                    @RequestParam(defaultValue = "用户在监控页面手动终止") String reason) {
        try {
            WorkflowStub stub = workflowClient.newUntypedWorkflowStub(
                    workflowId,
                    Optional.ofNullable(runId).filter(r -> !r.isBlank()),
                    Optional.empty());
            stub.terminate(reason);
            return Result.success("终止指令已发送: " + workflowId);
        } catch (Exception e) {
            return Result.error(500, "终止失败: " + e.getMessage());
        }
    }

    /** 将历史事件映射为可读日志行 */
    private Map<String, Object> describeEvent(HistoryEvent event, Map<Long, HistoryEvent> eventsById) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("eventId", event.getEventId());
        line.put("time", event.getEventTime().getSeconds());
        line.put("type", event.getEventType().name());
        line.put("level", levelOf(event));
        line.put("message", buildMessage(event, eventsById));
        return line;
    }

    /**
     * 新版本 proto 中 Started/Completed/Failed/TimedOut 事件不再携带 activityType，
     * 需要根据 scheduledEventId 反查对应的 Scheduled 事件获取活动名称。
     */
    private static String activityTypeName(HistoryEvent event, Map<Long, HistoryEvent> eventsById) {
        long scheduledEventId = 0;
        if (event.hasActivityTaskStartedEventAttributes()) {
            scheduledEventId = event.getActivityTaskStartedEventAttributes().getScheduledEventId();
        } else if (event.hasActivityTaskCompletedEventAttributes()) {
            scheduledEventId = event.getActivityTaskCompletedEventAttributes().getScheduledEventId();
        } else if (event.hasActivityTaskFailedEventAttributes()) {
            scheduledEventId = event.getActivityTaskFailedEventAttributes().getScheduledEventId();
        } else if (event.hasActivityTaskTimedOutEventAttributes()) {
            scheduledEventId = event.getActivityTaskTimedOutEventAttributes().getScheduledEventId();
        }
        HistoryEvent scheduled = eventsById.get(scheduledEventId);
        if (scheduled != null && scheduled.hasActivityTaskScheduledEventAttributes()) {
            return scheduled.getActivityTaskScheduledEventAttributes().getActivityType().getName();
        }
        return "UnknownActivity";
    }

    private static String levelOf(HistoryEvent event) {
        if (event.hasActivityTaskFailedEventAttributes()
                || event.hasWorkflowTaskFailedEventAttributes()
                || event.hasWorkflowExecutionFailedEventAttributes()
                || event.hasChildWorkflowExecutionFailedEventAttributes()
                || event.hasActivityTaskTimedOutEventAttributes()
                || event.hasChildWorkflowExecutionTimedOutEventAttributes()) {
            return "error";
        }
        if (event.hasWorkflowExecutionTerminatedEventAttributes()
                || event.hasChildWorkflowExecutionTerminatedEventAttributes()) {
            return "warn";
        }
        return "info";
    }

    private static String buildMessage(HistoryEvent event, Map<Long, HistoryEvent> eventsById) {
        if (event.hasWorkflowExecutionStartedEventAttributes()) {
            return "🚀 工作流启动";
        }
        if (event.hasWorkflowExecutionCompletedEventAttributes()) {
            return "✅ 工作流完成";
        }
        if (event.hasWorkflowExecutionFailedEventAttributes()) {
            return "❌ 工作流失败: " + shortMessage(event.getWorkflowExecutionFailedEventAttributes().getFailure().getMessage());
        }
        if (event.hasWorkflowExecutionTerminatedEventAttributes()) {
            return "⛔ 工作流已终止: " + event.getWorkflowExecutionTerminatedEventAttributes().getReason();
        }
        if (event.hasWorkflowExecutionTimedOutEventAttributes()) {
            return "⏱️ 工作流超时";
        }
        if (event.hasWorkflowTaskStartedEventAttributes()) {
            return "🧠 工作流任务 #" + event.getWorkflowTaskStartedEventAttributes().getScheduledEventId() + " 开始";
        }
        if (event.hasWorkflowTaskCompletedEventAttributes()) {
            return "🧠 工作流任务 #" + event.getWorkflowTaskCompletedEventAttributes().getScheduledEventId() + " 完成";
        }
        if (event.hasWorkflowTaskFailedEventAttributes()) {
            return "❌ 工作流任务失败: " + shortMessage(event.getWorkflowTaskFailedEventAttributes().getFailure().getMessage());
        }
        if (event.hasActivityTaskScheduledEventAttributes()) {
            return "📅 调度活动 " + event.getActivityTaskScheduledEventAttributes().getActivityType().getName();
        }
        if (event.hasActivityTaskStartedEventAttributes()) {
            return "▶️ 活动开始 " + activityTypeName(event, eventsById)
                    + " (第 " + event.getActivityTaskStartedEventAttributes().getAttempt() + " 次尝试)";
        }
        if (event.hasActivityTaskCompletedEventAttributes()) {
            return "✅ 活动完成 " + activityTypeName(event, eventsById);
        }
        if (event.hasActivityTaskFailedEventAttributes()) {
            return "❌ 活动失败 " + activityTypeName(event, eventsById)
                    + ": " + shortMessage(event.getActivityTaskFailedEventAttributes().getFailure().getMessage());
        }
        if (event.hasActivityTaskTimedOutEventAttributes()) {
            return "⏱️ 活动超时 " + activityTypeName(event, eventsById);
        }
        if (event.hasTimerStartedEventAttributes()) {
            return "⏳ 计时器开始 " + event.getTimerStartedEventAttributes().getStartToFireTimeout().getSeconds() + " 秒";
        }
        if (event.hasTimerFiredEventAttributes()) {
            return "⏰ 计时器触发";
        }
        if (event.hasStartChildWorkflowExecutionInitiatedEventAttributes()) {
            var child = event.getStartChildWorkflowExecutionInitiatedEventAttributes();
            return "👶 启动子流程 " + child.getWorkflowType().getName() + " " + child.getWorkflowId();
        }
        if (event.hasChildWorkflowExecutionStartedEventAttributes()) {
            return "▶️ 子流程开始 " + event.getChildWorkflowExecutionStartedEventAttributes().getWorkflowExecution().getWorkflowId();
        }
        if (event.hasChildWorkflowExecutionCompletedEventAttributes()) {
            return "✅ 子流程完成 " + event.getChildWorkflowExecutionCompletedEventAttributes().getWorkflowExecution().getWorkflowId();
        }
        if (event.hasChildWorkflowExecutionFailedEventAttributes()) {
            return "❌ 子流程失败 " + event.getChildWorkflowExecutionFailedEventAttributes().getWorkflowExecution().getWorkflowId()
                    + ": " + shortMessage(event.getChildWorkflowExecutionFailedEventAttributes().getFailure().getMessage());
        }
        if (event.hasChildWorkflowExecutionTerminatedEventAttributes()) {
            return "⛔ 子流程已终止 " + event.getChildWorkflowExecutionTerminatedEventAttributes().getWorkflowExecution().getWorkflowId();
        }
        if (event.hasMarkerRecordedEventAttributes()) {
            return "📍 标记: " + event.getMarkerRecordedEventAttributes().getMarkerName();
        }
        if (event.hasWorkflowExecutionContinuedAsNewEventAttributes()) {
            return "🔄 工作流 ContinueAsNew";
        }
        return event.getEventType().name();
    }

    private static String shortMessage(String message) {
        if (message == null || message.isBlank()) {
            return "(无详情)";
        }
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }
}
