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
import com.jcraft.jsch.SftpException;
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

/** 从上传使用的相同SMB/SFTP目的地打开现有的Komga存档文件。 */
@Service
public class SynologyArchiveReader {
    private static final Pattern GID_PREFIX = Pattern.compile("^\\[(\\d+)](?:\\s|$)");
    private final EhNetworkConfig config;
    private final MountedArchiveStorage mountedStorage;

    public SynologyArchiveReader(EhNetworkConfig config, MountedArchiveStorage mountedStorage) {
        this.config = config;
        this.mountedStorage = mountedStorage;
    }

    /** 从挂载目录或群晖共享读取指定归档，并在任务结束后释放连接。 */
    public <T> T read(String filename, ArchiveInputFunction<T> function) throws Exception {
        return read("", filename, function);
    }

    /** 从挂载目录或群晖共享读取指定归档，并在任务结束后释放连接。 */
    public <T> T read(String relativeDirectory, String filename, ArchiveInputFunction<T> function) throws Exception {
        try (ArchiveSession session = openSession()) {
            return session.read(relativeDirectory, filename, function);
        }
    }

    /** 列出目录中的归档名称，优先使用挂载目录，其次尝试共享协议。 */
    public List<String> listArchives() throws Exception {
        try (ArchiveSession session = openSession()) {
            return session.listArchives();
        }
    }

    /** 根据数据库文件名和真实 GID，解析可供系列同步使用的唯一归档。 */
    public Optional<String> resolveArchive(String directory, String filename, Long gid) throws Exception {
        try (ArchiveSession session = openSession()) {
            return selectSeriesArchive(filename, gid, session.listArchives(directory));
        }
    }

    /** 检查确切文件名是否存在，用于需要精确确认目标的流程。 */
    public boolean existsExact(String directory, String filename) throws Exception {
        try (ArchiveSession session = openSession()) {
            return session.listArchives(directory).contains(filename);
        }
    }

    /** 清理绝不能退回到按GID删除不同的文件。 */
    public void deleteExact(String directory, String filename) throws Exception {
        try (ArchiveSession session = openSession()) {
            session.deleteExact(directory, filename);
        }
    }

    /** 通过可用的存储方式改名，不重新下载归档。 */
    public void rename(String sourceFilename, String targetFilename) throws Exception {
        try (ArchiveSession session = openSession()) {
            session.rename(sourceFilename, targetFilename);
        }
    }

    /** 按指定目录和文件名删除归档，必要时兼容同 GID 历史名称。 */
    public void delete(String filename) throws Exception {
        delete("", filename);
    }

    /** 按指定目录和文件名删除归档，必要时兼容同 GID 历史名称。 */
    public void delete(String relativeDirectory, String filename) throws Exception {
        try (ArchiveSession session = openSession()) {
            session.delete(relativeDirectory, filename);
        }
    }

    /**
    * 打开一个任务作用域的阅读器。连接在顺序的存档读取和
    * 在传输失败后透明地重新创建，避免了数千次SSH握手。
     */
    public ArchiveSession openSession() {
        return new ReusableArchiveSession();
    }

    /** 在一批归档操作中复用 SMB/SFTP 连接，网络故障后按协议重建连接。 */
    private final class ReusableArchiveSession implements ArchiveSession {
        private SMBClient smbClient;
        private Connection smbConnection;
        private Session smbSession;
        private DiskShare smbShare;
        private com.jcraft.jsch.Session sshSession;
        private ChannelSftp sftp;

