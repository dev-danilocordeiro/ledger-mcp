package dev.danilocordeiro.ledgermcp.proposal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProposalRepository {

    void insert(Proposal proposal);

    Optional<Proposal> find(UUID id);

    /** A PENDING, EXECUTING or EXECUTED reversal of this transaction, if any. */
    Optional<Proposal> findActiveReversalOf(UUID transactionId);

    /**
     * Compare-and-set on status. Returns false when the row was not in {@code expected},
     * which is how two concurrent approvals are kept from both executing.
     */
    boolean transition(UUID id, ProposalStatus expected, ProposalStatus next, String decidedBy, Instant decidedAt, String note);

    void markExecuted(UUID id, UUID transactionId);
}
