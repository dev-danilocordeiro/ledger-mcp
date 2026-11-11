package dev.danilocordeiro.ledgermcp.domain;

import java.time.Instant;
import java.util.UUID;

public record PostedTransaction(UUID transactionId, Instant postedAt, boolean replayed) {
}
