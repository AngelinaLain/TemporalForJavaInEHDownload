package com.checker.temporalServices.activities.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.ssh.JschUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checker.common.ErrorType;
import com.checker.config.EhNetworkConfig;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.temporalServices.activities.SynologyActivity;
import com.jcraft.jsch.Session;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 群晖 Activity 实现：负责与 Synology DownloadStation / FileStation 交互（任务推送、状态轮询、文件重命名）
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "EHDownloadTaskQueue")
public class SynologyActivityImpl implements SynologyActivity {

    @Autowired
    private EhNetworkConfig netConfig;

    @Autowired
    private EhGalleriesMapper galleriesMapper;


    @Override
    public Long pushToSynology(String downloadUrl, Long gid, String destination) {
        String sid = getSynologySid();
        Map<String, Object> form = new HashMap<>();
        form.put("api", "SYNO.DownloadStation.Task");
        form.put("version", "3");
        form.put("method", "create");
        form.put("_sid", sid);
        form.put("uri", downloadUrl);
        form.put("destination", StrUtil.blankToDefault(destination, netConfig.getSynology().getDestination()));

        String taskApi = netConfig.getSynology().getUrl() + "/webapi/DownloadStation/task.cgi";
        String response = postSynologyForm(taskApi, form);

        if (response.contains("\"success\":true")) {
            log.info("✅ 任务已推送到群晖, GID: {}", gid);
            return gid;
        } else {
            throw ApplicationFailure.newFailure("群晖任务创建失败: " + response, ErrorType.SYNOLOGY_CREATE_FAILED.getCode());
        }
    }


