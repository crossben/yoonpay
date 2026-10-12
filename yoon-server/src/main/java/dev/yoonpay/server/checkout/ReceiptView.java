package dev.yoonpay.server.checkout;

import java.time.Instant;
import java.util.List;

/**
 * What the public receipt page may know about a paid invoice (ADR-0027): a payment receipt, not a
 * tax invoice. No customer contact details, provider reference or token.
 */
public record ReceiptView(
        String number,
        String merchant,
        String customer,
        String plan,
        String interval,
        int intervalCount,
        List<Line> lines,
        long amount,
        String currency,
        Instant periodStart,
        Instant periodEnd,
        Instant paidAt,
        String method,
        String payment,
        long subtotal,
        long discountAmount,
        String couponCode) {

    public record Line(String description, long amount) {
    }
}
