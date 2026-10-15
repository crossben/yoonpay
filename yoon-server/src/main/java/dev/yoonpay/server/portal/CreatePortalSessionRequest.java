package dev.yoonpay.server.portal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** {@code POST /v1/customers/{id}/portal_sessions} (ADR-0031). Without {@code allow}: view and pay only. */
public record CreatePortalSessionRequest(@Size(max = 2048) String returnUrl, @Valid Allow allow) {

    /** What the customer may do beyond viewing and paying. */
    public record Allow(Boolean cancel, @Size(max = 20) List<@NotNull String> changePrices, Boolean coupons) {
    }
}
