package dev.danilocordeiro.ledgermcp.proposal;

public enum ProposalKind {
    /** Mirror every posting of an existing transaction. */
    REVERSAL,
    /** Move an amount between an account and an offset account. */
    ADJUSTMENT
}
