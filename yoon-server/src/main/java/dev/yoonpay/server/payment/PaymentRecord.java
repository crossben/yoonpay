package dev.yoonpay.server.payment;

import java.time.Instant;
import java.util.UUID;

/** A {@code payments} row. */
public record PaymentRecord(
        String id,
        UUID applicationId,
        String status,
        long amount,
        String currency,
        String country,
        String method,
        String reference,
        String description,
        String customerPhone,
        String returnUrl,
        String provider,
        String providerReference,
        String checkoutUrl,
        String instructions,
        String routingReason,
        String failureCode,
        String failureMessage,
        long amountRefunded,
        Instant createdAt,
        Instant updatedAt,
        int statusChecks) {
}
