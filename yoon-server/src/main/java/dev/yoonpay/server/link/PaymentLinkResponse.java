package dev.yoonpay.server.link;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/** A payment link as the API returns it (ADR-0030). */
public record PaymentLinkResponse(String id, String object, String url, String qrUrl, Long amount, Long minAmount,
                                  Long maxAmount, String currency, String country, String description, String method,
                                  Integer maxPayments, Instant expiresAt, String returnUrl, boolean active,
                                  long paymentsCount, long amountCollected, JsonNode metadata, Instant createdAt) {
}
