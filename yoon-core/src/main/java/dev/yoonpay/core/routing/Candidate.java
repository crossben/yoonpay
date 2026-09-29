package dev.yoonpay.core.routing;

import dev.yoonpay.core.provider.Capabilities;
import dev.yoonpay.core.provider.ProviderId;

/**
 * A provider configured for the application.
 *
 * @param priority  lower is preferred
 * @param available false while the provider's circuit breaker is open
 */
public record Candidate(ProviderId id, int priority, Capabilities capabilities, boolean available) {
}
