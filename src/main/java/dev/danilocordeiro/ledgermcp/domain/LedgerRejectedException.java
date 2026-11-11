package dev.danilocordeiro.ledgermcp.domain;

/** The ledger refused a posting (unknown account, insufficient funds, currency mismatch...). */
public class LedgerRejectedException extends RuntimeException {

    public LedgerRejectedException(String message) {
        super(message);
    }
}
