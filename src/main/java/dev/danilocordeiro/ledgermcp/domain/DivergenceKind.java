package dev.danilocordeiro.ledgermcp.domain;

import java.util.Arrays;

/** Same vocabulary as ledger-recon's {@code DivergenceKind}. */
public enum DivergenceKind {
    /** The ledger posted it; the bank return file has no matching record. */
    MISSING_IN_CNAB("missing_in_cnab"),
    /** The bank reports it; the ledger never posted it. */
    MISSING_IN_LEDGER("missing_in_ledger"),
    /** Both sides have it, with different amounts. */
    AMOUNT_MISMATCH("amount_mismatch");

    private final String wireName;

    DivergenceKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static DivergenceKind fromWireName(String value) {
        return Arrays.stream(values())
                .filter(k -> k.wireName.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown divergence kind: " + value));
    }
}
