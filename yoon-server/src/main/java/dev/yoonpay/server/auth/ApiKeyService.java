package dev.yoonpay.server.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Applications and their API keys. Keys are stored as SHA-256 hashes and shown once. */
@Service
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern APP_NAME = Pattern.compile("[a-z][a-z0-9-]{1,62}");

    private final JdbcClient jdbc;

    public ApiKeyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A new application with its first key. */
    public record Created(AppPrincipal application, String apiKey) {
        @Override
        public String toString() {
            return "Created[application=" + application + ", apiKey=(hidden)]";
        }
    }

    @Transactional
    public Created createApplication(String name) {
        if (!APP_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Application name must match " + APP_NAME + " (it is also used in env var names)");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO applications (id, name) VALUES (:id, :name)")
                .param("id", id).param("name", name).update();
        return new Created(new AppPrincipal(id, name), issueKey(id));
    }

    @Transactional
    public String issueKey(UUID applicationId) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String key = "yk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        jdbc.sql("INSERT INTO api_keys (id, application_id, prefix, key_hash) VALUES (:id, :app, :prefix, :hash)")
                .param("id", UUID.randomUUID())
                .param("app", applicationId)
                .param("prefix", key.substring(0, 10))
                .param("hash", hash(key))
                .update();
        return key;
    }

    public Optional<AppPrincipal> authenticate(String key) {
        if (key == null || !key.startsWith("yk_")) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT a.id, a.name
                        FROM api_keys k JOIN applications a ON a.id = k.application_id
                        WHERE k.key_hash = :hash AND k.revoked_at IS NULL""")
                .param("hash", hash(key))
                .query(AppPrincipal.class)
                .optional();
    }

    public record KeyInfo(String prefix, java.time.Instant createdAt, java.time.Instant revokedAt) {
    }

    public record AppInfo(UUID id, String name, java.time.Instant createdAt, java.util.List<KeyInfo> keys) {
    }

    public java.util.List<AppInfo> list() {
        record Row(UUID id, String name, java.time.Instant createdAt) {
        }
        return jdbc.sql("SELECT id, name, created_at FROM applications ORDER BY name").query(Row.class).list().stream()
                .map(a -> new AppInfo(a.id(), a.name(), a.createdAt(), jdbc.sql("""
                                SELECT prefix, created_at, revoked_at FROM api_keys
                                WHERE application_id = :app ORDER BY created_at""")
                        .param("app", a.id()).query(KeyInfo.class).list()))
                .toList();
    }

    /** A new key for an existing application, for rotation: the old key keeps working until revoked. */
    @Transactional
    public String addKey(String applicationName) {
        UUID id = jdbc.sql("SELECT id FROM applications WHERE name = :n").param("n", applicationName)
                .query(UUID.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("No application named " + applicationName));
        return issueKey(id);
    }

    /** Revokes every active key starting with {@code prefix} (at least the 10 characters shown by list). */
    @Transactional
    public int revokeKey(String prefix) {
        if (prefix.length() < 10) {
            throw new IllegalArgumentException("Give the key prefix as shown by 'apps list' (10 characters)");
        }
        return jdbc.sql("UPDATE api_keys SET revoked_at = now() WHERE prefix = :p AND revoked_at IS NULL")
                .param("p", prefix.substring(0, 10)).update();
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
