package dev.yoonpay.server.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** {@code POST /v1/coupons} (ADR-0029). Exactly one of {@code percent_off} and {@code amount_off}. */
public record CreateCouponRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{3,32}") String code,
        @Min(1) @Max(100) Integer percentOff,
        @Valid AmountOff amountOff,
        @NotNull @Pattern(regexp = "once|repeating|forever") String duration,
        @Min(1) @Max(120) Integer durationInPeriods,
        @Positive Integer maxRedemptions,
        Instant redeemBy,
        @Size(min = 1, max = 50) List<@NotNull String> plans,
        JsonNode metadata) {

    public record AmountOff(@NotNull @Pattern(regexp = "[A-Z]{3}") String currency, @NotNull @Positive Long amount) {
    }
}
