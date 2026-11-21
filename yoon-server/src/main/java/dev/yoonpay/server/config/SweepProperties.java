package dev.yoonpay.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Reconciliation timing ({@code YOON_SWEEP_*}).
 *
 * @param pendingAfter     how long a payment/refund/payout may wait before the sweep asks the provider
 * @param paymentTtl       after this, a payment the provider still reports unpaid becomes EXPIRED
 * @param maxNoAnswer      status queries without an answer before an old payment is expired
 * @param payoutReviewAfter an open payout older than this is flagged for human review
 * @param refundReviewAfter an open refund older than this is flagged for human review
 * @param interruptedAfter a refund or payout still CREATED this long after creation was interrupted
 *                         (the process stopped around the provider call): its outcome is unknown
 * @param recheckAfter     a failed/expired payment is re-checked once, this long after it ended…
 * @param recheckWindow    …if it ended less than this long ago (catches late successes)
 * @param inboundRetention raw inbound webhooks are deleted after this
 * @param idempotencyRetention completed idempotency keys are deleted after this
 */
@ConfigurationProperties("yoon.sweep")
public record SweepProperties(
        Duration pendingAfter,
        Duration paymentTtl,
        Integer maxNoAnswer,
        Duration payoutReviewAfter,
        Duration refundReviewAfter,
        Duration interruptedAfter,
        Duration recheckAfter,
        Duration recheckWindow,
        Duration inboundRetention,
        Duration idempotencyRetention) {

    public SweepProperties {
        pendingAfter = or(pendingAfter, Duration.ofMinutes(1));
        paymentTtl = or(paymentTtl, Duration.ofMinutes(30));
        maxNoAnswer = maxNoAnswer == null ? 5 : maxNoAnswer;
        payoutReviewAfter = or(payoutReviewAfter, Duration.ofHours(24));
        refundReviewAfter = or(refundReviewAfter, Duration.ofHours(24));
        // Longer than any provider call can take, so a call still in flight is never overtaken.
        interruptedAfter = or(interruptedAfter, Duration.ofMinutes(5));
        recheckAfter = or(recheckAfter, Duration.ofMinutes(10));
        recheckWindow = or(recheckWindow, Duration.ofHours(24));
        inboundRetention = or(inboundRetention, Duration.ofDays(90));
        idempotencyRetention = or(idempotencyRetention, Duration.ofHours(24));
    }

    private static Duration or(Duration d, Duration fallback) {
        return d == null ? fallback : d;
    }
}
