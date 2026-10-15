package dev.yoonpay.server.link;

import dev.yoonpay.server.auth.AppPrincipal;
import dev.yoonpay.server.auth.Applications;
import dev.yoonpay.server.web.ApiProblem;

import java.io.PrintStream;
import java.util.Arrays;

/**
 * Payment links from the command line (ADR-0030), run against the configured database:
 * <pre>
 *   links create &lt;app&gt; &lt;amount | any&gt; &lt;currency&gt; &lt;country&gt; &lt;description…&gt;
 * </pre>
 * With Docker: {@code docker compose run --rm yoon links create shop 5000 XOF SN Tote bag}.
 */
public final class LinksCommand {

    static final String USAGE = """
            Usage:
              links create <app> <amount | any> <currency> <country> <description…>
                  a payment link for a fixed amount in minor units (5000 = 5 000 XOF), or "any" for the
                  customer to type it; prints the link to share and its QR code URL""";

    private LinksCommand() {
    }

    public static boolean matches(String[] args) {
        return args.length >= 1 && args[0].equals("links");
    }

    public static int run(String[] args, Applications applications, PaymentLinkService links, PrintStream out) {
        // links create <app> <amount|any> <currency> <country> <description…>
        if (args.length < 7 || !args[1].equals("create")) {
            out.println(USAGE);
            return 2;
        }
        try {
            AppPrincipal app = applications.findByName(args[2])
                    .orElseThrow(() -> new IllegalArgumentException("no application named " + args[2]));
            Long amount = null;
            if (!args[3].equals("any")) {
                try {
                    amount = Long.parseLong(args[3]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("the amount is a whole number of minor units, or 'any'");
                }
                if (amount <= 0) {
                    throw new IllegalArgumentException("the amount must be positive");
                }
            }
            String currency = args[4].toUpperCase(java.util.Locale.ROOT);
            String country = args[5].toUpperCase(java.util.Locale.ROOT);
            if (!currency.matches("[A-Z]{3}") || !country.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("currency is 3 letters (XOF), country 2 letters (SN)");
            }
            String description = String.join(" ", Arrays.copyOfRange(args, 6, args.length)).strip();
            if (description.isEmpty() || description.length() > 500) {
                throw new IllegalArgumentException("the description is 1 to 500 characters");
            }
            PaymentLinkResponse l = links.create(app, new CreatePaymentLinkRequest(amount, null, null, currency, country,
                    description, null, null, null, null, null));
            out.println("Payment link " + l.id() + ": " + l.url());
            out.println("QR code: " + l.qrUrl());
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
