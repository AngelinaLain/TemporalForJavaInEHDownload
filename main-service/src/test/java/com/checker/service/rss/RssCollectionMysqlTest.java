package com.checker.service.rss;

import com.checker.config.rss.RssConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses only the explicitly selected disposable MySQL database, never application .env. */
@EnabledIfEnvironmentVariable(named = "RSS_TEST_JDBC_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/rss_test.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RssCollectionMysqlTest {
    private JdbcTemplate db;
    private DataSourceTransactionManager manager;
    private RssCollectionArchiveService archives;
    private RssCollectionService service;

    @BeforeAll void migrate() {
        var ds = new DriverManagerDataSource(System.getenv("RSS_TEST_JDBC_URL"), "root", System.getenv("RSS_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach void reset() {
        for (String table : List.of("rss_collection_task", "eh_gallery_collection_items", "eh_gallery_collections",
                "rss_item", "rss_download_task", "rss_batch", "rss_poll_run", "rss_subscription_revision",
                "rss_subscription", "eh_download_permit", "eh_galleries")) db.update("DELETE FROM " + table);
        archives = mock(RssCollectionArchiveService.class);
        var placements = mock(RssPlacementResolver.class);
        when(placements.resolve(anyLong())).thenAnswer(invocation -> {
            long gid = invocation.getArgument(0);
            return new RssPlacementResolver.Decision(gid, gid, RssPlacementResolver.MatchSource.SELF, "测试按订阅画廊归位", null, null);
        });
        service = new RssCollectionService(db, manager, archives, placements, Runnable::run, new RssConfig());
        collection(10, "First");
        collection(20, "Second");
    }

    private void collection(long id, String name) {
        db.update("INSERT INTO eh_gallery_collections(id,name) VALUES(?,?)", id, name);
    }

    private void gallery(long gid, String status) {
        db.update("INSERT INTO eh_galleries(gid,title,filename,download_status) VALUES(?,?,?,?)",
                gid, "Gallery " + gid, "[" + gid + "] archive.cbz", status);
    }

    private void subscription(String id, long collection) {
        db.update("""
                INSERT INTO rss_subscription(id,config_json,baseline_at,next_poll_at,created_at,collection_enabled,collection_id)
                VALUES(?,'{}',0,0,0,TRUE,?)
                """, id, collection);
    }

    private void hit(String id, int revision, long gid, long discovered, boolean eligible) {
        db.update("""
                INSERT INTO rss_item(subscription_id,revision,gid,token,url,title,published_at,discovered_at,eligible)
                VALUES(?,?,?,'abc',?,'Gallery',0,?,?)
                """, id, revision, gid, "https://e-hentai.org/g/" + gid + "/abc/", discovered, eligible);
    }

    private RssCollectionService.Task task(String id, long gid, long collection) {
        return db.queryForObject("SELECT * FROM rss_collection_task WHERE subscription_id=? AND gid=? AND collection_id=?",
                (rs, row) -> new RssCollectionService.Task(rs.getString("subscription_id"), rs.getLong("gid"),
                        rs.getLong("collection_id"), rs.getInt("revision"), rs.getString("status"),
                        rs.getInt("attempts"), rs.getLong("updated_at"), rs.getString("placement_signature"),
                        rs.getObject("placement_gid") == null ? null : rs.getLong("placement_gid"),
                        rs.getString("match_source"),
                        rs.getObject("review_id") == null ? null : rs.getLong("review_id")), id, gid, collection);
    }

    private void makeDue() { db.update("UPDATE rss_collection_task SET next_attempt_at=0"); }

    @RepeatedTest(5) void concurrentFirstAssignmentUsesStableEarliestSubscriptionAndNeverDuplicatesSortOrder() throws Exception {
        subscription("earlier", 10); subscription("later", 20);
        for (long gid : List.of(1L, 2L)) {
            gallery(gid, "下载中"); hit("later", 1, gid, 200, true); hit("earlier", 1, gid, 100, true);
        }
        var pool = Executors.newFixedThreadPool(3);
        try {
            var gate = new CountDownLatch(1);
            var first = pool.submit(() -> { gate.await(); service.assignForGallery(1); return null; });
            var same = pool.submit(() -> { gate.await(); service.assignForGallery(1); return null; });
            var second = pool.submit(() -> { gate.await(); service.assignForGallery(2); return null; });
            gate.countDown();
            first.get(10, TimeUnit.SECONDS); same.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM eh_gallery_collection_items WHERE collection_id=10", Integer.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(DISTINCT sort_order) FROM eh_gallery_collection_items", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM eh_gallery_collection_items WHERE collection_id=20", Integer.class));
        verifyNoInteractions(archives);
    }

    @Test void sameDiscoveryTimeUsesSubscriptionIdAndIgnoresHistoricalOrInactiveHits() {
        subscription("b", 20); subscription("a", 10); subscription("old", 20);
        gallery(1, "未下载"); hit("b", 1, 1, 100, true); hit("a", 1, 1, 100, true); hit("old", 1, 1, 1, true);
        db.update("UPDATE rss_subscription SET revision=2 WHERE id='old'");
        service.assignForGallery(1);
        assertEquals(10, db.queryForObject("SELECT collection_id FROM eh_gallery_collection_items WHERE gid=1", Long.class));
        gallery(2, "未下载"); hit("a", 1, 2, 100, false);
        service.assignForGallery(2);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM eh_gallery_collection_items WHERE gid=2", Integer.class));
    }

    @Test void manualMembershipAndDuplicateVersionsRemainConflictsWithoutMovingFiles() {
        subscription("rss", 10);
        gallery(1, "已入库"); hit("rss", 1, 1, 0, true);
        db.update("INSERT INTO eh_gallery_collection_items(collection_id,gid,sort_order,added_source) VALUES(20,1,7,'MANUAL')");
        gallery(2, "已忽略"); hit("rss", 1, 2, 0, true);
        db.update("UPDATE eh_galleries SET duplicate_of_gid=1 WHERE gid=2");
        service.assignForGallery(1); service.assignForGallery(2); service.runOnce();
        assertEquals("CONFLICT", task("rss", 1, 10).status());
        assertEquals("CONFLICT", task("rss", 2, 10).status());
        assertEquals(20, db.queryForObject("SELECT collection_id FROM eh_gallery_collection_items WHERE gid=1", Long.class));
        assertEquals("MANUAL", db.queryForObject("SELECT added_source FROM eh_gallery_collection_items WHERE gid=1", String.class));
        verifyNoInteractions(archives);
    }

    @Test void importedGalleryIsReusedAndWaitingKomgaResumesAfterServiceRestart() throws Exception {
        subscription("rss", 10); gallery(1, "已入库"); hit("rss", 1, 1, 0, true);
        when(archives.synchronize(1, 10)).thenReturn(false, true);
        service.runOnce();
        assertEquals("WAITING_KOMGA", task("rss", 1, 10).status());
        assertEquals(0, task("rss", 1, 10).attempts());
        var placements = mock(RssPlacementResolver.class);
        when(placements.resolve(anyLong())).thenAnswer(invocation -> {
            long gid = invocation.getArgument(0);
            return new RssPlacementResolver.Decision(gid, gid, RssPlacementResolver.MatchSource.SELF, "测试按订阅画廊归位", null, null);
        });
        service = new RssCollectionService(db, manager, archives, placements, Runnable::run, new RssConfig());
        makeDue(); service.runOnce();
        assertEquals("SYNCED", task("rss", 1, 10).status());
        assertEquals("已入库", db.queryForObject("SELECT download_status FROM eh_galleries WHERE gid=1", String.class));
        assertEquals("[1] archive.cbz", db.queryForObject("SELECT filename FROM eh_galleries WHERE gid=1", String.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM rss_download_task", Integer.class));
        verify(archives, times(2)).synchronize(1, 10);
    }

    @Test void pendingImportAndDownloadPermitBlockArchiveSynchronizationUntilReleased() throws Exception {
        subscription("rss", 10); gallery(1, "下载中"); hit("rss", 1, 1, 0, true);
        service.runOnce(); assertEquals("WAITING_IMPORT", task("rss", 1, 10).status());
        db.update("UPDATE eh_galleries SET download_status='IMPORTED' WHERE gid=1");
        db.update("INSERT INTO eh_download_permit(gid,workflow_id,run_id,active,created_at) VALUES(1,'test','test',TRUE,0)");
        makeDue(); service.runOnce(); assertEquals("WAITING_IMPORT", task("rss", 1, 10).status());
        verifyNoInteractions(archives);
        db.update("DELETE FROM eh_download_permit WHERE gid=1");
        when(archives.synchronize(1, 10)).thenReturn(true);
        makeDue(); service.runOnce(); assertEquals("SYNCED", task("rss", 1, 10).status());
    }

    @Test void failuresUsePersistentBackoffAndStopAfterFiveAttempts() throws Exception {
        subscription("rss", 10); gallery(1, "已入库"); hit("rss", 1, 1, 0, true);
        when(archives.synchronize(1, 10)).thenThrow(new IllegalStateException("password=fixture-secret"));
        for (int attempt = 1; attempt <= 5; attempt++) {
            makeDue(); long before = System.currentTimeMillis(); service.runOnce();
            var task = task("rss", 1, 10);
            assertEquals(attempt, task.attempts());
            assertEquals(attempt == 5 ? "FAILED" : "RETRY", task.status());
            long next = db.queryForObject("SELECT next_attempt_at FROM rss_collection_task", Long.class);
            assertTrue(next >= before + RssCollectionService.retryDelay(attempt));
        }
        makeDue(); service.runOnce();
        verify(archives, times(5)).synchronize(1, 10);
        assertEquals("已入库", db.queryForObject("SELECT download_status FROM eh_galleries WHERE gid=1", String.class));
        assertFalse(db.queryForObject("SELECT last_error FROM rss_collection_task", String.class).contains("fixture-secret"));
    }

    @Test void pauseRevisionAndTargetChangesIgnoreOldJobsUntilCurrentRevisionHitsAgain() throws Exception {
        subscription("rss", 10); gallery(1, "已入库"); hit("rss", 1, 1, 0, true);
        service.enqueue(); var stale = task("rss", 1, 10);
        db.update("UPDATE rss_subscription SET enabled=FALSE WHERE id='rss'");
        service.process(stale); verifyNoInteractions(archives);
        service.runOnce(); assertEquals("CANCELLED", task("rss", 1, 10).status());
        db.update("UPDATE rss_subscription SET enabled=TRUE,revision=2,collection_id=20 WHERE id='rss'");
        service.runOnce(); assertEquals("CANCELLED", task("rss", 1, 10).status());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM rss_collection_task", Integer.class));
        hit("rss", 2, 1, 100, true);
        when(archives.synchronize(1, 20)).thenReturn(true);
        service.runOnce();
        assertEquals("SYNCED", task("rss", 1, 20).status());
        assertEquals(2, task("rss", 1, 20).revision());
        assertEquals("CANCELLED", task("rss", 1, 10).status());
        verify(archives, never()).synchronize(1, 10);
    }

    @Test void sameTargetNewRevisionCanResumeButOldHitAloneCannot() throws Exception {
        subscription("rss", 10); gallery(1, "已入库"); hit("rss", 1, 1, 0, true);
        service.enqueue();
        db.update("UPDATE rss_subscription SET revision=2 WHERE id='rss'");
        service.runOnce(); assertEquals("CANCELLED", task("rss", 1, 10).status());
        verifyNoInteractions(archives);
        hit("rss", 2, 1, 1, true); when(archives.synchronize(1, 10)).thenReturn(true);
        service.runOnce(); assertEquals("SYNCED", task("rss", 1, 10).status());
        assertEquals(2, task("rss", 1, 10).revision());
    }

    @Test void renameResynchronizesSameCollectionButManualMoveOrRemovalIsPreserved() throws Exception {
        subscription("rss", 10); gallery(1, "已入库"); hit("rss", 1, 1, 0, true);
        when(archives.synchronize(1, 10)).thenReturn(true);
        service.runOnce(); assertEquals("SYNCED", task("rss", 1, 10).status());
        db.update("UPDATE eh_gallery_collections SET name='Renamed' WHERE id=10");
        service.runOnce(); assertEquals("10:Renamed:0", task("rss", 1, 10).signature());
        verify(archives, times(2)).synchronize(1, 10);
        db.update("DELETE FROM eh_gallery_collection_items WHERE gid=1");
        service.runOnce(); assertEquals("CONFLICT", task("rss", 1, 10).status());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM eh_gallery_collection_items", Integer.class));
        verify(archives, times(2)).synchronize(1, 10);
    }

    @Test void persistedRunningJobRecoversAndCompletedJobDoesNotRunAgain() throws Exception {
        subscription("rss", 10); gallery(1, "已入库"); hit("rss", 1, 1, 0, true);
        service.assignForGallery(1); service.enqueue();
        db.update("UPDATE rss_collection_task SET status='RUNNING',placement_signature='10:First:0'");
        when(archives.synchronize(1, 10)).thenReturn(true);
        service.runOnce(); service.runOnce();
        assertEquals("SYNCED", task("rss", 1, 10).status());
        verify(archives, times(1)).synchronize(1, 10);
    }
}
