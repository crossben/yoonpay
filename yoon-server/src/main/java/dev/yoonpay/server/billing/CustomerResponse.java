package dev.yoonpay.server.billing;

import dev.yoonpay.server.phone.Phones;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/** A customer as the API returns it: the phone is always masked. */
public record CustomerResponse(
        String id,
        String object,
        String externalId,
        String email,
        String phone,
        String piAlias,
        String country,
        String name,
        JsonNode metadata,
        Instant createdAt,
        Instant updatedAt) {

    public static CustomerResponse of(CustomerRecord c, ObjectMapper json) {
        return new CustomerResponse(c.id(), "customer", c.externalId(), c.email(), Phones.mask(c.phone()), c.piAlias(),
                c.country(), c.name(), Metadata.read(c.metadata(), json), c.createdAt(), c.updatedAt());
    }
}
