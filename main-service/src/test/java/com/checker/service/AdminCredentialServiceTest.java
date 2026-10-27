package com.checker.service;

import com.checker.config.JwtTokenProvider;
import com.checker.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminCredentialServiceTest {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final SecurityProperties properties = properties();
    private final MemoryCredentials jdbc = new MemoryCredentials();
    private final AdminCredentialService service = new AdminCredentialService(jdbc, properties, encoder);

    private SecurityProperties properties() {
        SecurityProperties value = new SecurityProperties();
        value.getAdmin().setUsername("admin");
        value.getAdmin().setPasswordHash(encoder.encode("bootstrap-password"));
        return value;
    }

    @Test
    void bootstrapCredentialsBecomeDurableVersionedOverridesAndOldTokensExpire() {
        JwtTokenProvider tokens = new JwtTokenProvider("a-signing-secret-for-password-tests-1234567890", 60_000);
        String oldToken = tokens.generateToken("admin", service.authenticate("admin", "bootstrap-password").orElseThrow());
        assertTrue(service.isTokenCurrent("admin", tokens.getCredentialVersionFromToken(oldToken)));
        service.changePassword("admin", "bootstrap-password", "replacement-password", "replacement-password");
        assertTrue(encoder.matches("replacement-password", jdbc.hash));
        assertNotEquals("replacement-password", jdbc.hash);
        assertNotEquals(properties.getAdmin().getPasswordHash(), jdbc.hash);
        assertEquals(1, jdbc.version);
        assertFalse(service.isTokenCurrent("admin", tokens.getCredentialVersionFromToken(oldToken)));
        AdminCredentialService restarted = new AdminCredentialService(jdbc, properties, encoder);
        assertTrue(restarted.authenticate("admin", "bootstrap-password").isEmpty());
        assertEquals(1, restarted.authenticate("admin", "replacement-password").orElseThrow());
        assertTrue(restarted.isTokenCurrent("admin", 1));
        assertFalse(restarted.isTokenCurrent("someone-else", 1));
        assertTrue(jdbc.sql.stream().anyMatch(sql -> sql.contains("FOR UPDATE")));
        assertTrue(jdbc.sql.stream().anyMatch(sql -> sql.contains("AND credential_version = ?")));
    }

    @Test
    void wrongOldPasswordCannotWriteAReplacementAndExistingHashRemainsEffective() {
        service.changePassword("admin", "bootstrap-password", "replacement-password", "replacement-password");
        String saved = jdbc.hash;
        assertThrows(AdminCredentialService.InvalidCurrentPasswordException.class, () ->
                service.changePassword("admin", "bootstrap-password", "another-password", "another-password"));
        assertEquals(saved, jdbc.hash);
        assertEquals(1, jdbc.version);
        assertEquals(1, jdbc.passwordUpdates);
        assertThrows(IllegalArgumentException.class, () ->
                service.changePassword("not-admin", "replacement-password", "another-password", "another-password"));
    }

    @Test
    void validatesUnicodeByteLimitConfirmationAndReusingPasswordBeforeAnySql() {
        assertThrows(IllegalArgumentException.class, () -> service.changePassword("admin", "old", "short", "short"));
        assertThrows(IllegalArgumentException.class, () -> service.changePassword("admin", "old", "界".repeat(25), "界".repeat(25)));
        assertThrows(IllegalArgumentException.class, () -> service.changePassword("admin", "old", "valid-password", "different-password"));
        assertThrows(IllegalArgumentException.class, () -> service.changePassword("admin", "same-password", "same-password", "same-password"));
        assertTrue(jdbc.sql.isEmpty());
        assertDoesNotThrow(() -> AdminCredentialService.validatePasswords("old", "界".repeat(24), "界".repeat(24)));
    }

    @Test
    void missingVersionMeansBootstrapGenerationOnly() {
        assertTrue(service.isTokenCurrent("admin", 0));
        assertFalse(service.isTokenCurrent("admin", -1));
        assertTrue(service.authenticate("unknown", "bootstrap-password").isEmpty());
        assertTrue(service.authenticate("admin", "wrong-password").isEmpty());
    }

    /** Deterministic JDBC substitute shared by separate service instances; no real account is changed. */
    private static final class MemoryCredentials extends JdbcTemplate {
        private String hash;
        private long version;
        private int passwordUpdates;
        private final List<String> sql = new ArrayList<>();

        @Override
        public <T> List<T> query(String statement, RowMapper<T> mapper, Object... args) {
            sql.add(statement);
            if (hash == null) return List.of();
            try {
                ResultSet row = mock(ResultSet.class);
                when(row.getString("password_hash")).thenReturn(hash);
                when(row.getLong("credential_version")).thenReturn(version);
                return List.of(mapper.mapRow(row, 0));
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
        }

        @Override
        public <T> T queryForObject(String statement, RowMapper<T> mapper, Object... args) {
            return query(statement, mapper, args).get(0);
        }

        @Override
        public int update(String statement, Object... args) {
            sql.add(statement);
            if (statement.startsWith("INSERT")) {
                if (hash == null) { hash = String.valueOf(args[1]); version = 0; }
                return 1;
            }
            if (((Number) args[2]).longValue() != version) return 0;
            hash = String.valueOf(args[0]);
            version++;
            passwordUpdates++;
            return 1;
        }
    }
}
