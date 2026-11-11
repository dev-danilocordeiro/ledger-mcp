package dev.danilocordeiro.ledgermcp.audit;

public enum AuditOutcome {
    OK,
    /** The tool ran and failed: bad input, business rule, backend error. */
    ERROR,
    /** The caller lacked the scope the tool requires; the tool body never ran. */
    DENIED
}
