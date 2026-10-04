package dev.yoonpay.server.payment;

import dev.yoonpay.server.phone.Phones;

import java.time.Instant;

/** A payment as the API returns it. Phone numbers are masked; the checkout token appears only inside {@code checkout_url}. */
public record PaymentResponse(
        String id,
        String object,
        String status,
        long amount,
        long amountRefunded,
        String currency,
        String country,
        String method,
        String checkout,
        Instant checkoutExpiresAt,
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

    /** A hosted checkout's method before the customer chooses: never null on the wire (0.1.0 clients expect a string). */
    public static final String NOT_CHOSEN = "any";

    public static PaymentResponse of(PaymentRecord p) {
        return new PaymentResponse(p.id(), "payment", p.status().toLowerCase(), p.amount(), p.amountRefunded(),
                p.currency(), p.country(), p.method() == null ? NOT_CHOSEN : p.method(),
                p.checkout() == null ? PaymentRecord.DIRECT : p.checkout(), p.checkoutExpiresAt(),
                p.reference(), p.description(),
                p.customerPhone() == null ? null : new Customer(Phones.mask(p.customerPhone())),
                p.provider(), p.providerReference(),
                // Hosted: the customer always goes to the Yoon page, which forwards to the provider.
                p.hosted() ? p.hostedCheckoutUrl() : p.checkoutUrl(),
                p.instructions(), p.routingReason(),
                p.failureCode() == null ? null : new Failure(p.failureCode(), p.failureMessage()),
                p.createdAt(), p.updatedAt());
    }
}
