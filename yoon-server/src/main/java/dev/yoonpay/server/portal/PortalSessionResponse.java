package dev.yoonpay.server.portal;

import java.time.Instant;

/** A new portal session: the URL to send the customer to, valid until {@code expiresAt}. */
public record PortalSessionResponse(String id, String object, String customer, String url, Instant expiresAt) {
}
