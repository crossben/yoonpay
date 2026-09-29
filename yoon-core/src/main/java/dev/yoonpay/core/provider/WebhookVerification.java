package dev.yoonpay.core.provider;

/**
 * Result of checking a callback's signature. Never trusted alone: even a valid
 * callback only tells Yoon which payment to re-confirm through the status API.
 *
 * @param signatureValid whether the signature matched
 * @param reference      the provider reference the callback is about, if it could be read;
 *                       used only to find Yoon's record, never to re-confirm
 */
public record WebhookVerification(boolean signatureValid, ProviderReference reference) {
}
