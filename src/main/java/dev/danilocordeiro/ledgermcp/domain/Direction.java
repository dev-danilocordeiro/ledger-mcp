package dev.danilocordeiro.ledgermcp.domain;

/** Side of a double-entry posting. Mirrors ledger-core's {@code DEBIT|CREDIT}. */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