        /** 从挂载目录或群晖共享读取指定归档，并在任务结束后释放连接。 */
        @Override
        public <T> T read(String relativeDirectory, String filename, ArchiveInputFunction<T> function) throws Exception {
            String safeDirectory = validateRelativeDirectory(relativeDirectory);
            if (filename == null || filename.isBlank()) throw new IllegalArgumentException("画廊文件名为空");
            if (function == null) throw new IllegalArgumentException("归档处理函数不能为空");
            if (mountedStorage.isReadable()) {
                return mountedStorage.read(safeDirectory, filename, function);
            }

            Exception smbFailure = null;
            EhNetworkConfig.Smb smb = config.getSmb();
            if (smbConfigured(smb)) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        return readViaSmb(safeDirectory, filename, smb, function);
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
                    return readViaSftp(safeDirectory, filename, function);
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

        /** 复用 SMB 连接打开归档，精确文件缺失时只查对应 GID 的候选。 */
        private <T> T readViaSmb(String relativeDirectory, String filename, EhNetworkConfig.Smb smb,
                                 ArchiveInputFunction<T> function) throws Exception {
            ensureSmbConnected(smb);
            String directory = joinSmb(normalizeSmbPath(smb.getPath()), relativeDirectory);
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

        /** 按需建立并复用 SMB 连接，避免每本归档重新握手。 */
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

        /** 通过 SFTP 打开归档，连接失败按既定次数重试。 */
        private <T> T readViaSftp(String relativeDirectory, String filename,
                                  ArchiveInputFunction<T> function) throws Exception {
            ensureSftpConnected();
            try {
                return readSftpPath(relativeDirectory, filename, function);
            } catch (ArchiveProcessingException processing) {
                throw processing;
            } catch (Exception exactFailure) {
                List<String> names = new ArrayList<>();
                String prefix = gidSearchPrefix(filename);
                if (prefix != null) {
                    Vector<ChannelSftp.LsEntry> entries = sftp.ls(joinSftp(relativeDirectory, prefix + "*"));
                    for (ChannelSftp.LsEntry entry : entries) names.add(entry.getFilename());
                }
                Optional<String> resolved = selectGidArchive(filename, names);
                if (resolved.isPresent() && !resolved.get().equals(filename)) {
                    return readSftpPath(relativeDirectory, resolved.get(), function);
                }
                throw exactFailure;
            }
        }

        private <T> T readSftpPath(String relativeDirectory, String filename,
                                   ArchiveInputFunction<T> function) throws Exception {
            try (InputStream input = sftp.get(joinSftp(relativeDirectory, filename))) {
                return applyArchiveFunction(input, function);
            }
        }

        /** 区分归档处理异常与连接异常，避免把坏归档当成网络错误反复重试。 */
        private <T> T applyArchiveFunction(InputStream input, ArchiveInputFunction<T> function)
                throws ArchiveProcessingException {
            try {
                return function.apply(input);
            } catch (Exception processingFailure) {
                throw new ArchiveProcessingException(processingFailure);
            }
        }

        /** 列出目录中的归档名称，优先使用挂载目录，其次尝试共享协议。 */
        @Override
        public List<String> listArchives() throws Exception {
            return listArchives("");
        }

        /** 列出目录中的归档名称，优先使用挂载目录，其次尝试共享协议。 */
        @Override
        public List<String> listArchives(String relativeDirectory) throws Exception {
            String safeDirectory = validateRelativeDirectory(relativeDirectory);
            if (mountedStorage.isReadable()) return mountedStorage.listArchives(safeDirectory);
            Exception smbFailure = null;
            EhNetworkConfig.Smb smb = config.getSmb();
            if (smbConfigured(smb)) {
                try {
                    ensureSmbConnected(smb);
                    String directory = joinSmb(normalizeSmbPath(smb.getPath()), safeDirectory);
                    if (!directory.isEmpty() && !smbShare.folderExists(directory)) return List.of();
                    List<String> names = new ArrayList<>();
                    for (FileIdBothDirectoryInformation entry : smbShare.list(directory, "*")) {
                        if ((entry.getFileAttributes() & 0x10) == 0 && isSupportedArchive(entry.getFileName())) {
                            names.add(entry.getFileName());
                        }
                    }
                    return names;
                } catch (Exception failure) {
                    smbFailure = failure;
                    closeSmb();
                }
            }
            try {
                ensureSftpConnected();
                List<String> names = new ArrayList<>();
                Vector<ChannelSftp.LsEntry> entries;
                try {
                    entries = sftp.ls(joinSftp(safeDirectory, "*"));
                } catch (SftpException missing) {
                    if (missing.id == ChannelSftp.SSH_FX_NO_SUCH_FILE && smbFailure == null) return List.of();
                    throw missing;
                }
                for (ChannelSftp.LsEntry entry : entries) {
                    if (!entry.getAttrs().isDir() && isSupportedArchive(entry.getFilename())) {
                        names.add(entry.getFilename());
                    }
                }
                return names;
            } catch (Exception sftpFailure) {
                throw combinedFailure("无法列出群晖归档", smbFailure, sftpFailure);
            }
        }

        /** 通过可用的存储方式改名，不重新下载归档。 */
        @Override
        public void rename(String sourceFilename, String targetFilename) throws Exception {
            validateFilename(sourceFilename);
            validateFilename(targetFilename);
            if (mountedStorage.isWritable()) {
                mountedStorage.rename(sourceFilename, targetFilename);
                return;
            }
            Exception smbFailure = null;
            EhNetworkConfig.Smb smb = config.getSmb();
            if (smbConfigured(smb)) {
                try {
                    ensureSmbConnected(smb);
                    String directory = normalizeSmbPath(smb.getPath());
                    String sourcePath = joinSmb(directory, sourceFilename);
                    String targetPath = joinSmb(directory, targetFilename);
                    try (com.hierynomus.smbj.share.File file = smbShare.openFile(sourcePath,
                            EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
                            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {
                        file.rename(targetPath, false);
                    }
                    return;
                } catch (Exception failure) {
                    smbFailure = failure;
                    closeSmb();
                }
            }
            try {
                ensureSftpConnected();
                sftp.rename(sourceFilename, targetFilename);
            } catch (Exception sftpFailure) {
                throw combinedFailure("无法重命名群晖归档 " + sourceFilename, smbFailure, sftpFailure);
            }
        }

        /** 按指定目录和文件名删除归档，必要时兼容同 GID 历史名称。 */
        @Override
        public void delete(String relativeDirectory, String filename) throws Exception {
            delete(relativeDirectory, filename, false);
        }

        @Override
        public void deleteExact(String relativeDirectory, String filename) throws Exception {
            delete(relativeDirectory, filename, true);
        }

        /** 按指定目录和文件名删除归档，必要时兼容同 GID 历史名称。 */
        private void delete(String relativeDirectory, String filename, boolean exact) throws Exception {
            String safeDirectory = validateRelativeDirectory(relativeDirectory);
            validateFilename(filename);
            if (mountedStorage.isWritable()) {
                if (exact) mountedStorage.deleteExact(safeDirectory, filename);
                else mountedStorage.delete(safeDirectory, filename);
                return;
            }
            Exception smbFailure = null;
            EhNetworkConfig.Smb smb = config.getSmb();
            if (smbConfigured(smb)) {
                try {
                    ensureSmbConnected(smb);
                    String directory = joinSmb(normalizeSmbPath(smb.getPath()), safeDirectory);
                    if (exact) {
                        String path = joinSmb(directory, filename);
                        if (smbShare.fileExists(path)) smbShare.rm(path);
                    } else deleteViaSmb(directory, filename);
                    return;
                } catch (Exception failure) {
                    smbFailure = failure;
                    closeSmb();
                }
            }
            try {
                ensureSftpConnected();
                if (exact) {
                    try {
                        sftp.rm(joinSftp(safeDirectory, filename));
                    } catch (SftpException missing) {
                        if (missing.id != ChannelSftp.SSH_FX_NO_SUCH_FILE || smbFailure != null) throw missing;
                    }
                } else deleteViaSftp(safeDirectory, filename);
            } catch (Exception sftpFailure) {
                throw combinedFailure("无法删除群晖归档 " + filename, smbFailure, sftpFailure);
            }
        }

        private void deleteViaSmb(String directory, String filename) throws Exception {
            try {
                smbShare.rm(joinSmb(directory, filename));
            } catch (Exception exactFailure) {
                Optional<String> resolved = selectGidArchive(filename, listSmbGidCandidates(directory, filename));
                if (resolved.isPresent() && !resolved.get().equals(filename)) {
                    smbShare.rm(joinSmb(directory, resolved.get()));
                    return;
                }
                throw exactFailure;
            }
        }

        private List<String> listSmbGidCandidates(String directory, String filename) {
            List<String> names = new ArrayList<>();
            String prefix = gidSearchPrefix(filename);
            if (prefix == null) return names;
            for (FileIdBothDirectoryInformation entry : smbShare.list(directory, prefix + "*")) {
                names.add(entry.getFileName());
            }
            return names;
        }

        private void deleteViaSftp(String relativeDirectory, String filename) throws Exception {
            try {
                sftp.rm(joinSftp(relativeDirectory, filename));
            } catch (Exception exactFailure) {
                List<String> names = new ArrayList<>();
                String prefix = gidSearchPrefix(filename);
                if (prefix != null) {
                    Vector<ChannelSftp.LsEntry> entries = sftp.ls(joinSftp(relativeDirectory, prefix + "*"));
                    for (ChannelSftp.LsEntry entry : entries) names.add(entry.getFilename());
                }
                Optional<String> resolved = selectGidArchive(filename, names);
                if (resolved.isPresent() && !resolved.get().equals(filename)) {
                    sftp.rm(joinSftp(relativeDirectory, resolved.get()));
                    return;
                }
                throw exactFailure;
            }
        }

        /** 按需建立并复用 SFTP 连接，统一设置连接超时。 */
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
                // 保留原始读取或连接错误，关闭连接时的次要异常不应遮住真正原因。
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static IOException combinedFailure(String action, Exception smbFailure, Exception sftpFailure) {
        StringBuilder message = new StringBuilder(action);
        if (smbFailure != null) message.append("；SMB: ").append(rootMessage(smbFailure));
        if (sftpFailure != null) message.append("；SFTP: ").append(rootMessage(sftpFailure));
        return new IOException(message.toString(), sftpFailure != null ? sftpFailure : smbFailure);
    }

    private static void validateFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.contains("/") || filename.contains("\\")
                || ".".equals(filename) || "..".equals(filename)) {
            throw new IllegalArgumentException("非法归档文件名");
        }
    }

    private static String joinSmb(String directory, String filename) {
        return directory.isEmpty() ? filename : directory + "\\" + filename;
    }

    private static String joinSftp(String directory, String filename) {
        return directory == null || directory.isBlank() ? filename : directory.replace('\\', '/') + "/" + filename;
    }

    /** 拒绝绝对路径、目录穿越和非法分段，确保操作留在配置的归档范围。 */
    private static String validateRelativeDirectory(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.endsWith("/") || normalized.contains("//")
                || normalized.equals("..") || normalized.contains("../") || normalized.contains("/..")) {
            throw new IllegalArgumentException("非法系列目录");
        }
        return normalized;
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

    /** 系列同步已知真实 GID，即使旧数据库文件名没有前缀，也可据此定位唯一归档。 */
    static Optional<String> selectSeriesArchive(String filename, Long gid, List<String> names) throws IOException {
        if (gid == null) throw new IllegalArgumentException("画廊 GID 为空");
        List<String> candidates = names.stream().filter(SynologyArchiveReader::isSupportedArchive)
                .filter(name -> {
                    Matcher prefix = GID_PREFIX.matcher(name);
                    // 文件已明确属于其他 GID 时，不能因为标题相同就接受它。
                    if (prefix.find()) return prefix.group(1).equals(gid.toString());
                    return name.equals(filename) || (!isSupportedArchive(filename)
                            && (name.equals(filename + ".cbz") || name.equals(filename + ".zip")));
                }).distinct().toList();
        if (candidates.size() > 1) {
            throw new IOException("GID " + gid + " 匹配到多个归档，请在群晖归档同步中人工核查: " + candidates);
        }
        return candidates.stream().findFirst();
    }

    /** 判断两个受支持的归档文件名是否指向同一个稳定 GID，用于识别历史改名副本。 */
    public static boolean isArchiveForSameGid(String requestedFilename, String candidateFilename) {
        if (requestedFilename == null || candidateFilename == null || !isSupportedArchive(candidateFilename)) {
            return false;
        }
        Matcher requested = GID_PREFIX.matcher(requestedFilename);
        Matcher candidate = GID_PREFIX.matcher(candidateFilename);
        return requested.find() && candidate.find() && requested.group(1).equals(candidate.group(1));
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
    /** 让调用方在归档输入流有效期间处理内容，读取器负责关闭流和底层连接。 */
    public interface ArchiveInputFunction<T> {
        T apply(InputStream input) throws Exception;
    }

    /** 定义一次可复用的归档访问会话，批量任务结束后统一关闭连接。 */
    public interface ArchiveSession extends AutoCloseable {
        <T> T read(String relativeDirectory, String filename, ArchiveInputFunction<T> function) throws Exception;

        /** 从挂载目录或群晖共享读取指定归档，并在任务结束后释放连接。 */
        default <T> T read(String filename, ArchiveInputFunction<T> function) throws Exception {
            return read("", filename, function);
        }

        List<String> listArchives() throws Exception;

        List<String> listArchives(String relativeDirectory) throws Exception;

        void deleteExact(String relativeDirectory, String filename) throws Exception;

        void rename(String sourceFilename, String targetFilename) throws Exception;

        void delete(String relativeDirectory, String filename) throws Exception;

        /** 按指定目录和文件名删除归档，必要时兼容同 GID 历史名称。 */
        default void delete(String filename) throws Exception {
            delete("", filename);
        }

        @Override
        void close();
    }

    /** 标记“文件已经打开，但内容处理失败”，避免这类错误触发无意义的重新连接。 */
    private static final class ArchiveProcessingException extends Exception {
        private final Exception original;

        private ArchiveProcessingException(Exception original) {
            super(original);
            this.original = original;
        }
    }
}
