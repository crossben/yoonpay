package dev.yoonpay.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** {@code /docs} → Swagger UI rendering the API contract. */
@Controller
public class DocsController {

    @GetMapping({"/docs", "/docs/"})
    String docs() {
        return "forward:/docs/index.html";
    }
}
