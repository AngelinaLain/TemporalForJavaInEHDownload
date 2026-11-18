package com.checker.service.rss;

import com.checker.config.rss.RssConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RssCollectionServiceTest {
    @Test void readinessNeverMovesActiveDownloadsDuplicatesOrExistingManualAssignments() {
        var imported = new RssCollectionService.Gallery("已入库", null);
        assertNull(RssCollectionService.readiness(imported, null, 1, false, null));
        assertNull(RssCollectionService.readiness(new RssCollectionService.Gallery("IMPORTED", null),
                new RssCollectionService.Membership(1, "1:name:0"), 1, false, null));
        assertEquals("WAITING_IMPORT", RssCollectionService.readiness(imported, null, 1, true, null).status());
        assertEquals("WAITING_IMPORT", RssCollectionService.readiness(null, null, 1, false, null).status());
        assertEquals("WAITING_IMPORT", RssCollectionService.readiness(
                new RssCollectionService.Gallery("下载中", null), null, 1, false, null).status());
        assertEquals("CONFLICT", RssCollectionService.readiness(
                new RssCollectionService.Gallery("已入库", 20L), null, 1, false, null).status());
        assertEquals("CONFLICT", RssCollectionService.readiness(imported,
                new RssCollectionService.Membership(2, "2:manual:0"), 1, false, null).status());
        assertEquals("CONFLICT", RssCollectionService.readiness(imported, null, 1, false, "1:removed:0").status());
    }

    @Test void retriesBackOffAndRemoteErrorsDoNotExposeCredentialsOrPaths() {
        assertEquals(60_000, RssCollectionService.retryDelay(1));
        assertEquals(120_000, RssCollectionService.retryDelay(2));
        assertEquals(960_000, RssCollectionService.retryDelay(5));
        assertEquals(3_600_000, RssCollectionService.retryDelay(100));
        String message = RssCollectionService.safeError(new IllegalStateException("https://user:secret@nas/private/archive.cbz"));
        assertFalse(message.contains("secret"));
        assertFalse(message.contains("archive.cbz"));
        assertTrue(message.contains("IllegalStateException"));
    }

    @Test void schedulerQueuesOneWorkerAndCanResumeAfterCompletion() {
        var db = mock(JdbcTemplate.class);
        var archive = mock(RssCollectionArchiveService.class);
        var config = new RssConfig();
        List<Runnable> queued = new ArrayList<>();
        var service = new RssCollectionService(db, mock(PlatformTransactionManager.class), archive, queued::add, config);
        service.schedule();
        service.schedule();
        assertEquals(1, queued.size());
        verifyNoInteractions(db, archive);
        config.setEnabled(false);
        queued.get(0).run();
        config.setEnabled(true);
        service.schedule();
        assertEquals(2, queued.size());
    }

    @Test void rejectedExecutorDoesNotLeaveSchedulerPermanentlyBusy() {
        var executor = mock(TaskExecutor.class);
        doThrow(new RejectedExecutionException()).when(executor).execute(any());
        var db = mock(JdbcTemplate.class);
        var archive = mock(RssCollectionArchiveService.class);
        var config = new RssConfig();
        var service = new RssCollectionService(db, mock(PlatformTransactionManager.class), archive, executor, config);
        assertDoesNotThrow(service::schedule);
        assertDoesNotThrow(service::schedule);
        verify(executor, times(2)).execute(any());
        config.setEnabled(false);
        service.assignForGallery(1);
        verifyNoInteractions(db, archive);
    }
}
