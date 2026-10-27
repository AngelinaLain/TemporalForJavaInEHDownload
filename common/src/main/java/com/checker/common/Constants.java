package com.checker.common;

/**
 * 集中保存跨服务共用的任务队列名称和源站接口地址，保证工作流与执行节点使用同一约定。
 */
public final class Constants {

    // ─── Temporal ────────────────────────────────────────────────
    public static final String TASK_QUEUE = "EHDownloadTaskQueue";
    public static final String SCRAPER_TASK_QUEUE = "EH_SCRAPER_TASK_QUEUE";
    public static final String AI_TASK_QUEUE = "EH_TASK_QUEUE";

    // ─── EHentai URLs ───────────────────────────────────────────
    public static final String EHENTAI_BASE_URL = "https://e-hentai.org/";
    public static final String EHENTAI_API_URL = "https://api.e-hentai.org/api.php";
    public static final String EHENTAI_ARCHIVER_URL = "https://e-hentai.org/archiver.php";

    // ─── Synology API 路径 ──────────────────────────────────────
    public static final String SYNO_AUTH_PATH = "/webapi/auth.cgi";
    public static final String SYNO_DOWNLOAD_TASK_PATH = "/webapi/DownloadStation/task.cgi";
    public static final String SYNO_ENTRY_PATH = "/webapi/entry.cgi";

    // ─── Komga ──────────────────────────────────────────────────
    public static final String KOMGA_TARGET_SERIES = "N8N_Update";

    private Constants() {}
}
