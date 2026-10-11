package dev.yoonpay.server.billing;

import dev.yoonpay.core.id.Ids;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.phone.Phones;
import dev.yoonpay.server.web.ApiProblem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Set;

/** Customers (ADR-0025): one per application user, found by the application's own id. */
@Service
public class CustomerService {

    private static final Set<String> PATCHABLE = Set.of("email", "phone", "pi_alias", "country", "name", "metadata");

    private final CustomerRepository customers;
    private final ObjectMapper json;

    public CustomerService(CustomerRepository customers, ObjectMapper json) {
        this.customers = customers;
        this.json = json;
    }

    public CustomerRecord create(AppPrincipal app, CreateCustomerRequest req) {
        CustomerRecord c = new CustomerRecord(Ids.next("cus"), app.id(), req.externalId().trim(), blankToNull(req.email()),
                phone(req.phone(), req.country()), blankToNull(req.piAlias()), req.country(), blankToNull(req.name()),
                Metadata.metadata(req.metadata(), json), null, null);
        try {
            customers.insert(c);
        } catch (DuplicateKeyException e) {
            String existing = customers.findByExternalId(app.id(), c.externalId()).map(CustomerRecord::id).orElse("?");
            throw new ApiProblem(HttpStatus.CONFLICT, "customer_exists",
                    "A customer with this external_id already exists: " + existing);
        }
        return customers.find(app.id(), c.id()).orElseThrow();
    }

    /** PATCH semantics: fields present replace, `null` clears an optional field; `external_id` never changes. */
    public CustomerRecord update(AppPrincipal app, String id, JsonNode body) {
        CustomerRecord c = customers.find(app.id(), id).orElseThrow(() -> ApiProblem.notFound("Customer", id));
        if (body == null || !body.isObject()) {
            throw ApiProblem.invalid("the body must be a JSON object");
        }
        for (String field : body.propertyNames()) {
            if (!PATCHABLE.contains(field)) {
                throw ApiProblem.invalid("unknown or read-only field: " + field);
            }
        }
        String country = body.has("country") ? text(body, "country", 2) : c.country();
        if (country == null || !country.matches("[A-Z]{2}")) {
            throw ApiProblem.invalid("country must be an ISO 3166 alpha-2 code");
        }
        CustomerRecord updated = new CustomerRecord(c.id(), c.applicationId(), c.externalId(),
                body.has("email") ? text(body, "email", 254) : c.email(),
                body.has("phone") ? phone(text(body, "phone", 32), country) : c.phone(),
                body.has("pi_alias") ? text(body, "pi_alias", 64) : c.piAlias(),
                country,
                body.has("name") ? text(body, "name", 200) : c.name(),
                body.has("metadata") ? Metadata.metadata(body.get("metadata"), json) : c.metadata(),
                c.createdAt(), c.updatedAt());
        customers.update(updated);
        return customers.find(app.id(), id).orElseThrow();
    }

    private static String text(JsonNode body, String field, int max) {
        JsonNode v = body.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isString()) {
            throw ApiProblem.invalid(field + " must be a string");
        }
        String s = v.asString().trim();
        if (s.length() > max) {
            throw ApiProblem.invalid(field + " is longer than " + max + " characters");
        }
        return s.isEmpty() ? null : s;
    }

    private static String phone(String raw, String country) {
        return raw == null || raw.isBlank() ? null : Phones.normalize(raw, country.toUpperCase(Locale.ROOT));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
