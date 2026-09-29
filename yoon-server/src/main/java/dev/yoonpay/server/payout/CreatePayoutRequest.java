package dev.yoonpay.server.payout;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreatePayoutRequest(
        @NotNull @Positive Long amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @Pattern(regexp = "[A-Z]{2}") String country,
        @NotBlank @Size(max = 32) String method,
        @NotNull @Valid Recipient recipient,
        @Size(max = 255) String reference,
        @Size(max = 32) String provider) {

    public record Recipient(@NotBlank @Size(max = 32) String phone) {
    }
}
