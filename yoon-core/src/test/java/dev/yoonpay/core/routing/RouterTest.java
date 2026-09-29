package dev.yoonpay.core.routing;

import dev.yoonpay.core.provider.Capabilities;
import dev.yoonpay.core.provider.Capability;
import dev.yoonpay.core.provider.Operation;
import dev.yoonpay.core.provider.ProviderId;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RouterTest {

    private static final Currency XOF = Currency.getInstance("XOF");
    private final Router router = new Router();

    private static Candidate candidate(String id, int priority, boolean available, String... methods) {
        Set<Capability> caps = new java.util.HashSet<>();
        for (String m : methods) {
            caps.add(new Capability(Operation.COLLECT, "SN", m, XOF));
        }
        return new Candidate(new ProviderId(id), priority, new Capabilities(caps), available);
    }

    private static RouteRequest collect(String method) {
        return new RouteRequest(Operation.COLLECT, "SN", method, XOF, null);
    }

    @Test
    void orders_capable_available_providers_by_priority_then_id() {
        var decision = router.route(collect("wave"), List.of(
                candidate("naboopay", 2, true, "wave"),
                candidate("paydunya", 1, true, "wave"),
                candidate("dexpay", 2, true, "wave")));

        assertThat(decision).isInstanceOfSatisfying(Router.Decision.Route.class, r ->
                assertThat(r.providers()).extracting(ProviderId::value).containsExactly("paydunya", "dexpay", "naboopay"));
    }

    @Test
    void skips_providers_that_cannot_do_the_method() {
        var decision = router.route(collect("orange_money"), List.of(
                candidate("paydunya", 1, true, "wave"),
                candidate("dexpay", 2, true, "orange_money")));

        assertThat(decision).isInstanceOfSatisfying(Router.Decision.Route.class, r ->
                assertThat(r.providers()).extracting(ProviderId::value).containsExactly("dexpay"));
    }

    @Test
    void no_capable_provider_is_a_machine_readable_no_route() {
        var decision = router.route(collect("card"), List.of(candidate("paydunya", 1, true, "wave")));

        assertThat(decision).isInstanceOfSatisfying(Router.Decision.NoRoute.class, n ->
                assertThat(n.code()).isEqualTo(Router.NO_PROVIDER_FOR_METHOD));
    }

    @Test
    void capable_but_circuit_open_everywhere_is_unavailable() {
        var decision = router.route(collect("wave"), List.of(
                candidate("paydunya", 1, false, "wave"),
                candidate("dexpay", 2, false, "wave")));

        assertThat(decision).isInstanceOfSatisfying(Router.Decision.NoRoute.class, n ->
                assertThat(n.code()).isEqualTo(Router.ALL_PROVIDERS_UNAVAILABLE));
    }

    @Test
    void open_circuit_providers_are_skipped() {
        var decision = router.route(collect("wave"), List.of(
                candidate("paydunya", 1, false, "wave"),
                candidate("dexpay", 2, true, "wave")));

        assertThat(decision).isInstanceOfSatisfying(Router.Decision.Route.class, r ->
                assertThat(r.providers()).extracting(ProviderId::value).containsExactly("dexpay"));
    }

    @Test
    void a_pin_restricts_routing_to_that_provider() {
        var pinned = new RouteRequest(Operation.COLLECT, "SN", "wave", XOF, new ProviderId("dexpay"));

        var decision = router.route(pinned, List.of(
                candidate("paydunya", 1, true, "wave"),
                candidate("dexpay", 2, true, "wave")));

        assertThat(decision).isInstanceOfSatisfying(Router.Decision.Route.class, r ->
                assertThat(r.providers()).extracting(ProviderId::value).containsExactly("dexpay"));
    }

    @Test
    void pinning_an_unconfigured_provider_is_refused() {
        var pinned = new RouteRequest(Operation.COLLECT, "SN", "wave", XOF, new ProviderId("wave-direct"));

        var decision = router.route(pinned, List.of(candidate("paydunya", 1, true, "wave")));

        assertThat(decision).isInstanceOfSatisfying(Router.Decision.NoRoute.class, n ->
                assertThat(n.code()).isEqualTo(Router.PROVIDER_NOT_CONFIGURED));
    }
}
