package com.checker.clients;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.ssh.JschUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checker.common.Constants;
import com.checker.config.EhNetworkConfig;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 群晖底层交互客户端：封装 Synology DSM HTTP API 和 SSH 操作。
 * 供 Temporal Activity 和普通 Spring Service 共同依赖，避免直接调用 Activity Bean。
 */
@Slf4j
@Component
public class SynologyApiClient {

    @Autowired
    private EhNetworkConfig netConfig;

    // ─── HTTP API ───────────────────────────────────────────────

    /**
     * 登录群晖 DSM 获取会话 SID
     */
    public String getSynologySid() {
        String authUrl = netConfig.getSynology().getUrl() + Constants.SYNO_AUTH_PATH;
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
            throw new RuntimeException("登录群晖失败: " + resp);
        }
    }

    /**
     * 向群晖 API 发送 POST 表单请求，并返回响应体
     */
    public String postSynologyForm(String url, Map<String, Object> form) {
        HttpRequest req = HttpRequest.post(url)
                .timeout(20000)
                .setFollowRedirects(true)
                .setHostnameVerifier((hostname, session) -> true);
        if (form != null && !form.isEmpty()) {
            req.form(form);
        }
        try (HttpResponse response = req.execute()) {
            if (!response.isOk()) {
                throw new RuntimeException("群晖接口调用失败: HTTP " + response.getStatus());
            }
            return response.body();
        }
    }

    /**
     * 检查群晖下载队列中是否已存在相同 downloadUrl 的任务（幂等性保护）
     */
    public boolean isTaskAlreadyInQueue(String sid, String downloadUrl) {
        Map<String, Object> form = new HashMap<>();
        form.put("api", "SYNO.DownloadStation.Task");
        form.put("version", "1");
        form.put("method", "list");
        form.put("additional", "detail");
        form.put("_sid", sid);

        String taskApi = netConfig.getSynology().getUrl() + Constants.SYNO_DOWNLOAD_TASK_PATH;
        try {
            String response = postSynologyForm(taskApi, form);
            JSONObject jsonObj = JSONUtil.parseObj(response);
            if (!jsonObj.getBool("success", false)) return false;
            JSONArray tasks = jsonObj.getByPath("data.tasks", JSONArray.class);
            if (tasks == null) return false;
            for (int i = 0; i < tasks.size(); i++) {
                String taskUri = tasks.getJSONObject(i).getByPath("additional.detail.uri", String.class);
                if (downloadUrl.equals(taskUri)) return true;
            }
        } catch (Exception e) {
            log.warn("幂等检查失败，继续创建任务", e);
        }
        return false;
    }

    /**
     * 删除群晖中的下载任务（及其临时文件）
     */
    public void deleteDownloadTask(String taskId) {
        try {
            String sid = getSynologySid();
            String url = netConfig.getSynology().getUrl() + "/webapi/DownloadStation/task.cgi";

            HttpResponse response = HttpRequest.post(url)
                    .form("api", "SYNO.DownloadStation.Task")
                    .form("version", "1")
                    .form("method", "delete")
                    .form("id", taskId)
                    .form("_sid", sid)
                    .execute();

            if (response.isOk()) {
                log.info("🗑️ 已清理群晖中的无效/伪装下载任务: {}", taskId);
            } else {
                log.warn("⚠️ 清理群晖无效任务失败, HTTP 状态码: {}", response.getStatus());
            }
        } catch (Exception e) {
            log.error("❌ 调用群晖 API 清理任务异常: {}", taskId, e);
        }
    }

    // ─── SSH ────────────────────────────────────────────────────

    /**
     * 根据 GID 通过 SSH 通配符获取群晖文件的实际大小（MB）
     */
    public Double getFileSizeMbViaSSH(Long gid) {
        if (gid == null) return null;

        String host = parseSynologyHost();
        if (host == null) return null;

        Session session = null;
        try {
            session = JschUtil.getSession(host, 22, netConfig.getSynology().getUsername(), netConfig.getSynology().getPassword());

            String physicalPath = getPhysicalPath();
            String command = String.format("ls -nl '%s/'\\[%d\\]* 2>/dev/null | head -n 1 | awk '{print $5}'", physicalPath, gid);

            String result = JschUtil.exec(session, command, StandardCharsets.UTF_8);

            if (StrUtil.isNotBlank(result)) {
                try {
                    long sizeBytes = Long.parseLong(result.trim());
                    if (sizeBytes > 0) {
                        return Math.round((sizeBytes / 1048576.0) * 100.0) / 100.0;
                    }
                } catch (NumberFormatException e) {
                    log.warn("⚠️ 无法解析文件大小，文件可能不存在。命令返回: {}", result);
                }
            }
        } catch (Exception e) {
            log.error("❌ SSH 获取文件大小异常, GID: {}", gid, e);
        } finally {
            if (session != null) JschUtil.close(session);
        }
        return null;
    }

    /**
     * 批量通过 SSH 获取多个 GID 对应文件的实际大小（MB）。
     * 只建立一次 SSH 连接，将所有 GID 拼成一条 for 循环命令一次性执行，
     * 输出格式为每行 "GID SIZE_BYTES"，解析后返回 Map。
     */
    public Map<Long, Double> batchGetFileSizeViaSSH(List<Long> gids) {
        Map<Long, Double> result = new HashMap<>();
        if (gids == null || gids.isEmpty()) return result;

        String host = parseSynologyHost();
        if (host == null) return result;

        Session session = null;
        try {
            session = JschUtil.getSession(host, 22, netConfig.getSynology().getUsername(), netConfig.getSynology().getPassword());
            String physicalPath = getPhysicalPath();

            // 分批处理，每批最多 200 个 GID，防止单条命令过长
            int batchSize = 200;
            for (int i = 0; i < gids.size(); i += batchSize) {
                List<Long> batch = gids.subList(i, Math.min(i + batchSize, gids.size()));

                // 构造 shell 命令：对每个 GID 用 ls 通配符匹配并输出 "GID 字节数"
                // for g in 123 456 789; do s=$(ls -nl '/volume1/path/'\\[$g\\]* 2>/dev/null | head -n1 | awk '{print $5}'); [ -n "$s" ] && echo "$g $s"; done
                String gidList = batch.stream().map(String::valueOf).collect(Collectors.joining(" "));
                String command = String.format(
                        "for g in %s; do s=$(ls -nl '%s/'\\[$g\\]* 2>/dev/null | head -n1 | awk '{print $5}'); [ -n \"$s\" ] && echo \"$g $s\"; done",
                        gidList, physicalPath
                );

                String output = JschUtil.exec(session, command, StandardCharsets.UTF_8);
                if (StrUtil.isNotBlank(output)) {
                    for (String line : output.trim().split("\n")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length == 2) {
                            try {
                                long gid = Long.parseLong(parts[0]);
                                long sizeBytes = Long.parseLong(parts[1]);
                                if (sizeBytes > 0) {
                                    result.put(gid, Math.round((sizeBytes / 1048576.0) * 100.0) / 100.0);
                                }
                            } catch (NumberFormatException e) {
                                log.warn("⚠️ 解析批量文件大小行失败: {}", line);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ SSH 批量获取文件大小异常", e);
        } finally {
            if (session != null) JschUtil.close(session);
        }
        return result;
    }

    /**
     * 通过 SSH 执行 mv 命令进行文件重命名（FileStation API 失败的兜底方案）
     */
    public boolean renameViaSSH(String safePrefix, String newFilename) {
        String host = parseSynologyHost();
        if (host == null) return false;

        Session session = null;
        try {
            session = JschUtil.getSession(host, 22, netConfig.getSynology().getUsername(), netConfig.getSynology().getPassword());
            String physicalPath = getPhysicalPath();

            String sourcePath = physicalPath + "/" + safePrefix;
            String destPath = physicalPath + "/" + newFilename;

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

    // ─── 内部工具 ───────────────────────────────────────────────

    /**
     * 安全的 Bash 字符串转义方法
     */
    public String escapeBash(String input) {
        if (StrUtil.isBlank(input)) return "''";
        return "'" + input.replace("'", "'\\''") + "'";
    }

    /**
     * 解析群晖 URL 中的主机名
     */
    private String parseSynologyHost() {
        try {
            return new URL(netConfig.getSynology().getUrl()).getHost();
        } catch (Exception e) {
            log.error("❌ 解析群晖 URL 失败: {}", netConfig.getSynology().getUrl(), e);
            return null;
        }
    }

    /**
     * 获取群晖物理存储路径
     */
    private String getPhysicalPath() {
        String dest = netConfig.getSynology().getDestination();
        if (!dest.startsWith("/")) dest = "/" + dest;
        return "/volume1" + dest;
    }

    /**
     * 获取群晖逻辑目标路径（用于 FileStation API）
     */
    public String getDestination() {
        String dest = netConfig.getSynology().getDestination();
        if (!dest.startsWith("/")) dest = "/" + dest;
        return dest;
    }
}