    @Override
    public String checkSynologyTaskStatus(Long gid, String downloadUrl) {
        String sid = getSynologySid();
        Map<String, Object> form = new HashMap<>();
        form.put("api", "SYNO.DownloadStation.Task");
        form.put("version", "1");
        form.put("method", "list");
        form.put("additional", "detail");
        form.put("_sid", sid);

        String taskApi = netConfig.getSynology().getUrl() + "/webapi/DownloadStation/task.cgi";
        String response = postSynologyForm(taskApi, form);

        JSONObject jsonObj = JSONUtil.parseObj(response);
        if (!jsonObj.getBool("success", false)) {
            log.warn("❌ 群晖 list 接口调用失败, GID: {}", gid);
            return "error";
        }

        JSONArray tasks = jsonObj.getByPath("data.tasks", JSONArray.class);
        if (tasks == null || tasks.isEmpty()) {
            log.warn("⚠️ 任务列表为空, GID: {} (可能已被删除)", gid);
            return "finished";
        }

        for (int i = 0; i < tasks.size(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            String taskUri = task.getByPath("additional.detail.uri", String.class);
            if (StrUtil.isNotBlank(taskUri) && taskUri.equals(downloadUrl)) {
                String status = task.getStr("status", "").toLowerCase();
                log.info("🔍 找到匹配任务, GID: {}, status: {}", gid, status);

                if ("finished".equals(status) || "seeding".equals(status) || "extracted".equals(status)) {
                    String taskTitle = task.getStr("title", "");
                    if (StrUtil.isNotBlank(taskTitle)) {
                        EhGalleriesEntity updateFile = new EhGalleriesEntity();
                        updateFile.setGid(gid);
                        updateFile.setFilename(taskTitle);
                        galleriesMapper.updateById(updateFile);
                        log.info("💾 已记录群晖真实完整文件名: {}", taskTitle);
                    }
                    return "finished";
                } else if ("error".equals(status) || "broken".equals(status) || "file_not_found".equals(status)) {
                    log.warn("❌ 任务异常, GID: {}", gid);
                    return "error";
                } else {
                    return "downloading";
                }
            }
        }

        log.warn("⚠️ 任务不在列表中, GID: {} (可能已完成并被清除)", gid);
        return "finished";
    }


    @Override
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
            String sid = getSynologySid();
            String synoUrl = netConfig.getSynology().getUrl() + "/webapi/entry.cgi";
            String dest = netConfig.getSynology().getDestination();
            if (!dest.startsWith("/")) dest = "/" + dest;
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
                if (renameViaSSH(safePrefix, newFilename)) {
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
     *
     * @param gid         画廊 ID
     * @param newFilename 新文件名
     */
    private void updateFilename(Long gid, String newFilename) {
        EhGalleriesEntity updateEntity = new EhGalleriesEntity();
        updateEntity.setGid(gid);
        updateEntity.setFilename(newFilename);
        galleriesMapper.updateById(updateEntity);
    }

    /**
     * 登录群晖 DSM 获取会话 SID
     *
     * @return 认证成功后的 SID
     * @throws ApplicationFailure 登录失败时抛出
     */
    private String getSynologySid() {
        String authUrl = netConfig.getSynology().getUrl() + "/webapi/auth.cgi";
        Map<String, Object> form = new HashMap<>();
        form.put("api", "SYNO.API.Auth");
        form.put("version", "3");
        form.put("method", "login");
        form.put("session", "DownloadStation");
        form.put("format", "sid");
        form.put("account", netConfig.getSynology().getUsername());
        form.put("passwd", netConfig.getSynology().getPassword());

        String resp = postSynologyForm(authUrl, form);
        JSONObject jsonObj = JSONUtil.parseObj(resp);
        if (jsonObj.getBool("success", false)) {
            return jsonObj.getByPath("data.sid", String.class);
        } else {
            throw ApplicationFailure.newFailure("登录群晖失败: " + resp, ErrorType.SYNOLOGY_AUTH_FAILED.getCode());
        }
    }

    /**
     * 向群晖 API 发送 POST 表单请求，并返回响应体
     *
     * @param url  群晖 API 地址
     * @param form 表单参数
     * @return 响应体字符串
     * @throws ApplicationFailure 接口调用失败时抛出
     */
    private String postSynologyForm(String url, Map<String, Object> form) {
        HttpRequest req = HttpRequest.post(url)
                .timeout(20000)
                .setFollowRedirects(true)
                .setHostnameVerifier((hostname, session) -> true);
        if (form != null && !form.isEmpty()) {
            req.form(form);
        }
        try (HttpResponse response = req.execute()) {
            if (!response.isOk()) {
                throw ApplicationFailure.newFailure(
                        "群晖接口调用失败: HTTP " + response.getStatus(), ErrorType.SYNOLOGY_API_ERROR.getCode());
            }
            return response.body();
        }
    }

    /**
     * 安全的 Bash 字符串转义方法
     * 替换所有的单引号为 '\'' 并在两端包裹单引号，阻断命令注入
     */
    private String escapeBash(String input) {
        if (StrUtil.isBlank(input)) return "''";
        return "'" + input.replace("'", "'\\''") + "'";
    }

    /**
     * 通过 SSH 连接群晖执行 mv 命令进行文件重命名（作为 FileStation API 失败的兆底方案）
     *
     * @param safePrefix  原文件名前缀（用于通配符匹配）
     * @param newFilename 目标新文件名
     * @return 重命名是否成功
     */
    private boolean renameViaSSH(String safePrefix, String newFilename) {
        String host = "10.10.10.40";
        try {
            host = new URL(netConfig.getSynology().getUrl()).getHost();
        } catch (Exception e) {
            log.warn("⚠️ 解析群晖 URL 失败，使用默认 IP", e);
        }
        Session session = null;
        try {
            session = JschUtil.getSession(host, 22, netConfig.getSynology().getUsername(), netConfig.getSynology().getPassword());
            String dest = netConfig.getSynology().getDestination();
            if (!dest.startsWith("/")) dest = "/" + dest;
            String physicalPath = "/volume1" + dest;

            String sourcePath = physicalPath + "/" + safePrefix;
            String destPath = physicalPath + "/" + newFilename;

            // 注意：* 通配符必须放在转义包裹的单引号外面，否则 Bash 不会将其解析为通配符
            String command = String.format("mv %s* %s",
                    escapeBash(sourcePath),
                    escapeBash(destPath)
            );

            log.info("💻 SSH 指令: {}", command);
            String result = JschUtil.exec(session, command, StandardCharsets.UTF_8);

            if (StrUtil.isBlank(result) || !result.toLowerCase().contains("cannot stat")) {
                log.info("✅ SSH 重命名成功");
                return true;
            } else {
                log.error("❌ SSH 重命名失败: {}", result);
                return false;
            }
        } catch (Exception e) {
            log.error("SSH 兜底改名异常", e);
            return false;
        } finally {
            if (session != null) JschUtil.close(session);
        }
    }
}