package dev.danilocordeiro.ledgermcp.adapter.live;

import dev.danilocordeiro.ledgermcp.domain.AccountBalance;
import dev.danilocordeiro.ledgermcp.domain.Direction;
import dev.danilocordeiro.ledgermcp.domain.LedgerTransaction;
import dev.danilocordeiro.ledgermcp.domain.StatementEntry;
import dev.danilocordeiro.ledgermcp.domain.StatementPage;
import dev.danilocordeiro.ledgermcp.domain.UnsupportedCapabilityException;
import dev.danilocordeiro.ledgermcp.port.LedgerReader;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * Reads from ledger-query's HTTP API: {@code GET /v1/accounts/{id}/balance} and
 * {@code GET /v1/accounts/{id}/statement?from&to&limit&cursor} (bounds inclusive, RFC 3339).
 */
public class LedgerQueryClient implements LedgerReader {

    private final RestClient rest;

    public LedgerQueryClient(RestClient rest) {
        this.rest = rest;
    }

    record BalanceResponse(String accountId, String currency, long balanceMinor, long version, String updatedAt) {
    }

    record StatementEntryResponse(String transactionId, String direction, long amountMinor, long signedMinor,
                                  String currency, String occurredAt, String externalReference) {
    }

    record StatementResponse(String accountId, List<StatementEntryResponse> entries, String nextCursor) {
    }

    @Override
    public Optional<AccountBalance> balance(UUID accountId) {
        return rest.get().uri("/v1/accounts/{id}/balance", accountId).exchange((request, response) -> {
            if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            requireSuccess(response.getStatusCode().value(), "balance", accountId);
            BalanceResponse body = response.bodyTo(BalanceResponse.class);
            return Optional.of(new AccountBalance(UUID.fromString(body.accountId()), null, body.currency(),
                    body.balanceMinor(), null, body.updatedAt() == null ? null : Instant.parse(body.updatedAt())));
        });
    }

    @Override
    public StatementPage statement(UUID accountId, Instant from, Instant to, int limit, String cursor) {
        StatementResponse body = rest.get()
                .uri(b -> {
                    b.path("/v1/accounts/{id}/statement").queryParam("limit", limit);
                    if (from != null) {
                        b.queryParam("from", from.toString());
                    }
                    if (to != null) {
                        b.queryParam("to", to.toString());
                    }
                    if (cursor != null && !cursor.isBlank()) {
                        b.queryParam("cursor", cursor);
                    }
                    return b.build(accountId);
                })
                .exchange((request, response) -> {
                    requireSuccess(response.getStatusCode().value(), "statement", accountId);
                    return response.bodyTo(StatementResponse.class);
                });
        List<StatementEntry> entries = body.entries() == null ? List.of() : body.entries().stream()
                .map(e -> new StatementEntry(UUID.fromString(e.transactionId()), Direction.valueOf(e.direction()),
                        e.amountMinor(), e.signedMinor(), e.currency(), Instant.parse(e.occurredAt()), e.externalReference()))
                .toList();
        return new StatementPage(accountId, entries, body.nextCursor());
    }

    @Override
    public Optional<LedgerTransaction> transaction(UUID transactionId) {
        throw new UnsupportedCapabilityException("Looking up a single transaction is not available in live mode: "
                + "ledger-query exposes balances and per-account statements only. Use list_account_entries on one of "
                + "the accounts involved to see this transaction's leg on that account. Reversals cannot be proposed "
                + "in live mode for the same reason; propose an adjustment instead.");
    }

    private static void requireSuccess(int status, String what, UUID accountId) {
        if (status < 200 || status > 299) {
            throw new IllegalStateException("ledger-query returned HTTP " + status + " for " + what + " of account " + accountId);
        }
    }
}
