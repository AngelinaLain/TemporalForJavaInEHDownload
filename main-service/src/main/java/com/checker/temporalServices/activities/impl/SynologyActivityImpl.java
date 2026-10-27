package com.checker.temporalServices.activities.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checker.common.Constants;
import com.checker.common.ErrorType;
import com.checker.common.SynologyTaskStatus;
import com.checker.clients.SynologyApiClient;
import com.checker.dto.SynologyDownloadResult;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.config.EhNetworkConfig;
import com.checker.temporalServices.activities.SynologyActivity;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static com.checker.common.SynologyTaskStatus.*;

/**
 * 操作群晖下载任务并汇报执行进度；底层认证、HTTP 和文件查询交给群晖客户端。
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.TASK_QUEUE)
public class SynologyActivityImpl implements SynologyActivity {

    @Autowired
    private EhNetworkConfig netConfig;

    @Autowired
    private EhGalleriesMapper galleriesMapper;

    @Autowired
    private SynologyApiClient synologyApiClient;


    @Override
    /** 先查同一下载地址是否已排队，避免活动重试时重复创建任务。 */
    public Long pushToSynology(String downloadUrl, Long gid, String destination) {
        String sid = synologyApiClient.getSynologySid();

        // 幂等性检查：先确认该任务是否已存在于下载列表中
        if (synologyApiClient.isTaskAlreadyInQueue(sid, downloadUrl)) {
            log.info("✅ 任务已存在于群晖下载列表中，跳过创建, GID: {}", gid);
            return gid;
        }

        Map<String, Object> form = new HashMap<>();
        form.put("api", "SYNO.DownloadStation.Task");
        form.put("version", "3");
        form.put("method", "create");
        form.put("_sid", sid);
        form.put("uri", downloadUrl);
        form.put("destination", StrUtil.blankToDefault(destination, netConfig.getSynology().getDestination()));

        String taskApi = netConfig.getSynology().getUrl() + Constants.SYNO_DOWNLOAD_TASK_PATH;
        String response = synologyApiClient.postSynologyForm(taskApi, form);

        if (response.contains("\"success\":true")) {
            log.info("✅ 任务已推送到群晖, GID: {}", gid);
            return gid;
        } else {
            throw ApplicationFailure.newFailure("群晖任务创建失败: " + response, ErrorType.SYNOLOGY_CREATE_FAILED.getCode());
        }
    }

    @Override
    /** 按下载地址找到任务并检查完成大小；任务被清除时再检查物理文件是否存在。 */
    public SynologyTaskStatus checkSynologyTaskStatus(Long gid, String downloadUrl) {
        String sid = synologyApiClient.getSynologySid();
        Map<String, Object> form = new HashMap<>();
        form.put("api", "SYNO.DownloadStation.Task");
        form.put("version", "1");
        form.put("method", "list");
        form.put("additional", "detail");
        form.put("_sid", sid);

        String taskApi = netConfig.getSynology().getUrl() + Constants.SYNO_DOWNLOAD_TASK_PATH;
        String response = synologyApiClient.postSynologyForm(taskApi, form);

        JSONObject jsonObj = JSONUtil.parseObj(response);
        if (!jsonObj.getBool("success", false)) {
            log.warn("❌ 群晖 list 接口调用失败, GID: {}", gid);
            return ERROR;
        }

        JSONArray tasks = jsonObj.getByPath("data.tasks", JSONArray.class);
        if (tasks == null || tasks.isEmpty()) {
            log.warn("⚠️ 任务列表为空, GID: {} (可能已被删除)", gid);
            return FINISHED;
        }

        for (int i = 0; i < tasks.size(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            String taskUri = task.getByPath("additional.detail.uri", String.class);
            if (StrUtil.isNotBlank(taskUri) && taskUri.equals(downloadUrl)) {
                String status = task.getStr("status", "").toLowerCase();
                log.info("🔍 找到匹配任务, GID: {}, status: {}", gid, status);

                SynologyTaskStatus taskStatus = SynologyTaskStatus.fromApiStatus(status);
                if (taskStatus != null && taskStatus.isCompleted()) {

                    // 群晖任务接口返回的大小单位为字节。
                    long fileSize = task.getLong("size", 0L);
                    // 已完成的文件小于 5 KiB 时按错误网页处理，删除任务后交给工作流重试。
                    if (fileSize > 0 && fileSize < 5120) {
                        log.error("❌ 拦截到伪装文件！下载状态虽为 finished，但体积极小 ({} bytes)，判定为错误网页。GID: {}", fileSize, gid);
                        synologyApiClient.deleteDownloadTask(task.getStr("id"));
                        return ERROR; // 强制返回 error，让 Temporal 触发告警并重试
                    }

                    // 保存下载任务的实际文件名，供后续重命名和入库确认使用。
                    String taskTitle = task.getStr("title", "");
                    if (StrUtil.isNotBlank(taskTitle)) {
                        EhGalleriesEntity updateFile = new EhGalleriesEntity();
                        updateFile.setGid(gid);
                        updateFile.setFilename(taskTitle);
                        galleriesMapper.updateById(updateFile);
                        log.info("💾 已记录群晖真实完整文件名: {}", taskTitle);
                    }
                    return FINISHED;

                } else if (taskStatus != null && taskStatus.isError()) {
                    log.warn("❌ 任务异常, GID: {}", gid);
                    return ERROR;
                } else {
                    return DOWNLOADING;
                }
            }
        }

        log.warn("⚠️ 任务不在列表中, GID: {} (可能已完成并被清除)", gid);
        // 已完成任务可能被清除，此时通过文件大小确认实际归档是否仍在磁盘上。
        Double actualSize = synologyApiClient.getFileSizeMbViaSSH(gid);
        if (actualSize != null && actualSize > 0.0) {
            return FINISHED; // 文件确实在，说明是下载完被清除了
        } else {
            return ERROR;    // 文件不在，说明任务丢失或被异常删除，必须重试
        }
    }


    @Override
    /** 分段等待并持续心跳，再轮询下载结果，让长时间等待留在活动内而不膨胀工作流历史。 */
    public SynologyDownloadResult waitForDownloadComplete(Long gid, String downloadUrl, long estimatedWaitSeconds) {
        ActivityExecutionContext context = Activity.getExecutionContext();
        context.heartbeat("任务已推送，等待下载完成");

        // 1. 按预估大小先等一段下载时间（30 秒切片 + 心跳，Temporal UI 可见进度）
        long waitedSeconds = 0;
        long waitTarget = Math.max(estimatedWaitSeconds, 10);
        while (waitedSeconds < waitTarget) {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApplicationFailure.newFailure("等待下载完成时线程被中断", ErrorType.SYNOLOGY_API_ERROR.getCode());
            }
            waitedSeconds += 30;
            context.heartbeat("预估等待中: " + waitedSeconds + "/" + waitTarget + " 秒");
        }

        // 2. 轮询状态直到 FINISHED 或 ERROR（每次心跳上报，供 Temporal UI 实时展示）
        int pollCount = 0;
        while (true) {
            pollCount++;
            SynologyTaskStatus status = checkSynologyTaskStatus(gid, downloadUrl);
            context.heartbeat("轮询第 " + pollCount + " 次, 当前状态: " + status);

            if (status == SynologyTaskStatus.FINISHED) {
                Double actualSizeMb = synologyApiClient.getFileSizeMbViaSSH(gid);
                log.info("✅ 下载完成, GID: {}, 物理文件大小: {} MB", gid, actualSizeMb);
                return new SynologyDownloadResult(SynologyTaskStatus.FINISHED, actualSizeMb, null);
            }
            if (status == SynologyTaskStatus.ERROR) {
                log.warn("⚠️ 拦截到群晖异常或伪装文件，抛出异常触发 Temporal 重试, GID: {}", gid);
                throw ApplicationFailure.newFailure("Synology returned error or fake file",
                        ErrorType.SYNOLOGY_DOWNLOAD_ERROR.getCode());
            }
            try {
                Thread.sleep(20_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApplicationFailure.newFailure("轮询等待时线程被中断", ErrorType.SYNOLOGY_API_ERROR.getCode());
            }
        }
    }


    @Override
    /** 根据数据库标题生成带 GID 的安全归档名，远端重命名成功后更新本地记录。 */
    public String renameSynologyFile(Long gid, String oldFilename) {
        if (StrUtil.isBlank(oldFilename)) return null;

        if (oldFilename.startsWith("[" + gid + "]")) {
            log.info("✅ 文件已含 GID 前缀，跳过重命名: {}", oldFilename);
            return oldFilename;
        }

        EhGalleriesEntity gallery = galleriesMapper.selectById(gid);
        if (gallery == null) return null;

        String ext = ".zip";
        if (oldFilename.matches("(?i).*\\.(zip|cbz|rar)$")) {
            ext = oldFilename.substring(oldFilename.lastIndexOf('.'));
        }

        String safeTitle = gallery.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safeTitle.length() > 80) safeTitle = safeTitle.substring(0, 80);
        String newFilename = "[" + gid + "] " + safeTitle + ext;

        try {
            String sid = synologyApiClient.getSynologySid();
            String synoUrl = netConfig.getSynology().getUrl() + Constants.SYNO_ENTRY_PATH;
            String dest = synologyApiClient.getDestination();
            String oldFilePath = dest + "/" + oldFilename;

            Map<String, Object> params = new HashMap<>();
            params.put("api", "SYNO.FileStation.Rename");
            params.put("version", "2");
            params.put("method", "rename");
            params.put("path", new cn.hutool.json.JSONArray().set(oldFilePath).toString());
            params.put("name", new cn.hutool.json.JSONArray().set(newFilename).toString());
            params.put("_sid", sid);

            HttpResponse response = HttpRequest.post(synoUrl).form(params).execute();
            if (response.isOk() && JSONUtil.parseObj(response.body()).getBool("success", false)) {
                log.info("✅ 重命名成功: {} -> {}", oldFilename, newFilename);
                updateFilename(gid, newFilename);
                return newFilename;
            } else {
                log.error("❌ 群晖 API 重命名失败，尝试 SSH 兜底: {}", response.body());
                String safePrefix = oldFilename.length() > 60 ? oldFilename.substring(0, 60) : oldFilename;
                if (synologyApiClient.renameViaSSH(safePrefix, newFilename)) {
                    updateFilename(gid, newFilename);
                    return newFilename;
                }
            }
        } catch (Exception e) {
            log.error("重命名接口异常", e);
        }
        return oldFilename;
    }

    /**
     * 更新数据库中画廊的文件名字段
     */
    private void updateFilename(Long gid, String newFilename) {
        EhGalleriesEntity updateEntity = new EhGalleriesEntity();
        updateEntity.setGid(gid);
        updateEntity.setFilename(newFilename);
        galleriesMapper.updateById(updateEntity);
    }
}
