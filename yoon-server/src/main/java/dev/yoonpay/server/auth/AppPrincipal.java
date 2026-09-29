package dev.yoonpay.server.auth;

import java.util.UUID;

/** The application a request authenticated as. Every query is scoped to {@code id}. */
public record AppPrincipal(UUID id, String name) {
}
