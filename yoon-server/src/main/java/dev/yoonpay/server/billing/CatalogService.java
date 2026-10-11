package dev.yoonpay.server.billing;

import dev.yoonpay.core.id.Ids;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Plans and prices (ADR-0025): archived, never deleted; prices immutable. */
@Service
public class CatalogService {

    private static final Set<String> PLAN_PATCHABLE = Set.of("name", "active", "features", "metadata");

    private final CatalogRepository catalog;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public CatalogService(CatalogRepository catalog, TransactionTemplate tx, ObjectMapper json) {
        this.catalog = catalog;
        this.tx = tx;
        this.json = json;
    }

    public PlanResponse createPlan(AppPrincipal app, CreatePlanRequest req) {
        PlanRecord plan = new PlanRecord(Ids.next("plan"), app.id(), req.slug(), req.name().trim(), true,
                Metadata.features(req.features(), json), Metadata.metadata(req.metadata(), json), null, null);
        List<PriceInput> prices = req.prices() == null ? List.of() : req.prices();
        prices.forEach(CatalogService::checkCurrency);
        try {
            tx.executeWithoutResult(s -> {
                catalog.insertPlan(plan);
                for (PriceInput p : prices) {
                    catalog.insertPrice(price(app, plan.id(), p));
                }
            });
        } catch (DuplicateKeyException e) {
            String existing = catalog.findPlanBySlug(app.id(), req.slug()).map(PlanRecord::id).orElse("?");
            throw new ApiProblem(HttpStatus.CONFLICT, "plan_exists", "A plan with this slug already exists: " + existing);
        }
        return getPlan(app, plan.id());
    }

    public PlanResponse getPlan(AppPrincipal app, String id) {
        PlanRecord p = catalog.findPlan(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Plan", id));
        return PlanResponse.of(p, catalog.pricesOf(List.of(p.id())), json);
    }

    public List<PlanResponse> withPrices(List<PlanRecord> plans) {
        List<PriceRecord> prices = catalog.pricesOf(plans.stream().map(PlanRecord::id).toList());
        return plans.stream()
                .map(p -> PlanResponse.of(p, prices.stream().filter(x -> x.planId().equals(p.id())).toList(), json))
                .toList();
    }

    public PlanResponse updatePlan(AppPrincipal app, String id, JsonNode body) {
        PlanRecord p = catalog.findPlan(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Plan", id));
        if (body == null || !body.isObject()) {
            throw ApiProblem.invalid("the body must be a JSON object");
        }
        for (String field : body.propertyNames()) {
            if (!PLAN_PATCHABLE.contains(field)) {
                throw ApiProblem.invalid("unknown or read-only field: " + field);
            }
        }
        String name = p.name();
        if (body.has("name")) {
            JsonNode n = body.get("name");
            if (!n.isString() || n.asString().isBlank() || n.asString().length() > 200) {
                throw ApiProblem.invalid("name must be 1 to 200 characters");
            }
            name = n.asString().trim();
        }
        boolean active = p.active();
        if (body.has("active")) {
            if (!body.get("active").isBoolean()) {
                throw ApiProblem.invalid("active must be true or false");
            }
            active = body.get("active").asBoolean();
        }
        catalog.updatePlan(new PlanRecord(p.id(), p.applicationId(), p.slug(), name, active,
                body.has("features") ? Metadata.features(body.get("features"), json) : p.features(),
                body.has("metadata") ? Metadata.metadata(body.get("metadata"), json) : p.metadata(),
                p.createdAt(), p.updatedAt()));
        return getPlan(app, id);
    }

    public PriceRecord createPrice(AppPrincipal app, String planId, PriceInput input) {
        catalog.findPlan(app.id(), planId).orElseThrow(() -> ApiProblem.notFound("Plan", planId));
        checkCurrency(input);
        PriceRecord p = price(app, planId, input);
        catalog.insertPrice(p);
        return catalog.findPrice(app.id(), p.id()).orElseThrow();
    }

    public PriceRecord setPriceActive(AppPrincipal app, String id, Boolean active) {
        catalog.findPrice(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Price", id));
        if (active == null) {
            throw ApiProblem.invalid("active is required");
        }
        catalog.setPriceActive(app.id(), id, active);
        return catalog.findPrice(app.id(), id).orElseThrow();
    }

    private static PriceRecord price(AppPrincipal app, String planId, PriceInput p) {
        return new PriceRecord(Ids.next("price"), app.id(), planId, p.currency(), p.amount(),
                p.interval().toUpperCase(Locale.ROOT), p.intervalCount() == null ? 1 : p.intervalCount(), true, null);
    }

    private static void checkCurrency(PriceInput p) {
        try {
            Currency.getInstance(p.currency());
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("unknown currency " + p.currency());
        }
    }
}
