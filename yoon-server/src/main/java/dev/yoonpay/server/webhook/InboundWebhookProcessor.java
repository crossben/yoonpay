package dev.yoonpay.server.webhook;

import dev.yoonpay.core.provider.InboundWebhook;
import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.core.provider.WebhookVerification;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.lifecycle.StatusEvents.Cause;
import dev.yoonpay.server.payment.PaymentRecord;
import dev.yoonpay.server.payout.PayoutRecord;
import dev.yoonpay.server.provider.ProviderRegistry;
import dev.yoonpay.server.refund.RefundRecord;
import dev.yoonpay.server.settlement.Settlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Processes stored callbacks. Rows are claimed with a short lease ({@code SKIP LOCKED}), so
 * several nodes never process the same one. Processing is idempotent: a duplicate callback
 * reaches the same state machine, which ignores it.
 */
@Component
public class InboundWebhookProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboundWebhookProcessor.class);

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Applications applications;
    private final ProviderRegistry providers;
    private final Settlement settlement;

    public InboundWebhookProcessor(JdbcClient jdbc, ObjectMapper json, Applications applications,
                                   ProviderRegistry providers, Settlement settlement) {
        this.jdbc = jdbc;
        this.json = json;
        this.applications = applications;
        this.providers = providers;
        this.settlement = settlement;
    }

    record Row(long id, UUID applicationId, String provider, String headers, byte[] body) {
    }

    /** Processes up to {@code batch} waiting callbacks; returns how many. */
    public int processPending(int batch) {
        List<Row> rows = jdbc.sql("""
                        UPDATE inbound_webhooks SET locked_until = now() + interval '2 minutes'
                        WHERE id IN (SELECT id FROM inbound_webhooks
                                     WHERE processed_at IS NULL AND (locked_until IS NULL OR locked_until < now())
                                     ORDER BY id LIMIT :batch FOR UPDATE SKIP LOCKED)
                        RETURNING id, application_id, provider, headers, body""")
                .param("batch", batch).query(Row.class).list();
        for (Row row : rows) {
            String result;
            try {
                result = process(row);
            } catch (RuntimeException e) {
                log.error("Inbound webhook {} failed", row.id(), e);
                result = "error";
            }
            jdbc.sql("UPDATE inbound_webhooks SET processed_at = now(), result = :r, locked_until = NULL WHERE id = :id")
                    .param("r", result).param("id", row.id()).update();
        }
        return rows.size();
    }

    private String process(Row row) {
        Optional<AppPrincipal> app = applications.find(row.applicationId());
        Optional<PaymentProvider> provider = app.flatMap(a -> providers.find(a, row.provider()));
        if (provider.isEmpty()) {
            return "provider_not_configured";
        }
        Map<String, List<String>> headers = json.readValue(row.headers(), new TypeReference<>() {
        });
        WebhookVerification v = provider.get().verify(new InboundWebhook(headers, row.body()));
        if (!v.signatureValid()) {
            // Header names only: values may be secrets. The sweep will still ask the provider.
            log.warn("Inbound webhook {} from {}: invalid signature (headers present: {})", row.id(), row.provider(), headers.keySet());
            return "invalid_signature";
        }
        if (v.reference() == null) {
            return "no_reference";
        }
        String ref = v.reference().value();
        AppPrincipal a = app.get();

        // Lookup is scoped to the application in the URL and the provider: a callback can only
        // point at that application's own records. Re-confirmation uses the stored reference.
        Optional<PaymentRecord> payment = jdbc.sql("""
                        SELECT p.*, 0 AS amount_refunded FROM payments p
                        WHERE p.application_id = :app AND p.provider = :provider AND p.provider_reference = :ref""")
                .param("app", a.id()).param("provider", row.provider()).param("ref", ref)
                .query(PaymentRecord.class).optional();
        if (payment.isPresent()) {
            return "payment:" + settlement.settlePayment(a, payment.get(), Cause.webhook).name().toLowerCase();
        }
        Optional<RefundRecord> refund = jdbc.sql("""
                        SELECT * FROM refunds WHERE application_id = :app AND provider = :provider AND provider_reference = :ref""")
                .param("app", a.id()).param("provider", row.provider()).param("ref", ref)
                .query(RefundRecord.class).optional();
        if (refund.isPresent()) {
            return "refund:" + settlement.settleRefund(a, refund.get(), Cause.webhook).name().toLowerCase();
        }
        Optional<PayoutRecord> payout = jdbc.sql("""
                        SELECT * FROM payouts WHERE application_id = :app AND provider = :provider AND provider_reference = :ref""")
                .param("app", a.id()).param("provider", row.provider()).param("ref", ref)
                .query(PayoutRecord.class).optional();
        if (payout.isPresent()) {
            return "payout:" + settlement.settlePayout(a, payout.get(), Cause.webhook).name().toLowerCase();
        }
        return "not_found";
    }
}
