package dev.yoonpay.server.api;

import dev.yoonpay.testkit.Behaviour;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static dev.yoonpay.server.TestProviders.FAKE_TWO;
import static org.assertj.core.api.Assertions.assertThat;

class PayoutApiTest extends ApiTest {

    private static Map<String, Object> payout(long amount) {
        return Map.of("amount", amount, "currency", "XOF", "country", "SN", "method", "wave",
                "recipient", Map.of("phone", "771234567"), "reference", "wd_" + UUID.randomUUID());
    }

    @Test
    void an_accepted_payout_is_processing_and_reserved_in_the_ledger() {
        long before = reserved();

        Response r = post("shop", "/v1/payouts", payout(10000));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text("status")).isEqualTo("processing");
        assertThat(r.json().path("recipient").path("phone").asString()).isEqualTo("+22177***67");
        assertThat(reserved()).isEqualTo(before + 10000);
    }

    @Test
    void a_lost_answer_is_unknown_never_retried_and_never_sent_to_another_provider() {
        FAKE_ONE.script(Behaviour.timeoutAfterAccept());
        long before = reserved();

        Response r = post("shop", "/v1/payouts", payout(7000));

        assertThat(r.text("status")).isEqualTo("unknown");
        assertThat(FAKE_ONE.mutatingCalls()).isEqualTo(1);
        assertThat(FAKE_TWO.mutatingCalls()).isZero();
        assertThat(reserved()).as("the money may have left: keep it reserved").isEqualTo(before + 7000);
    }

    @Test
    void even_a_provider_that_is_down_does_not_fail_over() {
        FAKE_ONE.setDown(true);

        Response r = post("shop", "/v1/payouts", payout(5000));

        assertThat(r.text("status")).isEqualTo("failed");
        assertThat(r.text("provider")).isEqualTo("fakeone");
        assertThat(FAKE_TWO.mutatingCalls()).isZero();
    }

    @Test
    void a_rejected_payout_reserves_nothing() {
        FAKE_ONE.script(Behaviour.reject("RECIPIENT_BLOCKED"));
        long before = reserved();

        Response r = post("shop", "/v1/payouts", payout(3000));

        assertThat(r.text("status")).isEqualTo("failed");
        assertThat(reserved()).isEqualTo(before);
    }

    @Test
    void a_retried_request_pays_once() {
        var body = payout(4000);

        Response a = post("shop", "/v1/payouts", body, "payout-key-1");
        Response b = post("shop", "/v1/payouts", body, "payout-key-1");

        assertThat(b.body()).isEqualTo(a.body());
        assertThat(FAKE_ONE.mutatingCalls()).isEqualTo(1);
    }

    @Test
    void payouts_are_listed_fetched_have_a_history_and_stay_private() {
        String id = post("shop", "/v1/payouts", payout(1000)).text("id");

        assertThat(get("shop", "/v1/payouts/" + id).text("status")).isEqualTo("processing");
        assertThat(get("shop", "/v1/payouts?needs_review=false&limit=5").json().path("data").size()).isGreaterThanOrEqualTo(1);
        assertThat(get("shop", "/v1/payouts/" + id + "/events").json().size()).isEqualTo(2);
        assertThat(get("other", "/v1/payouts/" + id).status()).isEqualTo(404);
    }

    private long reserved() {
        var balances = get("shop", "/v1/balances").json().path("data");
        for (var b : balances) {
            if (b.path("account").asString().equals("payout_reserved:fakeone")) {
                return b.path("amount").asLong();
            }
        }
        return 0;
    }
}
