package dev.yoonpay.core.ledger;

/**
 * Shadow-ledger account names, per provider. The money itself sits in the
 * application's own merchant accounts at each provider; these accounts mirror it.
 */
public final class Accounts {

    private Accounts() {
    }

    /** External counterpart: money on the customer's side. */
    public static String customerFunds(String provider) {
        return "customer_funds:" + provider;
    }

    public static String providerBalance(String provider) {
        return "provider_balance:" + provider;
    }

    public static String providerFees(String provider) {
        return "provider_fees:" + provider;
    }

    public static String payoutReserved(String provider) {
        return "payout_reserved:" + provider;
    }
}
