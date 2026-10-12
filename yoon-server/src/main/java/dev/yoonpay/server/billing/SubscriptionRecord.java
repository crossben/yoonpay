package dev.yoonpay.server.billing;

import java.time.Instant;
import java.util.UUID;

/** A {@code subscriptions} row (ADR-0026). */
public record SubscriptionRecord(
        String id,
        UUID applicationId,
        String customerId,
        String priceId,
        String status,
        String collectionMethod,
        Integer billingAnchor,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        int gracePeriodDays,
        boolean cancelAtPeriodEnd,
        String pendingPriceId,
        Instant canceledAt,
        boolean comp,
        Instant remindedPeriodEnd,
        String metadata,
        Instant createdAt,
        Instant updatedAt,
        Instant trialEnd) {
}
