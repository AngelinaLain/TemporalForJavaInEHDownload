package com.checker.service;

/** 定义后台同步 Komga 标签和元数据的入口，供控制器或任务调用。 */
public interface KomgaSyncService {
    /** 执行标签同步，具体实现负责检查点与远端一致性判断。 */
    void syncTagsToKomga();

    /** 异步启动标签同步，调用方无需等待整批完成。 */
    void startTagSync();

    /** 后台批量刷新已关联书籍的元数据。 */
    void batchRefreshAllKomgaMetadata();
}
