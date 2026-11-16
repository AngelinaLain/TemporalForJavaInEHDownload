package com.checker.temporalServices.activities;
import io.temporal.activity.ActivityInterface;
@ActivityInterface
public interface DownloadPermitActivity {
    String acquireDownloadPermit(Long gid);
    void releaseDownloadPermit(Long gid);
}
