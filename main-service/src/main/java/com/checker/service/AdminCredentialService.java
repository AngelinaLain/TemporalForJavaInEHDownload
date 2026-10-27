package com.checker.service;

import com.checker.config.SecurityProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;

/** 管理单个管理员的登录凭据：首次使用配置密码，改密后以数据库中的密码和版本为准。 */
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

    /** 核对账号和密码，成功时返回当前凭据版本，供签发登录令牌使用。 */
    public OptionalLong authenticate(String username, String password) {
        boolean admin = isAdmin(username);
        Credential credential = admin ? current(username) : bootstrap();
        // 即使账号不存在也做一次密码校验，减少通过响应耗时猜测账号的机会。
        boolean matches = password != null && encoder.matches(password, credential.hash());
        return admin && matches ? OptionalLong.of(credential.version()) : OptionalLong.empty();
    }

    /** 按账号查询当前凭据版本，让所有服务实例在改密提交后立即拒绝旧令牌。 */
    public boolean isTokenCurrent(String username, long version) {
        if (version < 0 || !isAdmin(username)) return false;
        List<Long> versions = jdbc.query("SELECT credential_version FROM eh_admin_credentials WHERE username = ?",
                (row, index) -> row.getLong("credential_version"), username);
        return version == (versions.isEmpty() ? 0L : versions.get(0));
    }

    /** 在同一事务内核对旧密码、保存新密码并增加版本，避免同时改密相互覆盖。 */
    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword, String confirmation) {
        if (!isAdmin(username)) throw new IllegalArgumentException("当前账号不可修改管理员密码");
        validatePasswords(currentPassword, newPassword, confirmation);
        // 首次建立凭据记录和锁定记录都在当前事务内，首次改密并发时也只有一个请求能继续写。
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

    /** 读取数据库中已修改的凭据；没有覆盖记录时才使用环境配置的初始密码。 */
    private Credential current(String username) {
        List<Credential> records = jdbc.query(
                "SELECT password_hash,credential_version FROM eh_admin_credentials WHERE username = ?",
                (row, index) -> new Credential(row.getString("password_hash"), row.getLong("credential_version")), username);
        return records.isEmpty() ? bootstrap() : records.get(0);
    }
    /** 取得初始密码及版本零，不会覆盖数据库中的改密结果。 */
    private Credential bootstrap() { return new Credential(properties.getAdmin().getPasswordHash(), 0); }
    private boolean isAdmin(String username) { return username != null && username.equals(properties.getAdmin().getUsername()); }

    private record Credential(String hash, long version) {}
    /** 区分“当前密码错误”和其他输入错误，便于接口单独计入失败次数。 */
    public static final class InvalidCurrentPasswordException extends IllegalArgumentException {
        public InvalidCurrentPasswordException() { super("当前密码不正确"); }
    }
}
