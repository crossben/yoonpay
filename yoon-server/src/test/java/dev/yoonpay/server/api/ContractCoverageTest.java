package dev.yoonpay.server.api;

import dev.yoonpay.server.PostgresTest;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every /v1 route the server exposes must be documented in api/openapi.yaml, and every
 * documented route must exist. Adding an endpoint without documenting it fails the build.
 */
class ContractCoverageTest extends PostgresTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void every_route_is_documented_and_every_documented_route_exists() {
        Set<String> implemented = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> {
            for (String path : info.getPatternValues()) {
                if (path.startsWith("/v1/")) {
                    info.getMethodsCondition().getMethods().forEach(m -> implemented.add(m.name() + " " + path));
                }
            }
        });

        OpenAPI spec = new OpenAPIV3Parser().read(Path.of("..", "api", "openapi.yaml").toAbsolutePath().toString());
        Set<String> documented = new TreeSet<>();
        spec.getPaths().forEach((path, item) ->
                item.readOperationsMap().keySet().forEach(m -> documented.add(m.name() + " " + path)));

        assertThat(implemented).as("routes in code").isNotEmpty();
        assertThat(documented).as("api/openapi.yaml vs controllers").isEqualTo(implemented);
    }
}
