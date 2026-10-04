package dev.yoonpay.server.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/payments}: what to collect, not who should collect it. {@code method} is required
 * unless {@code checkout} is {@code hosted} (ADR-0024), where the customer picks it.
 */
public record CreatePaymentRequest(
        @NotNull @Positive Long amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @Pattern(regexp = "[A-Z]{2}") String country,
        @Size(min = 1, max = 32) String method,
        @Valid Customer customer,
        @Size(max = 255) String reference,
        @Size(max = 500) String description,
        @Size(max = 2048) String returnUrl,
        @Size(max = 32) String provider,
        @Pattern(regexp = "direct|hosted") String checkout) {

    public boolean hosted() {
        return "hosted".equals(checkout);
    }

    /** @param piAlias the customer's PI-SPI payment alias (provider {@code pispi}) */
    public record Customer(@Size(max = 32) String phone, @Size(max = 64) String piAlias) {
    }
}
