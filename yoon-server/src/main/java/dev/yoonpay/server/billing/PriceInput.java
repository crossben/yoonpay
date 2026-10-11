package dev.yoonpay.server.billing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** A price to create: integer minor units, an interval and how many of it. */
public record PriceInput(
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @Positive Long amount,
        @NotNull @Pattern(regexp = "day|week|month|year") String interval,
        @Min(1) @Max(366) Integer intervalCount) {
}
