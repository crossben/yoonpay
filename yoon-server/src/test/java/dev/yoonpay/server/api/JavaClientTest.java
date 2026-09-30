package dev.yoonpay.server.api;

import dev.yoonpay.client.Yoon;
import dev.yoonpay.client.YoonException;
import dev.yoonpay.client.generated.model.CreatePaymentRequest;
import dev.yoonpay.client.generated.model.CreatePaymentRequestCustomer;
import dev.yoonpay.client.generated.model.CreatePayoutRequest;
import dev.yoonpay.client.generated.model.CreatePayoutRequestRecipient;
import dev.yoonpay.client.generated.model.Payment;
import dev.yoonpay.client.generated.model.PaymentStatus;
import dev.yoonpay.client.generated.model.PayoutStatus;
import dev.yoonpay.testkit.Behaviour;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static dev.yoonpay.server.TestProviders.FAKE_ONE;
import static dev.yoonpay.server.TestProviders.FAKE_TWO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generated Java client against the real server: proves the published client can read what
 * the server actually returns (nullable fields, enums, pages, problems), not just the spec.
 */
class JavaClientTest extends ApiTest {

    private Yoon client() {
        return new Yoon("http://localhost:" + port, key("shop"));
    }

    private static CreatePaymentRequest payment() {
        return new CreatePaymentRequest().amount(5000L).currency("XOF").country("SN").method("wave")
                .customer(new CreatePaymentRequestCustomer().phone("+221771234567"))
                .reference("order_" + UUID.randomUUID());
    }

    @Test
    void create_read_and_list_payments() {
        Yoon yoon = client();

        Payment created = yoon.createPayment(payment(), UUID.randomUUID().toString());
        Payment fetched = yoon.getPayment(created.getId());
        var page = yoon.call(() -> yoon.payments().listPayments(created.getReference(), null, null, null, null, null, null, null, 10));

        assertThat(created.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(created.getCustomer().getPhone()).isEqualTo("+22177***67");
        assertThat(fetched.getProvider()).isEqualTo("fakeone");
        assertThat(page.getData()).extracting(Payment::getId).containsExactly(created.getId());
        assertThat(page.getHasMore()).isFalse();
        assertThat(yoon.call(() -> yoon.payments().listPaymentEvents(created.getId()))).hasSize(2);
    }

    @Test
    void a_failed_payment_carries_its_failure() {
        FAKE_ONE.script(Behaviour.reject("DECLINED"));
        FAKE_TWO.script(Behaviour.reject("DECLINED"));

        Payment p = client().createPayment(payment(), UUID.randomUUID().toString());

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailure().getCode()).isEqualTo("declined");
    }

    @Test
    void problems_arrive_as_yoon_exceptions() {
        Yoon yoon = client();
        Payment pending = yoon.createPayment(payment(), UUID.randomUUID().toString());

        assertThatThrownBy(() -> yoon.refund(pending.getId(), UUID.randomUUID().toString(), null, null))
                .isInstanceOfSatisfying(YoonException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(422);
                    assertThat(e.problemCode()).isEqualTo("payment_not_refundable");
                });
        assertThatThrownBy(() -> new Yoon("http://localhost:" + port, "yk_wrong").getPayment(pending.getId()))
                .isInstanceOfSatisfying(YoonException.class, e -> assertThat(e.problemCode()).isEqualTo("unauthorized"));
    }

    @Test
    void payouts_events_ledger_exports_and_meta() {
        Yoon yoon = client();

        var payout = yoon.createPayout(new CreatePayoutRequest().amount(3000L).currency("XOF").country("SN").method("wave")
                .recipient(new CreatePayoutRequestRecipient().phone("+221771234567")), UUID.randomUUID().toString());

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PROCESSING);
        assertThat(yoon.call(() -> yoon.events().listEvents(null, null, null, null, 5)).getData()).isNotNull();
        assertThat(yoon.call(() -> yoon.ledger().getBalances()).getData()).isNotEmpty();
        assertThat(yoon.call(() -> yoon.ledger().listLedgerEntries(null, null, null, null, 5)).getData()).isNotEmpty();
        assertThat(yoon.exportCsv(Yoon.Export.PAYOUTS, null, null)).startsWith("id,created_at");
        assertThat(yoon.exportCsv(Yoon.Export.LEDGER, java.time.OffsetDateTime.now().minusDays(1), null)).startsWith("posting_id");
        assertThat(yoon.call(() -> yoon.meta().listProviders())).hasSize(3);
        assertThat(yoon.call(() -> yoon.meta().getAbout()).getLicense()).isEqualTo("AGPL-3.0-only");
    }
}
