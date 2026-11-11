package dev.danilocordeiro.ledgermcp.domain;

import java.time.Instant;

/**
 * One reconciliation disagreement. Amounts are null on the side that has no record,
 * so "absent" stays distinguishable from "zero".
 */
public record Divergence(
        DivergenceKind kind,
        String reference,
        String transactionId,
        Long ledgerAmountMinor,
        Long cnabAmountMinor,
        Instant detectedAt) {
}
