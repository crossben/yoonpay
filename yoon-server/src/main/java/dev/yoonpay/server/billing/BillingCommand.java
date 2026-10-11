package dev.yoonpay.server.billing;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.web.ApiProblem;

import java.io.PrintStream;
import java.util.Arrays;

/**
 * Operator billing command line, run against the configured database without the web server:
 * <pre>
 *   billing comp &lt;app&gt; &lt;sub_… | cus_…&gt; &lt;price_…&gt; &lt;periods | open&gt; &lt;note…&gt;
 * </pre>
 * Grants periods without payment (ADR-0026, "Operator"). With Docker:
 * {@code docker compose run --rm yoon billing comp shop cus_… price_… 3 "partner, approved by Awa"}.
 */
public final class BillingCommand {

    static final String USAGE = """
            Usage:
              billing comp <app> <sub_… | cus_…> <price_…> <periods | open> <note…>
                  grant periods (or an open-ended period) without payment; the note says who asked and why""";

    private BillingCommand() {
    }

    public static boolean matches(String[] args) {
        return args.length >= 1 && args[0].equals("billing");
    }

    public static int run(String[] args, Applications applications, CompService comp, PrintStream out) {
        if (args.length < 6 || !args[1].equals("comp")) {
            out.println(USAGE);
            return 2;
        }
        try {
            AppPrincipal app = applications.findByName(args[2])
                    .orElseThrow(() -> new IllegalArgumentException("no application named " + args[2]));
            String target = args[3];
            if (!target.startsWith("sub_") && !target.startsWith("cus_")) {
                throw new IllegalArgumentException("the target is a subscription (sub_…) or a customer (cus_…)");
            }
            Integer periods;
            if (args[5].equals("open")) {
                periods = null;
            } else {
                try {
                    periods = Integer.parseInt(args[5]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("periods is a number or 'open'");
                }
            }
            String note = args.length > 6 ? String.join(" ", Arrays.copyOfRange(args, 6, args.length)) : "";
            SubscriptionRecord s = comp.comp(app, target, args[4], periods, note);
            out.println("Comped " + s.id() + ": active until "
                    + (s.currentPeriodEnd() == null ? "an operator ends it (open)" : s.currentPeriodEnd()));
            return 0;
        } catch (IllegalArgumentException e) {
            out.println("Error: " + e.getMessage());
            return 2;
        } catch (ApiProblem e) {
            out.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
