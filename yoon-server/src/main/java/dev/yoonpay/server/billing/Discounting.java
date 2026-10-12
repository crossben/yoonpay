package dev.yoonpay.server.billing;

import dev.yoonpay.core.billing.Discounts;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Coupons on subscriptions and invoices (ADR-0029). Redeeming locks the coupon row (after the
 * subscription: billing lock order subscription → coupon → invoice → payment) so the cap holds
 * under concurrency. A discount use is consumed when an invoice is paid, not when it is created:
 * a voided invoice never costs the customer a discounted period.
 */
@Component
public class Discounting {

    /** What comes off one invoice, and the coupon it comes from. */
    public record Applied(long amountOff, String couponId) {
    }

    private final CouponRepository coupons;
    private final CatalogRepository catalog;
    private final Clock clock;

    public Discounting(CouponRepository coupons, CatalogRepository catalog, Clock clock) {
        this.coupons = coupons;
        this.catalog = catalog;
        this.clock = clock;
    }

    /**
     * Redeems {@code code} on a subscription the caller has locked; returns the subscription columns
     * to set. Refused with {@code 422 coupon_not_applicable} and a {@code reason}.
     */
    public Map<String, Object> redeem(AppPrincipal app, SubscriptionRecord locked, String code) {
        CouponRecord found = coupons.findByCode(app.id(), code).orElseThrow(() -> refused("unknown", "No coupon " + code));
        CouponRecord c = coupons.lock(app.id(), found.id()).orElseThrow();
        PriceRecord price = catalog.findPrice(app.id(), locked.priceId()).orElseThrow();
        if (!c.active()) {
            throw refused("archived", "This coupon is no longer offered");
        }
        if (c.redeemBy() != null && !clock.instant().isBefore(c.redeemBy())) {
            throw refused("expired", "This coupon could be redeemed until " + c.redeemBy());
        }
        if (c.maxRedemptions() != null && c.timesRedeemed() >= c.maxRedemptions()) {
            throw refused("exhausted", "This coupon has been used the maximum number of times");
        }
        if (!eligible(c, price)) {
            throw refused(c.currency() != null && !c.currency().equals(price.currency()) ? "currency" : "plan",
                    "This coupon does not apply to this plan or currency");
        }
        coupons.recordRedemption(c, locked.id(), locked.customerId());
        Map<String, Object> fields = new HashMap<>();
        fields.put("coupon_id", c.id());
        fields.put("discount_periods_remaining", Discounts.initialPeriods(
                Discounts.Duration.valueOf(c.duration()), c.durationInPeriods()));
        return fields;
    }

    /** The discount on a new invoice at {@code price}, if the subscription has one that applies to it. */
    public Optional<Applied> forInvoice(SubscriptionRecord current, PriceRecord price) {
        if (current.couponId() == null || !Discounts.applies(current.discountPeriodsRemaining())) {
            return Optional.empty();
        }
        CouponRecord c = coupons.find(current.applicationId(), current.couponId()).orElseThrow();
        if (!eligible(c, price)) {
            return Optional.empty(); // e.g. changed to a plan the coupon is not for: the use is kept for later
        }
        long off = new Discounts.Discount(c.percentOff(), c.amountOff()).amountOff(price.amount());
        return off == 0 ? Optional.empty() : Optional.of(new Applied(off, c.id()));
    }

    /** Subscription columns after {@code paid} was paid: one use consumed if the invoice was discounted. */
    public Map<String, Object> consume(SubscriptionRecord s, InvoiceRecord paid) {
        Map<String, Object> fields = new HashMap<>();
        if (paid.couponId() == null || !paid.couponId().equals(s.couponId()) || s.discountPeriodsRemaining() == null) {
            return fields; // not discounted, coupon replaced since, or forever
        }
        Integer left = Discounts.afterInvoice(s.discountPeriodsRemaining());
        if (Discounts.applies(left)) {
            fields.put("discount_periods_remaining", left);
        } else {
            fields.put("coupon_id", null);
            fields.put("discount_periods_remaining", null);
        }
        return fields;
    }

    private static boolean eligible(CouponRecord c, PriceRecord price) {
        if (c.currency() != null && !c.currency().equals(price.currency())) {
            return false;
        }
        return c.planIds() == null || Arrays.asList(c.planIds().split(",")).contains(price.planId());
    }

    private static ApiProblem refused(String reason, String detail) {
        return ApiProblem.unprocessable("coupon_not_applicable", detail).with("reason", reason);
    }
}
