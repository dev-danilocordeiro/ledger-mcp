package dev.danilocordeiro.ledgermcp.security;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Identity for the stdio transport. There is no token on stdio: the person who launched
 * the process is the caller, named by LEDGER_MCP_OPERATOR, with the scopes configured
 * in {@code ledger.stdio.scopes}.
 */
public final class LocalOperatorAuthentication extends AbstractAuthenticationToken {

    private final String operator;

    public LocalOperatorAuthentication(String operator, Collection<String> scopes) {
        super(scopes.stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList());
        this.operator = operator;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return operator;
    }

}
