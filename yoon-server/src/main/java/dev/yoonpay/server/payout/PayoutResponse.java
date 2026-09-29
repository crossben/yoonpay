package dev.yoonpay.server.payout;

import dev.yoonpay.server.payment.PaymentResponse.Failure;
import dev.yoonpay.server.phone.Phones;

import java.time.Instant;

public record PayoutResponse(
        String id,
        String object,
        String status,
        long amount,
        String currency,
        String country,
        String method,
        String reference,
        Recipient recipient,
        String provider,
        String providerReference,
        String routingReason,
        boolean needsReview,
        Failure failure,
        Instant createdAt,
        Instant updatedAt) {

    public record Recipient(String phone) {
    }

    public static PayoutResponse of(PayoutRecord p) {
        return new PayoutResponse(p.id(), "payout", p.status().toLowerCase(), p.amount(), p.currency(), p.country(),
                p.method(), p.reference(), new Recipient(Phones.mask(p.recipientPhone())), p.provider(),
                p.providerReference(), p.routingReason(), p.needsReview(),
                p.failureCode() == null ? null : new Failure(p.failureCode(), p.failureMessage()),
                p.createdAt(), p.updatedAt());
    }
}
