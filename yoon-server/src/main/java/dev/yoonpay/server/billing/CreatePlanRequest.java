package dev.yoonpay.server.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** {@code POST /v1/plans}. */
public record CreatePlanRequest(
        @NotNull @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,62}") String slug,
        @NotBlank @Size(max = 200) String name,
        JsonNode features,
        JsonNode metadata,
        @Size(max = 20) List<@Valid PriceInput> prices,
        @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(90) Integer trialDays) {
}
