package dev.yoonpay.server.refund;

import dev.yoonpay.core.lifecycle.RefundStatus;
import dev.yoonpay.server.web.ApiProblem;

import java.io.PrintStream;
import java.util.Arrays;

/**
 * Operator actions on refunds from the command line (money-safety plan 2026-11), run against the
 * configured database:
 * <pre>
 *   refunds resolve &lt;refund id&gt; refunded|failed &lt;note…&gt;
 *   refunds release &lt;refund id&gt; &lt;note…&gt;
 * </pre>
 * With Docker: {@code docker compose run --rm yoon refunds release re_… "Stripe confirms it failed"}.
 */
public final class RefundsCommand {

    static final String USAGE = """
            Usage:
              refunds resolve <refund id> refunded|failed <note…>
                  settle a refund flagged needs_review, after checking with the provider
              refunds release <refund id> <note…>
                  free the amount a failed refund holds, once the provider confirms no money left""";

    private RefundsCommand() {
    }

    public static boolean matches(String[] args) {
        return args.length >= 1 && args[0].equals("refunds");
    }

    public static int run(String[] args, RefundOperations operations, PrintStream out) {
        try {
            RefundRecord r;
            if (args.length >= 5 && args[1].equals("resolve")
                    && (args[3].equals("refunded") || args[3].equals("failed"))) {
                RefundStatus target = args[3].equals("refunded") ? RefundStatus.REFUNDED : RefundStatus.FAILED;
                r = operations.resolve(args[2], target, note(args, 4));
            } else if (args.length >= 4 && args[1].equals("release")) {
                r = operations.release(args[2], note(args, 3));
            } else {
                out.println(USAGE);
                return 2;
            }
            out.println(r.id() + " " + r.status().toLowerCase() + " " + r.amount() + " " + r.currency());
            return 0;
        } catch (ApiProblem e) {
            out.println("error: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException e) {
            out.println("error: " + e.getMessage());
            return 2;
        }
    }

    private static String note(String[] args, int from) {
        String note = String.join(" ", Arrays.copyOfRange(args, from, args.length)).strip();
        if (note.isEmpty() || note.length() > 500) {
            throw new IllegalArgumentException("the note is 1 to 500 characters");
        }
        return note;
    }
}
