package dev.yoonpay.server.demo;

import dev.yoonpay.core.provider.PaymentProvider;
import dev.yoonpay.provider.demo.DemoBank;
import dev.yoonpay.provider.demo.DemoProvider;
import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.provider.ProviderRegistry;
import dev.yoonpay.server.web.ApiProblem;
import dev.yoonpay.server.webhook.InboundWebhookStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The demo provider's checkout page. After the visitor decides, the page hands Yoon a signed
 * callback through the normal inbound pipeline — verify, re-confirm, settle — exactly as a real
 * provider's callback would travel.
 */
@Controller
@ConditionalOnProperty(name = "yoon.demo.enabled", havingValue = "true")
public class DemoController {

    private static final Pattern HOOK_PATH = Pattern.compile("/v1/hooks/demo/([0-9a-f-]{36})$");

    private final DemoBank bank;
    private final Applications applications;
    private final ProviderRegistry providers;
    private final InboundWebhookStore inbound;

    public DemoController(DemoBank bank, Applications applications, ProviderRegistry providers, InboundWebhookStore inbound) {
        this.bank = bank;
        this.applications = applications;
        this.providers = providers;
        this.inbound = inbound;
    }

    @GetMapping("/demo/checkout/{reference}")
    ResponseEntity<String> page(@PathVariable String reference) {
        DemoBank.Checkout c = bank.find(reference).orElseThrow(() -> ApiProblem.notFound("Demo checkout", reference));
        String body = switch (c.state()) {
            case PENDING -> """
                    <p class="amount">%s</p><p>%s</p>
                    <form method="post"><button name="decision" value="pay">Pay</button>
                    <button name="decision" value="decline" class="secondary">Decline</button></form>"""
                    .formatted(escape(c.amount().toString()), escape(c.description() == null ? "" : c.description()));
            case PAID -> "<p class=\"amount\">Paid</p><p>" + escape(c.amount().toString()) + "</p>";
            case DECLINED -> "<p class=\"amount\">Declined</p>";
        };
        return html(HttpStatus.OK, body);
    }

    @PostMapping("/demo/checkout/{reference}")
    ResponseEntity<String> decide(@PathVariable String reference, @RequestParam String decision) {
        DemoBank.Checkout c = bank.decide(reference, decision.equals("pay"))
                .orElseThrow(() -> ApiProblem.notFound("Demo checkout", reference));
        callback(c);
        if (c.returnUrl() != null) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(c.returnUrl()).build();
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/demo/checkout/" + reference)).build();
    }

    /** Signed with the application's demo provider, stored like any provider callback. */
    private void callback(DemoBank.Checkout c) {
        Optional<AppPrincipal> app = Optional.ofNullable(c.callbackUrl())
                .map(u -> HOOK_PATH.matcher(u.getPath()))
                .filter(Matcher::find)
                .flatMap(m -> applications.find(UUID.fromString(m.group(1))));
        if (app.isEmpty()) {
            return; // no callback URL (YOON_PUBLIC_URL unset): reconciliation settles it instead
        }
        PaymentProvider p = providers.find(app.get(), "demo").orElse(null);
        if (p instanceof DemoProvider demo) {
            byte[] body = demo.callbackBody(c);
            inbound.store(app.get(), "demo", Map.of(DemoProvider.SIGNATURE_HEADER.toLowerCase(), List.of(demo.sign(body))), body);
        }
    }

    private static ResponseEntity<String> html(HttpStatus status, String content) {
        String page = """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1"><title>Yoon demo checkout</title>
                <link rel="icon" href="/favicon.svg" type="image/svg+xml">
                <style>
                body{font-family:system-ui,sans-serif;background:#f4f2ee;color:#1d1b18;display:grid;place-items:center;min-height:100vh;margin:0}
                main{background:#fff;padding:2rem 2.5rem;border-radius:12px;box-shadow:0 2px 12px #0001;max-width:22rem;width:calc(100%% - 32px)}
                .tag{font-size:.75rem;letter-spacing:.08em;text-transform:uppercase;color:#a0522d}
                .amount{font-size:2rem;font-weight:700;margin:.5rem 0}
                button{font:inherit;padding:.6rem 1.2rem;border:0;border-radius:8px;background:#1d1b18;color:#fff;cursor:pointer;margin-right:.5rem}
                button.secondary{background:#e8e4dc;color:#1d1b18}
                </style></head><body><main><div class="tag">Yoon demo provider · no real money</div>%s</main></body></html>"""
                .formatted(content);
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.TEXT_HTML).body(page);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
