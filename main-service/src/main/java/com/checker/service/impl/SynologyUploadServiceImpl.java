package com.checker.service.impl;

import cn.hutool.core.util.StrUtil;
import com.checker.config.EhNetworkConfig;
import com.checker.service.SynologyUploadService;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.SftpProgressMonitor;
import com.jcraft.jsch.SftpException;
import io.temporal.client.ActivityCompletionException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * 群晖上传实现：SMB/CIFS 首选，SFTP 兜底。
 * 覆盖「文件夹名不符合 / 超长」等场景：自动逐级创建目录、文件名安全化。
 */
@Slf4j
@Service
public class SynologyUploadServiceImpl implements SynologyUploadService {

    @Autowired
    private EhNetworkConfig netConfig;

    @Override
    public void upload(Path localFile, String targetFilename, LongConsumer progress) throws Exception {
        EhNetworkConfig.Smb smb = netConfig.getSmb();
        if (smb != null && StrUtil.isNotBlank(smb.getHost()) && StrUtil.isNotBlank(smb.getShare())) {
            try {
                uploadViaSmb(localFile, targetFilename, smb, progress);
                return;
            } catch (ActivityCompletionException e) {
                // heartbeat 发现 Activity 已取消或超时，必须立即停止，不能再尝试 SFTP。
                throw e;
            } catch (Exception e) {
                log.warn("⚠️ SMB 上传失败（{}），尝试 SFTP 兜底...", e.getMessage());
                log.debug("SMB 上传失败堆栈", e);
            }
        } else {
            log.warn("未配置 SMB，直接使用 SFTP 上传");
        }
        uploadViaSftp(localFile, targetFilename, progress);
    }

