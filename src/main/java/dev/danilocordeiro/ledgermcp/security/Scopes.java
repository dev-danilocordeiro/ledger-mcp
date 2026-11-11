package dev.danilocordeiro.ledgermcp.security;

/** OAuth scopes, one per capability. A tool declares exactly one with {@link RequiredScope}. */
public final class Scopes {

    public static final String LEDGER_READ = "ledger:read";
    public static final String RECON_READ = "recon:read";
    public static final String LEDGER_PROPOSE = "ledger:propose";
    public static final String LEDGER_APPROVE = "ledger:approve";

    private Scopes() {
    }
}
