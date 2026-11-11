package dev.danilocordeiro.ledgermcp.domain;

import java.time.Instant;
import java.util.UUID;

public record StatementEntry(
        UUID transactionId,
        Direction direction,
        long amountMinor,
        long signedMinor,
        String currency,
        Instant occurredAt,
        String externalReference) {
}
