package com.checker.service;

import cn.hutool.core.util.StrUtil;
import com.checker.config.EhNetworkConfig;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Opens an existing Komga archive from the same SMB/SFTP destination used by uploads. */
@Service
public class SynologyArchiveReader {
    private static final Pattern GID_PREFIX = Pattern.compile("^\\[(\\d+)](?:\\s|$)");
    private final EhNetworkConfig config;

    public SynologyArchiveReader(EhNetworkConfig config) {
        this.config = config;
    }

    public <T> T read(String filename, ArchiveInputFunction<T> function) throws Exception {
        try (ArchiveSession session = openSession()) {
            return session.read(filename, function);
        }
    }

    /**
     * Opens a task-scoped reader. Connections are reused across sequential archive reads and
     * transparently recreated after transport failures, avoiding thousands of SSH handshakes.
     */
    public ArchiveSession openSession() {
        return new ReusableArchiveSession();
    }

    private final class ReusableArchiveSession implements ArchiveSession {
        private SMBClient smbClient;
        private Connection smbConnection;
        private Session smbSession;
        private DiskShare smbShare;
        private com.jcraft.jsch.Session sshSession;
        private ChannelSftp sftp;

        @Override
        public <T> T read(String filename, ArchiveInputFunction<T> function) throws Exception {
            if (filename == null || filename.isBlank()) throw new IllegalArgumentException("画廊文件名为空");
            if (function == null) throw new IllegalArgumentException("归档处理函数不能为空");

            Exception smbFailure = null;
            EhNetworkConfig.Smb smb = config.getSmb();
            if (smbConfigured(smb)) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        return readViaSmb(filename, smb, function);
                    } catch (ArchiveProcessingException processing) {
                        throw processing.original;
                    } catch (Exception failure) {
                        smbFailure = failure;
                        closeSmb();
                        if (attempt == 0) backoff(attempt);
                    }
                }
            }

            Exception sftpFailure = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    return readViaSftp(filename, function);
                } catch (ArchiveProcessingException processing) {
                    throw processing.original;
                } catch (Exception failure) {
                    sftpFailure = failure;
                    closeSftp();
                    if (attempt < 2) backoff(attempt);
                }
            }

            StringBuilder message = new StringBuilder("无法读取群晖归档 ").append(filename);
            if (smbFailure != null) message.append("；SMB: ").append(rootMessage(smbFailure));
            if (sftpFailure != null) message.append("；SFTP: ").append(rootMessage(sftpFailure));
            Exception cause = sftpFailure != null ? sftpFailure : smbFailure;
            throw new IOException(message.toString(), cause);
        }

        private <T> T readViaSmb(String filename, EhNetworkConfig.Smb smb,
                                 ArchiveInputFunction<T> function) throws Exception {
            ensureSmbConnected(smb);
            String directory = normalizeSmbPath(smb.getPath());
            try {
                return readSmbPath(directory, filename, function);
            } catch (ArchiveProcessingException processing) {
                throw processing;
            } catch (Exception exactFailure) {
                List<String> names = new ArrayList<>();
                String prefix = gidSearchPrefix(filename);
                if (prefix != null) {
                    for (FileIdBothDirectoryInformation entry : smbShare.list(directory, prefix + "*")) {
                        names.add(entry.getFileName());
                    }
                }
                Optional<String> resolved = selectGidArchive(filename, names);
                if (resolved.isPresent() && !resolved.get().equals(filename)) {
                    return readSmbPath(directory, resolved.get(), function);
                }
                throw exactFailure;
            }
        }

        private <T> T readSmbPath(String directory, String filename,
                                  ArchiveInputFunction<T> function) throws Exception {
            String remotePath = directory.isEmpty() ? filename : directory + "\\" + filename;
            try (com.hierynomus.smbj.share.File file = smbShare.openFile(remotePath,
                    EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                 InputStream input = file.getInputStream()) {
                return applyArchiveFunction(input, function);
            }
        }

        private void ensureSmbConnected(EhNetworkConfig.Smb smb) throws Exception {
            if (smbShare != null && smbShare.isConnected()) return;
            closeSmb();
            smbClient = new SMBClient();
            smbConnection = smbClient.connect(smb.getHost());
            AuthenticationContext auth = new AuthenticationContext(
                    StrUtil.blankToDefault(smb.getUsername(), "guest"),
                    smb.getPassword() == null ? new char[0] : smb.getPassword().toCharArray(),
                    StrUtil.isBlank(smb.getDomain()) ? null : smb.getDomain());
            smbSession = smbConnection.authenticate(auth);
            smbShare = (DiskShare) smbSession.connectShare(smb.getShare());
        }

        private <T> T readViaSftp(String filename, ArchiveInputFunction<T> function) throws Exception {
            ensureSftpConnected();
            try {
                return readSftpPath(filename, function);
            } catch (ArchiveProcessingException processing) {
                throw processing;
            } catch (Exception exactFailure) {
                List<String> names = new ArrayList<>();
                String prefix = gidSearchPrefix(filename);
                if (prefix != null) {
                    Vector<ChannelSftp.LsEntry> entries = sftp.ls(prefix + "*");
                    for (ChannelSftp.LsEntry entry : entries) names.add(entry.getFilename());
                }
                Optional<String> resolved = selectGidArchive(filename, names);
                if (resolved.isPresent() && !resolved.get().equals(filename)) {
                    return readSftpPath(resolved.get(), function);
                }
                throw exactFailure;
            }
        }

        private <T> T readSftpPath(String filename, ArchiveInputFunction<T> function) throws Exception {
            try (InputStream input = sftp.get(filename)) {
                return applyArchiveFunction(input, function);
            }
        }

        private <T> T applyArchiveFunction(InputStream input, ArchiveInputFunction<T> function)
                throws ArchiveProcessingException {
            try {
                return function.apply(input);
            } catch (Exception processingFailure) {
                throw new ArchiveProcessingException(processingFailure);
            }
        }

        private void ensureSftpConnected() throws Exception {
            if (sftp != null && sftp.isConnected() && sshSession != null && sshSession.isConnected()) return;
            closeSftp();
            EhNetworkConfig.Synology synology = config.getSynology();
            String host = synology == null ? null : parseHost(synology.getUrl());
            if (synology == null || StrUtil.isBlank(host) || StrUtil.isBlank(synology.getUsername())) {
                throw new IllegalStateException("未配置可读取历史归档的 SMB 或 SFTP");
            }
            sshSession = new JSch().getSession(synology.getUsername(), host, 22);
            sshSession.setPassword(StrUtil.blankToDefault(synology.getPassword(), ""));
            sshSession.setConfig("StrictHostKeyChecking", "no");
            sshSession.setServerAliveInterval(15_000);
            sshSession.setServerAliveCountMax(3);
            sshSession.connect(30_000);
            sftp = (ChannelSftp) sshSession.openChannel("sftp");
            sftp.connect(30_000);
            sftp.cd("/volume1" + StrUtil.blankToDefault(synology.getDestination(), ""));
        }

        private void backoff(int attempt) throws IOException {
            try {
                Thread.sleep(500L * (1L << attempt));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("等待群晖重连时任务被中断", interrupted);
            }
        }

        @Override
        public void close() {
            closeSftp();
            closeSmb();
        }

        private void closeSftp() {
            if (sftp != null) sftp.disconnect();
            if (sshSession != null) sshSession.disconnect();
            sftp = null;
            sshSession = null;
        }

        private void closeSmb() {
            closeQuietly(smbShare);
            closeQuietly(smbSession);
            closeQuietly(smbConnection);
            closeQuietly(smbClient);
            smbShare = null;
            smbSession = null;
            smbConnection = null;
            smbClient = null;
        }
    }

    private boolean smbConfigured(EhNetworkConfig.Smb smb) {
        return smb != null && StrUtil.isNotBlank(smb.getHost()) && StrUtil.isNotBlank(smb.getShare());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // The original read/connect error is more useful than a cleanup failure.
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    static Optional<String> selectGidArchive(String requestedFilename, List<String> names) {
        String prefix = gidSearchPrefix(requestedFilename);
        if (prefix == null || names == null || names.isEmpty()) return Optional.empty();
        String requestedExtension = extension(requestedFilename);
        return names.stream()
                .filter(name -> name != null && name.startsWith(prefix))
                .filter(SynologyArchiveReader::isSupportedArchive)
                .sorted(Comparator
                        .comparing((String name) -> !extension(name).equalsIgnoreCase(requestedExtension))
                        .thenComparing(String.CASE_INSENSITIVE_ORDER))
                .findFirst();
    }

    private static String gidSearchPrefix(String filename) {
        if (filename == null) return null;
        Matcher matcher = GID_PREFIX.matcher(filename);
        return matcher.find() ? "[" + matcher.group(1) + "] " : null;
    }

    private static boolean isSupportedArchive(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return (lower.endsWith(".cbz") || lower.endsWith(".zip")) && !lower.endsWith(".uploading");
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private static String normalizeSmbPath(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.replace('/', '\\').replaceAll("\\\\+", "\\\\");
        while (normalized.startsWith("\\")) normalized = normalized.substring(1);
        while (normalized.endsWith("\\")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static String parseHost(String url) {
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

    public interface ArchiveSession extends AutoCloseable {
        <T> T read(String filename, ArchiveInputFunction<T> function) throws Exception;

        @Override
        void close();
    }

    private static final class ArchiveProcessingException extends Exception {
        private final Exception original;

        private ArchiveProcessingException(Exception original) {
            super(original);
            this.original = original;
        }
    }
}
