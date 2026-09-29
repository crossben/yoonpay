package dev.yoonpay.core.provider;

import java.util.Map;

/**
 * Builds a {@link PaymentProvider} for one application's credentials. Each application brings
 * its own merchant keys, so the server creates one provider instance per (application,
 * provider). Implementations validate the credentials and fail fast, naming the missing key
 * but never printing a value.
 */
public interface PaymentProviderFactory {

    ProviderId id();

    PaymentProvider create(Map<String, String> credentials);
}
