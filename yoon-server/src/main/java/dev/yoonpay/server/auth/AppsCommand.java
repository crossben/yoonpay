package dev.yoonpay.server.auth;

import java.io.PrintStream;

/**
 * Command line: {@code yoon apps create <name>} creates an application and prints its API key
 * once. Runs without the web server, against the configured database.
 */
public final class AppsCommand {

    private AppsCommand() {
    }

    public static boolean matches(String[] args) {
        return args.length >= 1 && args[0].equals("apps");
    }

    public static int run(String[] args, ApiKeyService keys, PrintStream out) {
        if (args.length == 3 && args[1].equals("create")) {
            ApiKeyService.Created created = keys.createApplication(args[2]);
            out.println("Application: " + created.application().name() + " (" + created.application().id() + ")");
            out.println("API key (shown once, store it now): " + created.apiKey());
            return 0;
        }
        out.println("Usage: apps create <name>");
        return 2;
    }
}
