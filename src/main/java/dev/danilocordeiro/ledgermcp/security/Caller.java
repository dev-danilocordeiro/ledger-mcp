package dev.danilocordeiro.ledgermcp.security;

import java.util.Set;

/** Who is calling a tool: the audited principal, the OAuth client it used, and its scopes. */
public record Caller(String principal, String clientId, String transport, Set<String> scopes) {

    public static final Caller ANONYMOUS = new Caller("anonymous", null, "unknown", Set.of());

    public Caller {
        scopes = Set.copyOf(scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
