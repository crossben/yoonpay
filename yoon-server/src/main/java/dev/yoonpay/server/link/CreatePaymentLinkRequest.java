package dev.yoonpay.server.link;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/** {@code POST /v1/payment_links} (ADR-0030): a fixed {@code amount}, or none and optional bounds. */
public record CreatePaymentLinkRequest(
        @Positive Long amount,
        @Positive Long minAmount,
        @Positive Long maxAmount,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @Pattern(regexp = "[A-Z]{2}") String country,
        @NotBlank @Size(max = 500) String description,
        @Size(max = 32) String method,
        @Positive @Max(1_000_000) Integer maxPayments,
        Instant expiresAt,
        @Size(max = 2048) String returnUrl,
        JsonNode metadata) {
}
