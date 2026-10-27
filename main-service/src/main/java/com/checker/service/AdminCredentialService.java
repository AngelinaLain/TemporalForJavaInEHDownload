package com.checker.service;

import com.checker.config.SecurityProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;

/** Single-admin credentials: immutable environment bootstrap, durable versioned overrides. */
@Service
public class AdminCredentialService {
    private final JdbcTemplate jdbc;
    private final SecurityProperties properties;
    private final PasswordEncoder encoder;

    public AdminCredentialService(JdbcTemplate jdbc, SecurityProperties properties, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.encoder = encoder;
    }

    public OptionalLong authenticate(String username, String password) {
        boolean admin = isAdmin(username);
        Credential credential = admin ? current(username) : bootstrap();
        // Always perform a hash check, including unknown names, without exposing stored hashes.
        boolean matches = password != null && encoder.matches(password, credential.hash());
        return admin && matches ? OptionalLong.of(credential.version()) : OptionalLong.empty();
    }

    /** One indexed projection: every node rejects an old token immediately after a commit. */
    public boolean isTokenCurrent(String username, long version) {
        if (version < 0 || !isAdmin(username)) return false;
        List<Long> versions = jdbc.query("SELECT credential_version FROM eh_admin_credentials WHERE username = ?",
                (row, index) -> row.getLong("credential_version"), username);
        return version == (versions.isEmpty() ? 0L : versions.get(0));
    }

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword, String confirmation) {
        if (!isAdmin(username)) throw new IllegalArgumentException("当前账号不可修改管理员密码");
        validatePasswords(currentPassword, newPassword, confirmation);
        // INSERT and FOR UPDATE share the transaction, including first-change races.
        jdbc.update("INSERT INTO eh_admin_credentials (username,password_hash,credential_version) VALUES (?,?,0) " +
                "ON DUPLICATE KEY UPDATE username = VALUES(username)", username, properties.getAdmin().getPasswordHash());
        Credential credential = jdbc.queryForObject(
                "SELECT password_hash,credential_version FROM eh_admin_credentials WHERE username = ? FOR UPDATE",
                (row, index) -> new Credential(row.getString("password_hash"), row.getLong("credential_version")), username);
        if (credential == null || !encoder.matches(currentPassword, credential.hash()))
            throw new InvalidCurrentPasswordException();
        if (encoder.matches(newPassword, credential.hash()))
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        String nextHash = encoder.encode(newPassword);
        int updated = jdbc.update("UPDATE eh_admin_credentials SET password_hash = ?, " +
                        "credential_version = credential_version + 1, changed_at = CURRENT_TIMESTAMP(6) " +
                        "WHERE username = ? AND credential_version = ?",
                nextHash, username, credential.version());
        if (updated != 1) throw new IllegalStateException("密码已被其他请求修改，请重新登录后重试");
    }

    static void validatePasswords(String currentPassword, String next, String confirmation) {
        if (currentPassword == null || currentPassword.isBlank() || currentPassword.length() > 256)
            throw new IllegalArgumentException("请输入当前密码");
        if (next == null || next.isBlank() || next.codePointCount(0, next.length()) < 8)
            throw new IllegalArgumentException("新密码至少需要 8 个字符");
        if (next.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("新密码 UTF-8 编码后不能超过 72 字节");
        if (!next.equals(confirmation)) throw new IllegalArgumentException("两次输入的新密码不一致");
        if (next.equals(currentPassword)) throw new IllegalArgumentException("新密码不能与当前密码相同");
    }

    private Credential current(String username) {
        List<Credential> records = jdbc.query(
                "SELECT password_hash,credential_version FROM eh_admin_credentials WHERE username = ?",
                (row, index) -> new Credential(row.getString("password_hash"), row.getLong("credential_version")), username);
        return records.isEmpty() ? bootstrap() : records.get(0);
    }
    private Credential bootstrap() { return new Credential(properties.getAdmin().getPasswordHash(), 0); }
    private boolean isAdmin(String username) { return username != null && username.equals(properties.getAdmin().getUsername()); }

    private record Credential(String hash, long version) {}
    public static final class InvalidCurrentPasswordException extends IllegalArgumentException {
        public InvalidCurrentPasswordException() { super("当前密码不正确"); }
    }
}
