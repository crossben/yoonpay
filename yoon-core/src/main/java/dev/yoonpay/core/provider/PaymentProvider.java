package dev.yoonpay.core.provider;

import dev.yoonpay.core.lifecycle.PaymentStatus;
import dev.yoonpay.core.lifecycle.PayoutStatus;
import dev.yoonpay.core.lifecycle.RefundStatus;

import java.util.Optional;

/**
 * The provider SPI. One implementation per provider module. If adding a provider needs a
 * change to core, the SPI is wrong: fix the SPI rather than special-casing a provider.
 *
 * <p>Implementations call the provider over plain HTTP (no vendor SDK), set timeouts on every call, and
 * never retry mutating calls on their own.
 */
public interface PaymentProvider {

    ProviderId id();

    Capabilities capabilities();

    CallOutcome collect(CollectRequest request);

    StatusResult<PaymentStatus> status(ProviderReference payment);

    CallOutcome refund(RefundRequest request);

    StatusResult<RefundStatus> refundStatus(ProviderReference refund);

    CallOutcome payout(PayoutRequest request);

    StatusResult<PayoutStatus> payoutStatus(ProviderReference payout);

    /** Signature check only — never trusted alone. */
    WebhookVerification verify(InboundWebhook hook);

    /**
     * Finds what the provider created for Yoon's {@code attemptReference} when the answer to the
     * original call was lost. Empty when the provider has no such lookup or knows nothing of it;
     * the sweep then keeps waiting and eventually escalates.
     */
    default Optional<ProviderReference> lookup(Operation operation, String attemptReference) {
        return Optional.empty();
    }
}
