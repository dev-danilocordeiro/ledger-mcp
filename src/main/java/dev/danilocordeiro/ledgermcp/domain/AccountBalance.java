package dev.danilocordeiro.ledgermcp.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Current balance of one account. {@code balanceMinor} is signed by the account's normal
 * side, as ledger-query reports it. {@code displayName} and {@code normalSide} are only
 * known in synthetic mode; ledger-query does not expose them, so they are null in live mode.
 */
public record AccountBalance(
        UUID accountId,
        String displayName,
        String currency,
        long balanceMinor,
        Direction normalSide,
        Instant updatedAt) {

    /** The balance after applying {@code posting}, or null when the normal side is unknown. */
    public Long projectedAfter(Posting posting) {
        if (normalSide == null) {
            return null;
        }
        long delta = posting.direction() == normalSide ? posting.amountMinor() : -posting.amountMinor();
        return balanceMinor + delta;
    }
}
