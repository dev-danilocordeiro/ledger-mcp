package dev.danilocordeiro.ledgermcp.proposal;

/**
 * PENDING -> EXECUTING -> EXECUTED | FAILED, or PENDING -> REJECTED | EXPIRED.
 * EXECUTING with no outcome means the post call's result is unknown; approving again
 * retries it under the same idempotency key.
 */
public enum ProposalStatus {
    PENDING,
    EXECUTING,
    EXECUTED,
    FAILED,
    REJECTED,
    EXPIRED
}
