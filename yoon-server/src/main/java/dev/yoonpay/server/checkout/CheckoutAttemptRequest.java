package dev.yoonpay.server.checkout;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /checkout/api/{id}/attempts}: the customer's choice on the hosted page. */
public record CheckoutAttemptRequest(
        @NotBlank @Size(max = 32) String method,
        @Size(max = 32) String phone,
        @Size(max = 64) String piAlias) {
}
