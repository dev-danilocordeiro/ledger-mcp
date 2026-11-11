package dev.danilocordeiro.ledgermcp.proposal;

import dev.danilocordeiro.ledgermcp.domain.Posting;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Proposal(
        UUID id,
        ProposalKind kind,
        ProposalStatus status,
        String currency,
        List<Posting> postings,
        String externalReference,
        UUID targetTransactionId,
        String divergenceReference,
        String reason,
        String proposedBy,
        Instant createdAt,
        Instant expiresAt,
        String confirmationCode,
        String decidedBy,
        Instant decidedAt,
        String decisionNote,
        UUID executedTransactionId) {

    public Proposal {
        postings = List.copyOf(postings);
    }

    public boolean isExpiredAt(Instant now) {
        return status == ProposalStatus.PENDING && !now.isBefore(expiresAt);
    }
}
