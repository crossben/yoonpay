package dev.yoonpay.server.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * A {@code payments} row.
 *
 * @param checkout          {@code direct} or {@code hosted} (ADR-0024)
 * @param checkoutToken     hosted only: the page's capability; never logged
 * @param hostedCheckoutUrl hosted only: the Yoon page, token included
 * @param checkoutMethod    hosted only: the one method the application allowed, or null for any
 * @param checkoutBusy      hosted only: an attempt round may be in flight
 */
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
        int statusChecks,
        String customerPiAlias,
        String checkout,
        String checkoutToken,
        String hostedCheckoutUrl,
        Instant checkoutExpiresAt,
        String checkoutMethod,
        boolean checkoutBusy) {

    public static final String DIRECT = "direct";
    public static final String HOSTED = "hosted";

    public boolean hosted() {
        return HOSTED.equals(checkout);
    }

    @Override
    public String toString() {
        return "PaymentRecord[id=" + id + ", status=" + status + ", checkout=" + checkout + "]";
    }
}
