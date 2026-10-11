package dev.yoonpay.server.billing;

import java.time.Instant;
import java.util.UUID;

/** An {@code invoices} row (ADR-0027): a billing record, not a tax invoice. */
public record InvoiceRecord(
        String id,
        UUID applicationId,
        String number,
        String customerId,
        String subscriptionId,
        String priceId,
        String kind,
        String status,
        long amountDue,
        String currency,
        Instant periodStart,
        Instant periodEnd,
        String paymentId,
        String receiptToken,
        Instant paidAt,
        Instant voidedAt,
        Instant createdAt,
        Instant updatedAt) {
}
