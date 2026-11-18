package com.checker.service.activity.workflow;
import io.temporal.activity.ActivityInterface;
@ActivityInterface
public interface DownloadPermitActivity {
    String acquireDownloadPermit(Long gid);
    void releaseDownloadPermit(Long gid);
}
