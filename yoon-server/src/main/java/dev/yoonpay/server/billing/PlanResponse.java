package dev.yoonpay.server.billing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** A plan with all its prices (archived ones included), as the API returns it. */
public record PlanResponse(
        String id,
        String object,
        String slug,
        String name,
        boolean active,
        JsonNode features,
        JsonNode metadata,
        List<PriceResponse> prices,
        Instant createdAt,
        Instant updatedAt) {

    public record PriceResponse(
            String id,
            String object,
            String plan,
            String currency,
            long amount,
            String interval,
            int intervalCount,
            boolean active,
            Instant createdAt) {

        public static PriceResponse of(PriceRecord p) {
            return new PriceResponse(p.id(), "price", p.planId(), p.currency(), p.amount(),
                    p.interval().toLowerCase(Locale.ROOT), p.intervalCount(), p.active(), p.createdAt());
        }
    }

    public static PlanResponse of(PlanRecord p, List<PriceRecord> prices, ObjectMapper json) {
        return new PlanResponse(p.id(), "plan", p.slug(), p.name(), p.active(), Metadata.read(p.features(), json),
                Metadata.read(p.metadata(), json), prices.stream().map(PriceResponse::of).toList(),
                p.createdAt(), p.updatedAt());
    }
}
