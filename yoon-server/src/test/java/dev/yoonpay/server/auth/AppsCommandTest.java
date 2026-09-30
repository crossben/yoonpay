package dev.yoonpay.server.auth;

import dev.yoonpay.server.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AppsCommandTest extends PostgresTest {

    @Autowired
    ApiKeyService keys;

    private record Result(int code, String out) {
    }

    private Result run(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = AppsCommand.run(args, keys, new PrintStream(buf, true));
        return new Result(code, buf.toString());
    }

    private static String key(String out) {
        Matcher m = Pattern.compile("yk_[A-Za-z0-9_-]+").matcher(out);
        assertThat(m.find()).isTrue();
        return m.group();
    }

    @Test
    void a_key_can_be_rotated_without_downtime_then_revoked() {
        String name = "cli-" + UUID.randomUUID().toString().substring(0, 8);
        String first = key(run("apps", "create", name).out());

        Result added = run("apps", "add-key", name);
        String second = key(added.out());

        assertThat(keys.authenticate(first)).isPresent();
        assertThat(keys.authenticate(second)).isPresent();

        Result listed = run("apps", "list");
        assertThat(listed.out()).contains(name).contains(first.substring(0, 10) + "…").doesNotContain(first);

        assertThat(run("apps", "revoke-key", first.substring(0, 10)).code()).isZero();
        assertThat(keys.authenticate(first)).isEmpty();
        assertThat(keys.authenticate(second)).isPresent();
        assertThat(run("apps", "list").out()).contains("revoked");
    }

    @Test
    void mistakes_print_usage_and_fail() {
        assertThat(run("apps").code()).isEqualTo(2);
        assertThat(run("apps", "create").out()).contains("Usage");
        assertThat(run("apps", "add-key", "no-such-app").out()).contains("No application named");
        assertThat(run("apps", "revoke-key", "short").code()).isEqualTo(2);
        assertThat(run("apps", "revoke-key", "yk_nothing").code()).isEqualTo(1);
    }
}
