package dev.danilocordeiro.ledgermcp.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerTransaction(
        UUID transactionId,
        String currency,
        List<Posting> postings,
        String externalReference,
        Instant postedAt) {

    public LedgerTransaction {
        postings = List.copyOf(postings);
    }
}
