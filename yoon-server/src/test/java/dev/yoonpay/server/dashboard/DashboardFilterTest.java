package dev.yoonpay.server.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** YOON_DASHBOARD_ENABLED=false, or no admin token, means no dashboard at all. */
class DashboardFilterTest {

    private static final String TOKEN = "x".repeat(32);

    private static MockHttpServletResponse call(DashboardFilter filter, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (chain.getRequest() != null) {
            response.setStatus(200); // reached the static resource handler
        }
        return response;
    }

    @Test
    void disabled_dashboard_is_404_on_every_path() throws Exception {
        DashboardFilter off = new DashboardFilter(false, TOKEN);
        for (String path : new String[]{"/dashboard", "/dashboard/", "/dashboard/index.html", "/dashboard/dashboard.js"}) {
            MockHttpServletResponse r = call(off, path);
            assertThat(r.getStatus()).as(path).isEqualTo(404);
            assertThat(r.getContentAsString()).contains("\"code\":\"resource_not_found\"");
        }
    }

    @Test
    void without_an_admin_token_the_dashboard_does_not_exist() throws Exception {
        assertThat(call(new DashboardFilter(true, ""), "/dashboard").getStatus()).isEqualTo(404);
        assertThat(call(new DashboardFilter(true, "short"), "/dashboard/index.html").getStatus()).isEqualTo(404);
    }

    @Test
    void enabled_dashboard_passes_through_with_csp_and_leaves_other_paths_alone() throws Exception {
        DashboardFilter on = new DashboardFilter(true, TOKEN);
        MockHttpServletResponse page = call(on, "/dashboard/index.html");
        assertThat(page.getStatus()).isEqualTo(200);
        assertThat(page.getHeader("Content-Security-Policy")).isEqualTo(DashboardFilter.CSP);

        MockHttpServletResponse other = call(new DashboardFilter(false, TOKEN), "/dashboards-not-me");
        assertThat(other.getStatus()).isEqualTo(200);
        assertThat(other.getHeader("Content-Security-Policy")).isNull();
    }
}
