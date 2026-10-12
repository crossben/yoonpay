package dev.yoonpay.server.billing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** A coupon as the API returns it (ADR-0029). */
public record CouponResponse(String id, String object, String code, Integer percentOff, CreateCouponRequest.AmountOff amountOff,
                             String duration, Integer durationInPeriods, Integer maxRedemptions, int timesRedeemed,
                             Instant redeemBy, List<String> plans, boolean active, JsonNode metadata, Instant createdAt) {

    public static CouponResponse of(CouponRecord c, ObjectMapper json) {
        return new CouponResponse(c.id(), "coupon", c.code(), c.percentOff(),
                c.amountOff() == null ? null : new CreateCouponRequest.AmountOff(c.currency(), c.amountOff()),
                c.duration().toLowerCase(Locale.ROOT), c.durationInPeriods(), c.maxRedemptions(), c.timesRedeemed(),
                c.redeemBy(), c.planIds() == null ? null : Arrays.asList(c.planIds().split(",")), c.active(),
                Metadata.read(c.metadata(), json), c.createdAt());
    }
}
