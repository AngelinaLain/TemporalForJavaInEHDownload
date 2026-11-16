package com.checker.rss;

import com.checker.config.EhWorkflowConfig;
import com.checker.dto.RssSearchItem;
import com.checker.service.DownloadPermitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.*;

/** Runs only against an explicitly supplied disposable database, never application .env. */
@EnabledIfEnvironmentVariable(named="RSS_TEST_JDBC_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/rss_test.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RssStoreMysqlTest {
    JdbcTemplate db;
    RssStore store;
    DataSourceTransactionManager manager;
    @BeforeAll void migrate() {
        var ds=new DriverManagerDataSource(System.getenv("RSS_TEST_JDBC_URL"),"root",System.getenv("RSS_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        db=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        store=new RssStore(db,new ObjectMapper(),manager,new RssConfig());
    }
    @BeforeEach void reset() {
        for (String table:List.of("rss_item","rss_download_task","rss_batch","rss_poll_run","rss_subscription_revision","rss_subscription","eh_download_permit","eh_galleries")) db.update("DELETE FROM "+table);
    }
    RssSubscriptionRequest request() { var r=new RssSubscriptionRequest();r.setName("测试订阅");r.setKeyword("test");return r; }
    RssSearchItem item(long gid,long time) {return new RssSearchItem(gid,"abc","https://e-hentai.org/g/"+gid+"/abc/","Test",time);}
    RssStore.Snapshot subscription() {String id=store.save(null,request());return store.load(id,1);}

    @Test void overlappingSubscriptionsAndRepeatedPagesCreateOneDownload() {
        var a=subscription();var b=subscription();var item=item(42,System.currentTimeMillis());
        assertEquals(1,store.record(a,List.of(item)));assertEquals(0,store.record(a,List.of(item)));
        store.record(b,List.of(item));store.enqueue();store.enqueue();
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM rss_item",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rss_download_task",Integer.class));
        var batch=store.prepareBatch();assertNotNull(batch);assertEquals(1,batch.urls().size());
        assertNull(store.prepareBatch());assertEquals(batch.workflowId(),store.batches().get(0).workflowId());
        store.started(batch,"run-1");assertEquals("run-1",store.batches().get(0).runId());
    }
    @Test void baselineAndEditedRevisionDoNotEnqueueHistoricalOrStaleResults() {
        var s=subscription();store.record(s,List.of(item(1,s.baseline()-60000),item(2,s.baseline()+60000)));store.enqueue();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rss_download_task",Integer.class));
        var changed=request();changed.setKeyword("changed");store.save(s.id(),changed);
        assertNull(store.load(s.id(),1));assertEquals(0,store.record(s,List.of(item(3,System.currentTimeMillis()))));
        assertNotNull(store.load(s.id(),2));
        var oldItems=(List<java.util.Map<String,Object>>)store.items(s.id(),1,25).get("records");
        assertEquals("test",oldItems.get(0).get("searchQuery"));
    }
    @Test void cursorSurvivesRestartAndCoverageOnlyMovesWhenScanCompletes() {
        var s=store.beginScan(subscription());assertNotNull(s.scanStarted());
        store.checkpoint(s,"https://e-hentai.org/?next=10",false,10);
        var reloaded=store.load(s.id(),1);assertEquals(10,reloaded.scanPages());assertNull(reloaded.coverage());
        assertEquals("https://e-hentai.org/?next=10",reloaded.cursor());
        store.checkpoint(s,"https://e-hentai.org/?next=50",false,5);
        assertEquals("https://e-hentai.org/?next=10",store.load(s.id(),1).cursor());
        store.checkpoint(reloaded,null,true,11);
        var complete=store.load(s.id(),1);assertEquals(s.scanStarted(),complete.coverage());assertNull(complete.cursor());
        store.checkpoint(reloaded,"https://e-hentai.org/?next=1",false,12);
        assertNull(store.load(s.id(),1).cursor());
    }
    @Test void pausedSubscriptionsStopDispatchAndCollectOnlyCanLaterDownload() {
        var req=request();req.setAutoDownload(false);String id=store.save(null,req);
        store.record(store.load(id,1),List.of(item(1,System.currentTimeMillis())));store.enqueue();
        assertNull(store.prepareBatch());
        req.setAutoDownload(true);store.save(id,req);store.enqueue();store.enable(id,false);
        assertNull(store.prepareBatch());store.enable(id,true);assertNotNull(store.prepareBatch());
    }
    @Test void failedItemPersistsOutsideFeedAndRetriesHaveBoundedAttempts() {
        var s=subscription();store.record(s,List.of(item(1,System.currentTimeMillis())));store.enqueue();var batch=store.prepareBatch();
        var task=store.tasks(batch).get(0);store.retryTask(task,"详情失败");store.finishBatch(batch);
        assertEquals("RETRY",db.queryForObject("SELECT status FROM rss_download_task WHERE gid=1",String.class));
        db.update("UPDATE rss_download_task SET attempts=5,status='QUEUED' WHERE gid=1");
        store.retryTask(new RssStore.Task(1,5,"QUEUED"),"仍失败");
        assertEquals("FAILED",db.queryForObject("SELECT status FROM rss_download_task WHERE gid=1",String.class));
        store.retry(s.id());assertNotNull(store.prepareBatch());
        assertEquals(1,store.items(s.id(),1,25).get("total") instanceof Long ? ((Long)store.items(s.id(),1,25).get("total")).intValue() : 0);
    }
    @Test void twoConcurrentDispatchersCannotClaimTheSameTask() throws Exception {
        var s=subscription();store.record(s,List.of(item(1,System.currentTimeMillis())));store.enqueue();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var gate=new CountDownLatch(1);
            Callable<RssStore.Batch> work=()->{gate.await();return store.prepareBatch();};
            var a=pool.submit(work);var b=pool.submit(work);gate.countDown();
            assertEquals(1,(a.get(10,TimeUnit.SECONDS)==null?0:1)+(b.get(10,TimeUnit.SECONDS)==null?0:1));
        } finally {pool.shutdownNow();}
    }
    @Test void globalPermitIsIdempotentAcrossRetriesAndExclusiveAcrossRuns() {
        var config=new EhWorkflowConfig();config.setMaxConcurrency(1);
        var service=new DownloadPermitService(db,manager,config,mock(WorkflowClient.class));
        assertEquals("ACQUIRED",service.acquire(1L,"a","r1"));
        assertEquals("ACQUIRED",service.acquire(1L,"a","r1"));
        assertEquals("BUSY",service.acquire(1L,"b","r2"));
        assertEquals("BUSY",service.acquire(2L,"c","r3"));
        service.release(1L,"a","wrong-run");assertTrue(service.hasOwner(1));
        service.release(1L,"a","r1");assertEquals("ACQUIRED",service.acquire(2L,"c","r3"));
    }
    @Test void listItemsAndRunRecordsExposeConsistentApiShapes() {
        var s=subscription();store.record(s,List.of(item(1,System.currentTimeMillis())));
        String run=UUID.randomUUID().toString();store.beginRun(run,s);store.finish(run,s,1,2,"temporary failure");
        var row=store.list().get(0);assertEquals("测试订阅",row.get("name"));assertEquals(1,((List<?>)row.get("counts")).size());
        assertEquals("ERROR",store.runs(s.id()).get(0).get("status"));
        assertEquals(1,((List<?>)store.items(s.id(),1,25).get("records")).size());
    }

    @Test void startedWorkflowWithLostDatabaseAcknowledgementIsNotStartedAgain() {
        var s=subscription();store.record(s,List.of(item(1,System.currentTimeMillis())));store.enqueue();
        var batch=store.prepareBatch();
        db.update("UPDATE rss_subscription SET next_poll_at=?",Long.MAX_VALUE);
        var starts=new java.util.concurrent.atomic.AtomicInteger();
        try(var env=io.temporal.testing.TestWorkflowEnvironment.newInstance()) {
            var worker=env.newWorker(com.checker.common.Constants.TASK_QUEUE);
            worker.registerWorkflowImplementationFactory(com.checker.temporalServices.workflows.EHAutomationWorkflow.class,()->options->starts.incrementAndGet());
            env.start();
            var client=env.getWorkflowClient();
            var workflow=client.newWorkflowStub(com.checker.temporalServices.workflows.EHAutomationWorkflow.class,io.temporal.client.WorkflowOptions.newBuilder()
                    .setTaskQueue(com.checker.common.Constants.TASK_QUEUE).setWorkflowId(batch.workflowId()).build());
            workflow.executeAutomation(com.checker.dto.SearchOptions.builder().galleryUrls(batch.urls()).build());
            // Simulate a crash before store.started(): the durable batch still says DISPATCHING.
            var runtime=new RssRuntime(store,client,new RssConfig(),mock(com.checker.mapper.EhGalleriesMapper.class),mock(DownloadPermitService.class));
            runtime.tick();assertEquals("RUNNING",store.batches().get(0).status());
            runtime.tick();assertEquals(1,starts.get());
            // The parent returned successfully but no gallery was created: this item must remain retryable.
            assertEquals("RETRY",db.queryForObject("SELECT status FROM rss_download_task WHERE gid=1",String.class));
        }
    }

    @Test void rediscoveryNeverOverwritesImportedFileOrStatus() throws Exception {
        var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(db.getDataSource());
        var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        var sessionFactory=factory.getObject();
        sessionFactory.getConfiguration().addMapper(com.checker.mapper.EhGalleriesMapper.class);
        var mapper=new org.mybatis.spring.SqlSessionTemplate(sessionFactory).getMapper(com.checker.mapper.EhGalleriesMapper.class);
        var first=new com.checker.entity.EhGalleriesEntity();first.setGid(900L);first.setTitle("Title");first.setFilename("final.cbz");
        first.setDownloadStatus("已入库");first.setCrawledAt(new java.util.Date());first.setUpdatedAt(new java.util.Date());first.setTags(List.of("language:chinese"));
        mapper.insertDiscoveredGallery(first);
        var next=new com.checker.entity.EhGalleriesEntity();next.setGid(900L);next.setTitle("Title");next.setFilename("raw title");
        next.setDownloadStatus("未下载");next.setCrawledAt(new java.util.Date());next.setUpdatedAt(new java.util.Date());
        mapper.insertDiscoveredGallery(next);
        var result=mapper.selectGalleryForUpdate(900L);
        assertEquals("已入库",result.getDownloadStatus());assertEquals("final.cbz",result.getFilename());
        assertEquals(List.of("language:chinese"),result.getTags());
    }
}
