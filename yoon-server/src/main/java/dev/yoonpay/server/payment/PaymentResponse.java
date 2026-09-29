package dev.yoonpay.server.payment;

import dev.yoonpay.server.phone.Phones;

import java.time.Instant;

/** A payment as the API returns it. Phone numbers are masked. */
public record PaymentResponse(
        String id,
        String object,
        String status,
        long amount,
        long amountRefunded,
        String currency,
        String country,
        String method,
        String reference,
        String description,
        Customer customer,
        String provider,
        String providerReference,
        String checkoutUrl,
        String instructions,
        String routingReason,
        Failure failure,
        Instant createdAt,
        Instant updatedAt) {

    public record Customer(String phone) {
    }

    public record Failure(String code, String message) {
    }

    public static PaymentResponse of(PaymentRecord p) {
        return new PaymentResponse(p.id(), "payment", p.status().toLowerCase(), p.amount(), p.amountRefunded(),
                p.currency(), p.country(), p.method(), p.reference(), p.description(),
                p.customerPhone() == null ? null : new Customer(Phones.mask(p.customerPhone())),
                p.provider(), p.providerReference(), p.checkoutUrl(), p.instructions(), p.routingReason(),
                p.failureCode() == null ? null : new Failure(p.failureCode(), p.failureMessage()),
                p.createdAt(), p.updatedAt());
    }
}
