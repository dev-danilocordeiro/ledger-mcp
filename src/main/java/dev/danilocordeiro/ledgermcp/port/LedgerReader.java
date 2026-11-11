package dev.danilocordeiro.ledgermcp.port;

import dev.danilocordeiro.ledgermcp.domain.AccountBalance;
import dev.danilocordeiro.ledgermcp.domain.LedgerTransaction;
import dev.danilocordeiro.ledgermcp.domain.StatementPage;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LedgerReader {

    Optional<AccountBalance> balance(UUID accountId);

    /** Entries with {@code from <= occurredAt <= to}, newest first. Either bound may be null. */
    StatementPage statement(UUID accountId, Instant from, Instant to, int limit, String cursor);

    /**
     * @throws dev.danilocordeiro.ledgermcp.domain.UnsupportedCapabilityException when the
     *         backend has no transaction lookup
     */
    Optional<LedgerTransaction> transaction(UUID transactionId);
}