    private void uploadViaSmb(Path localFile, String targetFilename, EhNetworkConfig.Smb smb,
                              LongConsumer progress) throws Exception {
        long localSize = Files.size(localFile);
        try (SMBClient client = new SMBClient()) {
            try (Connection connection = client.connect(smb.getHost())) {
                String username = StrUtil.blankToDefault(smb.getUsername(), "guest");
                char[] password = smb.getPassword() != null ? smb.getPassword().toCharArray() : new char[0];
                String domain = StrUtil.isNotBlank(smb.getDomain()) ? smb.getDomain() : null;
                AuthenticationContext ac = new AuthenticationContext(username, password, domain);
                Session session = connection.authenticate(ac);

                String shareName = StrUtil.blankToDefault(smb.getShare(), "");
                try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                    String dir = normalizeSmbPath(smb.getPath());
                    ensureSmbDirectory(share, dir);

                    String remotePath = dir.isEmpty() ? targetFilename : dir + "\\" + targetFilename;
                    String tempFilename = "." + targetFilename + ".uploading";
                    String tempRemotePath = dir.isEmpty() ? tempFilename : dir + "\\" + tempFilename;
                    Set<AccessMask> accessMask = EnumSet.of(AccessMask.FILE_WRITE_DATA, AccessMask.FILE_APPEND_DATA,
                            AccessMask.FILE_READ_ATTRIBUTES, AccessMask.DELETE);
                    Set<FileAttributes> attributes = EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL);
                    Set<SMB2CreateOptions> createOptions = EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE);
                    try (com.hierynomus.smbj.share.File file = share.openFile(tempRemotePath, accessMask, attributes,
                            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, createOptions)) {
                        try (InputStream in = Files.newInputStream(localFile); OutputStream os = file.getOutputStream()) {
                            copyWithProgress(in, os, progress);
                        }
                        file.flush();
                        long remoteSize = file.getFileInformation().getStandardInformation().getEndOfFile();
                        if (remoteSize != localSize) {
                            throw new java.io.IOException("SMB 上传大小校验失败: " + remoteSize + "/" + localSize);
                        }
                        // 同一共享内 rename 由 NAS 原子发布，Komga 不会看到上传中的半成品。
                        try {
                            file.rename(remotePath, true);
                        } catch (Exception renameFailure) {
                            log.debug("SMB 覆盖式 rename 失败，尝试删除旧目标后重命名: {}", renameFailure.getMessage());
                            // 部分 SMB 实现不支持覆盖式 rename：先删除旧目标再发布
                            try {
                                share.rm(remotePath);
                            } catch (Exception ignored) {
                                // 旧目标可能原本不存在
                            }
                            file.rename(remotePath, false);
                        }
                    }
                    log.info("✅ SMB 上传成功: //{}/{}", smb.getHost(), remotePath.replace('\\', '/'));
                }
            }
        }
    }

    /** 逐级创建 SMB 目录（容忍文件夹不存在/名称问题） */
    private void ensureSmbDirectory(DiskShare share, String path) throws Exception {
        if (path == null || path.isBlank()) return;
        String[] parts = path.split("[\\\\/]");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (current.length() > 0) current.append('\\');
            current.append(part);
            String dir = current.toString();
            try {
                if (!share.folderExists(dir)) {
                    share.mkdir(dir);
                }
            } catch (Exception e) {
                // 已存在 / 无权限等情况，继续尝试下一级
                log.debug("SMB 目录处理失败（忽略）: {}", dir, e.getMessage());
            }
        }
    }

    private String normalizeSmbPath(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.replace('/', '\\').replaceAll("\\\\+", "\\\\");
        while (normalized.startsWith("\\")) normalized = normalized.substring(1);
        while (normalized.endsWith("\\")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private void uploadViaSftp(Path localFile, String targetFilename, LongConsumer progress) throws Exception {
        EhNetworkConfig.Synology synology = netConfig.getSynology();
        String host = parseSynologyHost();
        String username = synology.getUsername();
        String password = synology.getPassword();
        if (StrUtil.isBlank(host) || StrUtil.isBlank(username)) {
            throw new IllegalStateException("SFTP 兜底缺少群晖主机或用户名配置");
        }

        JSch jsch = new JSch();
        com.jcraft.jsch.Session session = jsch.getSession(username, host, 22);
        session.setPassword(password == null ? "" : password);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setTimeout(30_000);
        session.connect(30_000);
        try {
            ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(30_000);
            try {
                String dir = "/volume1" + StrUtil.blankToDefault(synology.getDestination(), "");
                ensureSftpDirectory(sftp, dir);
                long localSize = Files.size(localFile);
                String tempFilename = "." + targetFilename + ".uploading";
                SftpProgressMonitor monitor = getSftpProgressMonitor(progress);
                try (InputStream in = Files.newInputStream(localFile)) {
                    sftp.put(in, tempFilename, monitor, ChannelSftp.OVERWRITE);
                }
                long remoteSize = sftp.lstat(tempFilename).getSize();
                if (remoteSize != localSize) {
                    throw new java.io.IOException("SFTP 上传大小校验失败: " + remoteSize + "/" + localSize);
                }
                try {
                    sftp.rename(tempFilename, targetFilename);
                } catch (SftpException renameFailure) {
                    // 部分 SFTP 服务不支持覆盖式 rename；删除旧最终文件后再发布已校验的临时文件。
                    try {
                        sftp.rm(targetFilename);
                    } catch (SftpException ignored) {
                        // 目标可能原本不存在。
                    }
                    sftp.rename(tempFilename, targetFilename);
                }
                log.info("✅ SFTP 上传成功: {} -> {}/{}", host, dir, targetFilename);
            } finally {
                sftp.disconnect();
            }
        } finally {
            session.disconnect();
        }
    }

    @NotNull
    private static SftpProgressMonitor getSftpProgressMonitor(LongConsumer progress) {
        AtomicLong uploaded = new AtomicLong();

        return new SftpProgressMonitor() {
            @Override
            public void init(int op, String src, String dest, long max) {
                uploaded.set(0);
            }

            @Override
            public boolean count(long count) {
                progress.accept(uploaded.addAndGet(count));
                return !Thread.currentThread().isInterrupted();
            }

            @Override
            public void end() {
                progress.accept(uploaded.get());
            }
        };
    }

    private static void copyWithProgress(InputStream in, OutputStream out, LongConsumer progress) throws Exception {
        byte[] buffer = new byte[128 * 1024];
        long copied = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            copied += read;
            progress.accept(copied);
            if (Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("上传被中断");
            }
        }
        progress.accept(copied);
    }

    /** 递归创建 SFTP 目录 */
    private void ensureSftpDirectory(ChannelSftp sftp, String dir) throws SftpException {
        if (StrUtil.isBlank(dir) || "/".equals(dir)) return;
        try {
            sftp.cd(dir);
        } catch (SftpException e) {
            int idx = dir.lastIndexOf('/');
            if (idx > 0) {
                ensureSftpDirectory(sftp, dir.substring(0, idx));
            }
            try {
                sftp.mkdir(dir);
            } catch (SftpException ignored) {
                // 目录可能已存在
            }
            sftp.cd(dir);
        }
    }

    /** 从 synology.url（如 http://192.168.1.10:5000）解析主机名 */
    private String parseSynologyHost() {
        String url = netConfig.getSynology().getUrl();
        if (StrUtil.isBlank(url)) return null;
        String host = url;
        int scheme = host.indexOf("://");
        if (scheme >= 0) host = host.substring(scheme + 3);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) host = host.substring(0, colon);
        return host;
    }
}
