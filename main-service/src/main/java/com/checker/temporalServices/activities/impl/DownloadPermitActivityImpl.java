package com.checker.temporalServices.activities.impl;
import com.checker.common.Constants;
import com.checker.service.DownloadPermitService;
import com.checker.temporalServices.activities.DownloadPermitActivity;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;
@Component
@ActivityImpl(taskQueues=Constants.TASK_QUEUE)
public class DownloadPermitActivityImpl implements DownloadPermitActivity {
    private final DownloadPermitService permits;
    public DownloadPermitActivityImpl(DownloadPermitService permits) { this.permits=permits; }
    public String acquireDownloadPermit(Long gid) {
        var info=Activity.getExecutionContext().getInfo();
        return permits.acquire(gid,info.getWorkflowId(),info.getRunId());
    }
    public void releaseDownloadPermit(Long gid) {
        var info=Activity.getExecutionContext().getInfo();
        permits.release(gid,info.getWorkflowId(),info.getRunId());
    }
}
