package dev.danilocordeiro.ledgermcp.domain;

import java.util.List;

/** What ledger-core's {@code POST /v1/transactions} takes, plus its Idempotency-Key header. */
public record TransactionRequest(String idempotencyKey, String currency, List<Posting> postings, String externalReference) {

    public TransactionRequest {
        postings = List.copyOf(postings);
        Postings.requireBalanced(postings);
    }
}
