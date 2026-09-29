package dev.yoonpay.server.idempotency;

import dev.yoonpay.server.PostgresTest;
import dev.yoonpay.server.idempotency.IdempotencyStore.Claim;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyStoreTest extends PostgresTest {

    @Autowired
    IdempotencyStore store;

    private final String hash = RequestHash.of("POST", "/v1/payments", "{\"amount\":5000}".getBytes(StandardCharsets.UTF_8));

    @Test
    void first_call_starts_and_a_repeat_after_completion_replays_the_response() {
        UUID app = newApplication();

        assertThat(store.begin(app, "k1", hash)).isEqualTo(new Claim.Started());
        store.complete(app, "k1", 201, "{\"id\":\"pay_1\"}");

        assertThat(store.begin(app, "k1", hash)).isEqualTo(new Claim.Replay(201, "{\"id\":\"pay_1\"}"));
    }

    @Test
    void same_key_with_a_different_body_is_a_mismatch() {
        UUID app = newApplication();
        store.begin(app, "k1", hash);

        String other = RequestHash.of("POST", "/v1/payments", "{\"amount\":9000}".getBytes(StandardCharsets.UTF_8));

        assertThat(store.begin(app, "k1", other)).isEqualTo(new Claim.Mismatch());
    }

    @Test
    void a_repeat_while_the_first_is_running_is_told_to_wait() {
        UUID app = newApplication();
        store.begin(app, "k1", hash);

        assertThat(store.begin(app, "k1", hash)).isEqualTo(new Claim.InProgress());
    }

    @Test
    void keys_are_scoped_per_application() {
        UUID a = newApplication();
        UUID b = newApplication();

        assertThat(store.begin(a, "k1", hash)).isEqualTo(new Claim.Started());
        assertThat(store.begin(b, "k1", hash)).isEqualTo(new Claim.Started());
    }

    @Test
    void of_many_concurrent_identical_requests_exactly_one_starts() throws Exception {
        UUID app = newApplication();
        int n = 20;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Claim>> results = new ArrayList<>();

        try (var pool = Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return store.begin(app, "race", hash);
                }));
            }
            go.countDown();

            List<Claim> claims = new ArrayList<>();
            for (Future<Claim> f : results) {
                claims.add(f.get());
            }
            assertThat(claims).filteredOn(c -> c instanceof Claim.Started).hasSize(1);
            assertThat(claims).filteredOn(c -> c instanceof Claim.InProgress).hasSize(n - 1);
        }
    }

    @Test
    void completing_twice_is_a_bug_and_fails() {
        UUID app = newApplication();
        store.begin(app, "k1", hash);
        store.complete(app, "k1", 201, "{}");

        assertThatThrownBy(() -> store.complete(app, "k1", 201, "{}")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void purge_removes_old_completed_keys_but_keeps_in_progress_ones() {
        UUID app = newApplication();
        store.begin(app, "done", hash);
        store.complete(app, "done", 201, "{}");
        store.begin(app, "stuck", hash);
        jdbc.sql("UPDATE idempotency_keys SET created_at = now() - interval '2 days' WHERE application_id = :a")
                .param("a", app).update();

        store.purgeCompletedOlderThan(Duration.ofHours(24));

        assertThat(jdbc.sql("SELECT idempotency_key FROM idempotency_keys WHERE application_id = :a")
                .param("a", app).query(String.class).list()).containsExactly("stuck");
    }
}
