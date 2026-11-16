package com.checker.service;

import com.checker.config.EhWorkflowConfig;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.Optional;

/** Database-backed global limit shared by RSS, manual searches and retries across all workers. */
@Slf4j
@Service
public class DownloadPermitService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final EhWorkflowConfig config;
    private final WorkflowClient workflows;
    public DownloadPermitService(JdbcTemplate db, PlatformTransactionManager manager, EhWorkflowConfig config, WorkflowClient workflows) {
        this.db=db; this.tx=new TransactionTemplate(manager); this.config=config; this.workflows=workflows;
    }
    public String acquire(Long gid,String workflow,String run) {
        return tx.execute(status -> {
            db.queryForObject("SELECT id FROM eh_download_permit_lock WHERE id=1 FOR UPDATE",Integer.class);
            var state = db.query("SELECT download_status FROM eh_galleries WHERE gid=?",(rs,n)->rs.getString(1),gid);
            if (!state.isEmpty() && ("IMPORTED".equals(state.get(0)) || "已入库".equals(state.get(0)))) return "FINISHED";
            db.update("INSERT IGNORE INTO eh_download_permit(gid,workflow_id,run_id,active,created_at) VALUES(?,?,?,FALSE,?)",gid,workflow,run,System.currentTimeMillis());
            var owners = db.query("SELECT workflow_id,run_id,active FROM eh_download_permit WHERE gid=?",(rs,n)->new Owner(gid,rs.getString(1),rs.getString(2),rs.getBoolean(3)),gid);
            if (owners.isEmpty()) return "BUSY";
            Owner owner=owners.get(0);
            if (!owner.workflow().equals(workflow) || !owner.run().equals(run)) return "BUSY";
            if (owner.active()) return "ACQUIRED";
            if (db.queryForObject("SELECT COUNT(*) FROM eh_download_permit WHERE active=TRUE",Long.class) >= Math.max(1,config.getMaxConcurrency())) return "BUSY";
            db.update("UPDATE eh_download_permit SET active=TRUE WHERE gid=? AND workflow_id=? AND run_id=?",gid,workflow,run);
            return "ACQUIRED";
        });
    }
    public void release(Long gid,String workflow,String run) {
        db.update("DELETE FROM eh_download_permit WHERE gid=? AND workflow_id=? AND run_id=?",gid,workflow,run);
    }
    public boolean hasOwner(long gid) { return db.queryForObject("SELECT COUNT(*) FROM eh_download_permit WHERE gid=?",Long.class,gid)>0; }
    record Owner(long gid,String workflow,String run,boolean active) {}

    @Scheduled(fixedDelayString="${rss.permit-recovery-ms:60000}",initialDelay=60000)
    public void recoverClosedOwners() {
        var owners=db.query("SELECT gid,workflow_id,run_id,active FROM eh_download_permit ORDER BY created_at LIMIT 200",(rs,n)->new Owner(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getBoolean(4)));
        for (Owner owner:owners) {
            try {
                var execution=workflows.newUntypedWorkflowStub(owner.workflow(),Optional.of(owner.run()),Optional.empty()).describe();
                if (execution.getStatus()==WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING || execution.getCloseTime()==null
                        || execution.getCloseTime().isAfter(Instant.now().minusSeconds(360))) continue;
                tx.executeWithoutResult(status -> {
                    db.queryForObject("SELECT id FROM eh_download_permit_lock WHERE id=1 FOR UPDATE",Integer.class);
                    int removed=db.update("DELETE FROM eh_download_permit WHERE gid=? AND workflow_id=? AND run_id=?",owner.gid(),owner.workflow(),owner.run());
                    if (removed==1) db.update("UPDATE eh_galleries SET download_status='下载失败' WHERE gid=? AND download_status IN ('DOWNLOADING','下载中')",owner.gid());
                });
            } catch (Exception e) {
                // An RPC failure or missing retained history is not proof that a downloader stopped.
                log.warn("无法确认下载许可持有者状态，保留许可 gid={}: {}",owner.gid(),e.getClass().getSimpleName());
            }
        }
    }
}
