package com.checker.common;

/**
 * 全局常量类：集中管理魔术字符串，防止分散硬编码导致拼写错误
 */
public final class Constants {

    // ─── Temporal ────────────────────────────────────────────────
    public static final String TASK_QUEUE = "EHDownloadTaskQueue";

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
