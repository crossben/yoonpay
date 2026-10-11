package dev.yoonpay.server.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** {@code POST /v1/customers}. */
public record CreateCustomerRequest(
        @NotBlank @Size(max = 255) String externalId,
        @Size(max = 254) String email,
        @Size(max = 32) String phone,
        @Size(max = 64) String piAlias,
        @NotNull @Pattern(regexp = "[A-Z]{2}") String country,
        @Size(max = 200) String name,
        JsonNode metadata) {
}
