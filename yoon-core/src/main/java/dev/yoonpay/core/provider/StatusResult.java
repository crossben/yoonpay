package dev.yoonpay.core.provider;

import dev.yoonpay.core.money.Money;

/**
 * A status query answer, or a status a webhook claims. {@code confirmedAmount} is what the
 * provider says was actually paid — settlement checks it against the requested amount.
 *
 * @param status          mapped status; null means "could not ask / no answer" (keep waiting)
 * @param rawStatus       provider's own value, stored in the event log
 * @param confirmedAmount null when the provider does not report it
 */
public record StatusResult<S extends Enum<S>>(S status, String rawStatus, Money confirmedAmount) {
}
