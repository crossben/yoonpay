package dev.yoonpay.server.auth;

import java.io.PrintStream;

/**
 * Operator command line, run against the configured database without the web server:
 * <pre>
 *   apps create &lt;name&gt;          new application + first API key (shown once)
 *   apps list                   applications and their keys (prefixes only)
 *   apps add-key &lt;name&gt;         another key, for rotation (shown once)
 *   apps revoke-key &lt;prefix&gt;    revoke a key by the prefix shown in 'apps list'
 * </pre>
 * With Docker: {@code docker compose run --rm yoon apps list}.
 */
public final class AppsCommand {

    static final String USAGE = """
            Usage:
              apps create <name>         create an application and its first API key
              apps list                  list applications and key prefixes
              apps add-key <name>        issue another key (rotation)
              apps revoke-key <prefix>   revoke a key""";

    private AppsCommand() {
    }

    public static boolean matches(String[] args) {
        return args.length >= 1 && args[0].equals("apps");
    }

    public static int run(String[] args, ApiKeyService keys, PrintStream out) {
        try {
            String cmd = args.length >= 2 ? args[1] : "";
            switch (cmd) {
                case "create" -> {
                    requireArgs(args, 3);
                    ApiKeyService.Created created = keys.createApplication(args[2]);
                    out.println("Application: " + created.application().name() + " (" + created.application().id() + ")");
                    out.println("API key (shown once, store it now): " + created.apiKey());
                    out.println("Provider callbacks for this application: <YOON_PUBLIC_URL>/v1/hooks/<provider>/" + created.application().id());
                }
                case "list" -> {
                    for (ApiKeyService.AppInfo app : keys.list()) {
                        out.println(app.name() + "  " + app.id() + "  created " + app.createdAt());
                        for (ApiKeyService.KeyInfo k : app.keys()) {
                            out.println("    " + k.prefix() + "…  created " + k.createdAt()
                                    + (k.revokedAt() == null ? "  active" : "  revoked " + k.revokedAt()));
                        }
                    }
                }
                case "add-key" -> {
                    requireArgs(args, 3);
                    out.println("New API key for " + args[2] + " (shown once, store it now): " + keys.addKey(args[2]));
                    out.println("The previous key still works: revoke it with 'apps revoke-key <prefix>' once clients use the new one.");
                }
                case "revoke-key" -> {
                    requireArgs(args, 3);
                    int n = keys.revokeKey(args[2]);
                    out.println(n == 0 ? "No active key with that prefix." : "Revoked " + n + " key(s).");
                    return n == 0 ? 1 : 0;
                }
                default -> {
                    out.println(USAGE);
                    return 2;
                }
            }
            return 0;
        } catch (IllegalArgumentException e) {
            out.println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static void requireArgs(String[] args, int n) {
        if (args.length != n) {
            throw new IllegalArgumentException("wrong number of arguments\n" + USAGE);
        }
    }
}
