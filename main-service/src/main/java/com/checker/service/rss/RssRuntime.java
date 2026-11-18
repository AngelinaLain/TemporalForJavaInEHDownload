package com.checker.service.rss;

import com.checker.config.rss.RssConfig;
import com.checker.dao.rss.RssStore;
import com.checker.service.workflow.rss.RssPollWorkflow;
import com.checker.service.workflow.rss.RssTickWorkflow;
import com.checker.shared.Constants;
import com.checker.shared.DownloadStatus;
import com.checker.dto.SearchOptions;
import com.checker.entity.EhGalleriesEntity;
import com.checker.dao.catalog.EhGalleriesMapper;
import com.checker.manager.workflow.DownloadPermitService;
import com.checker.service.workflow.EHAutomationWorkflow;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.*;
import io.temporal.client.schedules.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class RssRuntime {
    private final RssStore store;
    private final WorkflowClient client;
    private final RssConfig config;
    private final EhGalleriesMapper galleries;
    private final DownloadPermitService permits;
    private volatile boolean scheduleReady;
    public RssRuntime(RssStore store,WorkflowClient client,RssConfig config,EhGalleriesMapper galleries,DownloadPermitService permits) {
        this.store=store;this.client=client;this.config=config;this.galleries=galleries;this.permits=permits;
    }
    @Scheduled(fixedDelay=60000,initialDelay=15000)
    public void ensureSchedule() {
        if (!config.isEnabled() || scheduleReady) return;
        try {
            ScheduleClient schedules=ScheduleClient.newInstance(client.getWorkflowServiceStubs(),ScheduleClientOptions.newBuilder().setNamespace(client.getOptions().getNamespace()).build());
            Schedule schedule=Schedule.newBuilder().setAction(ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType(RssTickWorkflow.class).setOptions(WorkflowOptions.newBuilder().setWorkflowId("rss-tick").setTaskQueue(Constants.TASK_QUEUE).build()).build())
                    .setSpec(ScheduleSpec.newBuilder().setIntervals(List.of(new ScheduleIntervalSpec(Duration.ofMinutes(1)))).build())
                    .setPolicy(SchedulePolicy.newBuilder().setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP).setCatchupWindow(Duration.ofMinutes(5)).build()).build();
            schedules.createSchedule("gallery-rss-tick",schedule,ScheduleOptions.newBuilder().build());
            scheduleReady=true;
        } catch (ScheduleAlreadyRunningException e) { scheduleReady=true; }
        catch (Exception e) { log.warn("RSS 调度创建失败，将自动重试: {}",e.getMessage()); }
    }
    public void tick() {
        if (!config.isEnabled()) return;
        for (var row:store.due()) {
            try { startPoll((String)row.get("id"),((Number)row.get("revision")).intValue()); }
            catch (Exception e) { log.warn("RSS 轮询启动失败 id={}",row.get("id"),e); }
        }
        store.enqueue();
        for (var batch:store.batches()) {
            try {
                if (batch.status().equals("DISPATCHING")) dispatch(batch);
                else reconcile(batch);
            } catch (WorkflowNotFoundException missingHistory) {
                for (var task:store.tasks(batch)) {
                    var gallery=galleries.selectById(task.gid());
                    if (gallery!=null && has(gallery,DownloadStatus.IMPORTED)) store.taskState(task,"IMPORTED",null);
                    else store.taskState(task,"FAILED","Temporal 历史已不可查询，请核对任务后手动重试");
                }
                store.finishBatch(batch);
            } catch (Exception e) { store.batchError(batch,e.getMessage()); }
        }
        for (var task:store.heldTasks()) reconcileTask(task);
        RssStore.Batch next=store.prepareBatch();
        if (next!=null) dispatch(next);
    }
    public void startPoll(String id,int revision) {
        if (!config.isEnabled() || store.load(id,revision)==null) return;
        var workflow=client.newWorkflowStub(RssPollWorkflow.class,WorkflowOptions.newBuilder().setTaskQueue(Constants.TASK_QUEUE)
                .setWorkflowId("rss-poll-"+id+"-"+revision).build());
        try { WorkflowClient.start(workflow::poll,id,revision); }
        catch (WorkflowExecutionAlreadyStarted ignored) { }
    }
    private void dispatch(RssStore.Batch batch) {
        try {
            // Reject re-use even after completion: retries of a lost start response must attach to the same execution.
            var workflow=client.newWorkflowStub(EHAutomationWorkflow.class,WorkflowOptions.newBuilder().setTaskQueue(Constants.TASK_QUEUE)
                    .setWorkflowId(batch.workflowId()).setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE).build());
            if (System.currentTimeMillis()-batch.createdAt()>86400000L) {
                var known=client.newUntypedWorkflowStub(batch.workflowId()).describe();
                store.started(batch,known.getExecution().getRunId());
                return;
            }
            var execution=WorkflowClient.start(workflow::executeAutomation,SearchOptions.builder().galleryUrls(batch.urls()).build());
            store.started(batch,execution.getRunId());
        } catch (WorkflowExecutionAlreadyStarted existing) { store.started(batch,existing.getExecution().getRunId()); }
        catch (WorkflowNotFoundException expired) {
            for (var task:store.tasks(batch)) store.taskState(task,"FAILED","派发结果未知且超过一天，请核对工作流后手动重试");
            store.finishBatch(batch);
        } catch (Exception e) { store.batchError(batch,e.getMessage()); }
    }
    private void reconcile(RssStore.Batch batch) {
        var execution=client.newUntypedWorkflowStub(batch.workflowId(),Optional.ofNullable(batch.runId()),Optional.empty()).describe();
        if (execution.getStatus()==WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) return;
        // Allow an interrupted activity's heartbeat/cancellation timeout to expire before any replacement is queued.
        if (execution.getCloseTime()!=null && execution.getCloseTime().isAfter(Instant.now().minusSeconds(360))
                && execution.getStatus()!=WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED) return;
        for (var task:store.tasks(batch)) reconcileTask(task);
        store.finishBatch(batch);
    }
    private void reconcileTask(RssStore.Task task) {
        EhGalleriesEntity gallery=galleries.selectById(task.gid());
        if (gallery==null) {store.retryTask(task,"详情抓取未生成画廊记录，等待重新获取");return;}
        if (has(gallery,DownloadStatus.IMPORTED)) {store.taskState(task,"IMPORTED",null);return;}
        if (has(gallery,DownloadStatus.BLOCKED)) {store.taskState(task,"BLOCKED","源站访问被阻断，请先处理凭据或配额");return;}
        if (has(gallery,DownloadStatus.REVIEW_REQUIRED)) {store.taskState(task,"REVIEW_REQUIRED",null);return;}
        if (gallery.getDuplicateOfGid()!=null || has(gallery,DownloadStatus.IGNORED)) {store.taskState(task,"SKIPPED",null);return;}
        if (has(gallery,DownloadStatus.DOWNLOADING)) {
            if (permits.hasOwner(task.gid()) || isRunning("single-gallery-"+task.gid())) {
                store.taskState(task,"WAITING_EXISTING",null);return;
            }
            // A parent may have died between database claim and starting its child. Use CAS to avoid resetting later progress.
            var update=new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<EhGalleriesEntity>();
            update.eq("gid",task.gid()).eq("download_status",gallery.getDownloadStatus()).set("download_status",DownloadStatus.DOWNLOAD_FAILED.getValue());
            galleries.update(null,update);
        }
        if (has(gallery,DownloadStatus.WAITING_KOMGA)) {
            if (isRunning("komga-import-"+task.gid())) {store.taskState(task,"WAITING_EXISTING",null);return;}
            var update=new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<EhGalleriesEntity>();
            update.eq("gid",task.gid()).eq("download_status",gallery.getDownloadStatus()).set("download_status",DownloadStatus.KOMGA_IMPORT_FAILED.getValue());
            galleries.update(null,update);
        }
        store.retryTask(task,"画廊尚未成功入库："+gallery.getDownloadStatus());
    }
    private boolean isRunning(String id) {
        try {return client.newUntypedWorkflowStub(id).describe().getStatus()==WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;}
        catch (WorkflowNotFoundException e) {return false;}
    }
    private static boolean has(EhGalleriesEntity gallery,DownloadStatus status) {return status.name().equals(gallery.getDownloadStatus())||status.getValue().equals(gallery.getDownloadStatus());}
}
