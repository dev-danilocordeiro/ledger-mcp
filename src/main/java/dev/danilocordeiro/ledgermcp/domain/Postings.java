package dev.danilocordeiro.ledgermcp.domain;

import java.util.List;

public final class Postings {

    private Postings() {
    }

    /** Throws unless debits equal credits, the zero-sum invariant ledger-core enforces too. */
    public static void requireBalanced(List<Posting> postings) {
        if (postings.size() < 2) {
            throw new IllegalArgumentException("a transaction needs at least two postings");
        }
        long debits = postings.stream().filter(p -> p.direction() == Direction.DEBIT).mapToLong(Posting::amountMinor).sum();
        long credits = postings.stream().filter(p -> p.direction() == Direction.CREDIT).mapToLong(Posting::amountMinor).sum();
        if (debits != credits) {
            throw new IllegalArgumentException("postings do not balance: debits=" + debits + " credits=" + credits);
        }
    }
}
