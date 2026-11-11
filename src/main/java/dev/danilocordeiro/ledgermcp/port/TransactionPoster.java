package dev.danilocordeiro.ledgermcp.port;

import dev.danilocordeiro.ledgermcp.domain.PostedTransaction;
import dev.danilocordeiro.ledgermcp.domain.TransactionRequest;

public interface TransactionPoster {

    /**
     * Posts a balanced transaction. Must be idempotent on {@link TransactionRequest#idempotencyKey()}.
     *
     * @throws dev.danilocordeiro.ledgermcp.domain.LedgerRejectedException when the ledger refuses it
     */
    PostedTransaction post(TransactionRequest request);
}
