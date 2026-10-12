package dev.yoonpay.server.billing;

import dev.yoonpay.core.billing.Discounts;
import dev.yoonpay.core.id.Ids;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Coupons (ADR-0029): terms fixed at creation; archived, never deleted. */
@Service
public class CouponService {

    private static final Set<String> PATCHABLE = Set.of("active", "max_redemptions", "metadata");

    private final CouponRepository coupons;
    private final CatalogRepository catalog;
    private final ObjectMapper json;

    public CouponService(CouponRepository coupons, CatalogRepository catalog, ObjectMapper json) {
        this.coupons = coupons;
        this.catalog = catalog;
        this.json = json;
    }

    public CouponResponse create(AppPrincipal app, CreateCouponRequest req) {
        Long amount = req.amountOff() == null ? null : req.amountOff().amount();
        String currency = req.amountOff() == null ? null : req.amountOff().currency();
        Discounts.Duration duration = Discounts.Duration.valueOf(req.duration().toUpperCase(Locale.ROOT));
        try {
            new Discounts.Discount(req.percentOff(), amount);
            Discounts.initialPeriods(duration, req.durationInPeriods());
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid(e.getMessage());
        }
        if (currency != null) {
            try {
                Currency.getInstance(currency);
            } catch (IllegalArgumentException e) {
                throw ApiProblem.invalid("unknown currency " + currency);
            }
        }
        String plans = null;
        if (req.plans() != null) {
            for (String plan : new LinkedHashSet<>(req.plans())) {
                catalog.findPlan(app.id(), plan)
                        .orElseThrow(() -> ApiProblem.unprocessable("plan_not_found", "No plan " + plan));
            }
            plans = String.join(",", new LinkedHashSet<>(req.plans()));
        }
        CouponRecord c = new CouponRecord(Ids.next("co"), app.id(), req.code(), req.percentOff(), amount, currency,
                duration.name(), req.durationInPeriods(), req.maxRedemptions(), 0, req.redeemBy(), plans, true,
                Metadata.metadata(req.metadata(), json), null, null);
        try {
            coupons.insert(c);
        } catch (DuplicateKeyException e) {
            String existing = coupons.findByCode(app.id(), req.code()).map(CouponRecord::id).orElse("?");
            throw new ApiProblem(HttpStatus.CONFLICT, "coupon_exists", "A coupon with this code already exists: " + existing);
        }
        return get(app, c.id());
    }

    public CouponResponse get(AppPrincipal app, String id) {
        return CouponResponse.of(find(app, id), json);
    }

    public List<CouponResponse> list(List<CouponRecord> rows) {
        return rows.stream().map(c -> CouponResponse.of(c, json)).toList();
    }

    public CouponResponse update(AppPrincipal app, String id, JsonNode body) {
        CouponRecord c = find(app, id);
        if (body == null || !body.isObject()) {
            throw ApiProblem.invalid("the body must be a JSON object");
        }
        for (String field : body.propertyNames()) {
            if (!PATCHABLE.contains(field)) {
                throw ApiProblem.invalid("unknown or read-only field: " + field + " (coupon terms never change: "
                        + "create a new coupon and archive this one)");
            }
        }
        boolean active = c.active();
        if (body.has("active")) {
            if (!body.get("active").isBoolean()) {
                throw ApiProblem.invalid("active must be true or false");
            }
            active = body.get("active").asBoolean();
        }
        Integer max = c.maxRedemptions();
        if (body.has("max_redemptions")) {
            JsonNode m = body.get("max_redemptions");
            if (!m.isNull() && (!m.isInt() || m.asInt() < 1)) {
                throw ApiProblem.invalid("max_redemptions must be a positive integer or null (no cap)");
            }
            max = m.isNull() ? null : m.asInt();
            if (max != null && (c.maxRedemptions() == null || max < c.maxRedemptions())) {
                throw ApiProblem.invalid("max_redemptions can only be raised or removed");
            }
        }
        String metadata = body.has("metadata") ? Metadata.metadata(body.get("metadata"), json) : c.metadata();
        coupons.update(id, active, max, metadata);
        return get(app, id);
    }

    private CouponRecord find(AppPrincipal app, String id) {
        return coupons.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Coupon", id));
    }
}
