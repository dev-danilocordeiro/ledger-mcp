package dev.danilocordeiro.ledgermcp.domain;

import java.util.Objects;
import java.util.UUID;

/** One leg of a transaction. Amounts are always positive minor units (cents). */
public record Posting(UUID accountId, Direction direction, long amountMinor) {

    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive, got " + amountMinor);
        }
    }

    public Posting mirrored() {
        return new Posting(accountId, direction.opposite(), amountMinor);
    }
}
