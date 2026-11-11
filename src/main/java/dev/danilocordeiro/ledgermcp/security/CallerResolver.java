package dev.danilocordeiro.ledgermcp.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class CallerResolver {

    private CallerResolver() {
    }

    public static Caller current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            String principal = firstNonBlank(jwt.getClaimAsString("preferred_username"), jwt.getSubject());
            String client = firstNonBlank(jwt.getClaimAsString("azp"), jwt.getClaimAsString("client_id"));
            return new Caller(principal, client, "http", scopesOf(auth));
        }
        if (auth instanceof LocalOperatorAuthentication local) {
            return new Caller(local.getName(), "stdio", "stdio", scopesOf(local));
        }
        return Caller.ANONYMOUS;
    }

    static Set<String> scopesOf(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("SCOPE_"))
                .map(a -> a.substring("SCOPE_".length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
