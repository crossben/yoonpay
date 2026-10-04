package dev.yoonpay.server.dashboard;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** {@code /dashboard} → the static operator dashboard. {@link DashboardFilter} decides whether it exists. */
@Controller
public class DashboardController {

    @GetMapping({"/dashboard", "/dashboard/"})
    String dashboard() {
        return "forward:/dashboard/index.html";
    }
}
