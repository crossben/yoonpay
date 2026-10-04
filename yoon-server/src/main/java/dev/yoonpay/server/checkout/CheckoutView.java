package dev.yoonpay.server.checkout;

import dev.yoonpay.server.payment.PaymentRecord;
import dev.yoonpay.server.payment.PaymentService.MethodOption;
import dev.yoonpay.server.phone.Phones;

import java.time.Instant;
import java.util.List;

/**
 * What the public checkout page may know about a hosted payment (ADR-0024): no application,
 * provider reference, routing note, token or other object. The phone is masked.
 */
public record CheckoutView(
        String id,
        String status,
        long amount,
        String currency,
        String country,
        String description,
        String method,
        List<MethodOption> methods,
        boolean canChoose,
        NextAction nextAction,
        String customerPhone,
        boolean hasPiAlias,
        String returnUrl,
        Instant expiresAt,
        LastError lastError) {

    /** {@code redirect} to the provider's page, or {@code instructions} to show (push / USSD). */
    public record NextAction(String type, String url, String instructions) {
    }

    public record LastError(String code) {
    }

    static CheckoutView of(PaymentRecord p, List<MethodOption> methods, boolean canChoose, String lastError) {
        NextAction next = null;
        if (p.status().equals("PENDING")) {
            if (p.checkoutUrl() != null) {
                next = new NextAction("redirect", p.checkoutUrl(), p.instructions());
            } else if (p.instructions() != null) {
                next = new NextAction("instructions", null, p.instructions());
            }
        }
        return new CheckoutView(p.id(), p.status().toLowerCase(java.util.Locale.ROOT), p.amount(), p.currency(), p.country(),
                p.description(), p.method(), methods, canChoose, next, Phones.mask(p.customerPhone()),
                p.customerPiAlias() != null, p.returnUrl(), p.checkoutExpiresAt(),
                lastError == null ? null : new LastError(lastError));
    }
}
