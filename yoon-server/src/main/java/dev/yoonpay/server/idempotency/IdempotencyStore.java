package dev.yoonpay.server.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * Idempotency for mutating API calls. {@link #begin} claims the key by inserting it in state
 * IN_PROGRESS <em>before</em> any provider call; the primary key decides concurrent races.
 * A key left IN_PROGRESS (e.g. node crash) is never re-executed: whatever it started is
 * resolved by reconciliation, like any unknown outcome.
 */
@Repository
public class IdempotencyStore {

    private final JdbcClient jdbc;

    public IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public sealed interface Claim {
        /** This caller owns the key and must proceed, then call {@link #complete}. */
        record Started() implements Claim {
        }

        /** Same key, same body, already done: return the stored response. */
        record Replay(int status, String body) implements Claim {
        }

        /** Same key, same body, still running elsewhere: answer 409 with Retry-After. */
        record InProgress() implements Claim {
        }

        /** Same key, different body: answer 409. */
        record Mismatch() implements Claim {
        }
    }

    /** Commits on its own, so the claim is visible to concurrent requests immediately. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim begin(UUID applicationId, String key, String requestHash) {
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys (application_id, idempotency_key, request_hash, state)
                        VALUES (:app, :key, :hash, 'IN_PROGRESS')
                        ON CONFLICT DO NOTHING""")
                .param("app", applicationId)
                .param("key", key)
                .param("hash", requestHash)
                .update();
        if (inserted == 1) {
            return new Claim.Started();
        }

        record Row(String requestHash, String state, Integer responseStatus, String responseBody) {
        }
        Row row = jdbc.sql("""
                        SELECT request_hash, state, response_status, response_body
                        FROM idempotency_keys
                        WHERE application_id = :app AND idempotency_key = :key""")
                .param("app", applicationId)
                .param("key", key)
                .query(Row.class)
                .single();

        if (!row.requestHash().equals(requestHash)) {
            return new Claim.Mismatch();
        }
        if (row.state().equals("IN_PROGRESS")) {
            return new Claim.InProgress();
        }
        return new Claim.Replay(row.responseStatus(), row.responseBody());
    }

    @Transactional
    public void complete(UUID applicationId, String key, int status, String body) {
        int updated = jdbc.sql("""
                        UPDATE idempotency_keys
                        SET state = 'COMPLETED', response_status = :status, response_body = :body, completed_at = now()
                        WHERE application_id = :app AND idempotency_key = :key AND state = 'IN_PROGRESS'""")
                .param("app", applicationId)
                .param("key", key)
                .param("status", status)
                .param("body", body)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("idempotency key not in progress: " + key);
        }
    }

    /** Deletes completed keys older than {@code retention}. IN_PROGRESS keys are kept for inspection. */
    @Transactional
    public int purgeCompletedOlderThan(Duration retention) {
        return jdbc.sql("""
                        DELETE FROM idempotency_keys
                        WHERE state = 'COMPLETED' AND created_at < now() - make_interval(secs => :secs)""")
                .param("secs", retention.toSeconds())
                .update();
    }
}
