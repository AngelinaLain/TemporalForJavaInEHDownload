package com.checker.service;

import cn.hutool.core.util.StrUtil;
import com.checker.config.EhNetworkConfig;
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
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.EnumSet;

/** Opens an existing Komga archive from the same SMB/SFTP destination used by uploads. */
@Service
public class SynologyArchiveReader {
    private final EhNetworkConfig config;

    public SynologyArchiveReader(EhNetworkConfig config) {
        this.config = config;
    }

    public <T> T read(String filename, ArchiveInputFunction<T> function) throws Exception {
        if (filename == null || filename.isBlank()) throw new IllegalArgumentException("画廊文件名为空");
        EhNetworkConfig.Smb smb = config.getSmb();
        if (smb != null && StrUtil.isNotBlank(smb.getHost()) && StrUtil.isNotBlank(smb.getShare())) {
            try {
                return readViaSmb(filename, smb, function);
            } catch (Exception ignored) {
                // Keep parity with upload: SFTP is the fallback when SMB is unavailable.
            }
        }
        return readViaSftp(filename, function);
    }

    private <T> T readViaSmb(String filename, EhNetworkConfig.Smb smb,
                             ArchiveInputFunction<T> function) throws Exception {
        try (SMBClient client = new SMBClient(); Connection connection = client.connect(smb.getHost())) {
            AuthenticationContext auth = new AuthenticationContext(
                    StrUtil.blankToDefault(smb.getUsername(), "guest"),
                    smb.getPassword() == null ? new char[0] : smb.getPassword().toCharArray(),
                    StrUtil.isBlank(smb.getDomain()) ? null : smb.getDomain());
            Session session = connection.authenticate(auth);
            try (DiskShare share = (DiskShare) session.connectShare(smb.getShare())) {
                String directory = normalizeSmbPath(smb.getPath());
                String remotePath = directory.isEmpty() ? filename : directory + "\\" + filename;
                try (com.hierynomus.smbj.share.File file = share.openFile(remotePath,
                        EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                     InputStream input = file.getInputStream()) {
                    return function.apply(input);
                }
            }
        }
    }

    private <T> T readViaSftp(String filename, ArchiveInputFunction<T> function) throws Exception {
        EhNetworkConfig.Synology synology = config.getSynology();
        String host = parseHost(synology.getUrl());
        if (StrUtil.isBlank(host) || StrUtil.isBlank(synology.getUsername())) {
            throw new IllegalStateException("未配置可读取历史归档的 SMB 或 SFTP");
        }
        com.jcraft.jsch.Session session = new JSch().getSession(synology.getUsername(), host, 22);
        session.setPassword(StrUtil.blankToDefault(synology.getPassword(), ""));
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(30_000);
        try {
            ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(30_000);
            try {
                sftp.cd("/volume1" + StrUtil.blankToDefault(synology.getDestination(), ""));
                try (InputStream input = sftp.get(filename)) {
                    return function.apply(input);
                }
            } finally {
                sftp.disconnect();
            }
        } finally {
            session.disconnect();
        }
    }

    private String normalizeSmbPath(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.replace('/', '\\').replaceAll("\\\\+", "\\\\");
        while (normalized.startsWith("\\")) normalized = normalized.substring(1);
        while (normalized.endsWith("\\")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private String parseHost(String url) {
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

    @FunctionalInterface
    public interface ArchiveInputFunction<T> {
        T apply(InputStream input) throws Exception;
    }
}
