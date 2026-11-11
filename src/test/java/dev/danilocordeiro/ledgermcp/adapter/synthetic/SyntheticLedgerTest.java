package dev.danilocordeiro.ledgermcp.adapter.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.danilocordeiro.ledgermcp.domain.Direction;
import dev.danilocordeiro.ledgermcp.domain.LedgerTransaction;
import dev.danilocordeiro.ledgermcp.domain.Posting;
import dev.danilocordeiro.ledgermcp.domain.StatementEntry;
import dev.danilocordeiro.ledgermcp.domain.StatementPage;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyntheticLedgerTest {

    SyntheticDataset dataset = SyntheticDataset.generate();
    SyntheticLedger ledger = new SyntheticLedger(Clock.systemUTC());

    @Test
    void isDeterministic() {
        SyntheticDataset again = SyntheticDataset.generate();
        assertThat(again.transactions).isEqualTo(dataset.transactions);
        assertThat(again.divergences).isEqualTo(dataset.divergences);
    }

    @Test
    void everyTransactionBalancesAndNoRestrictedAccountGoesNegative() {
        for (LedgerTransaction t : dataset.transactions) {
            long debits = t.postings().stream().filter(p -> p.direction() == Direction.DEBIT).mapToLong(Posting::amountMinor).sum();
            long credits = t.postings().stream().filter(p -> p.direction() == Direction.CREDIT).mapToLong(Posting::amountMinor).sum();
            assertThat(debits).as(t.transactionId().toString()).isEqualTo(credits);
        }
        for (SyntheticAccount account : dataset.accounts) {
            if (!account.allowsNegative()) {
                assertThat(ledger.balance(account.id()).orElseThrow().balanceMinor()).as(account.name()).isNotNegative();
            }
        }
    }

    @Test
    void statementBoundsAreInclusiveAndPagesCoverEverythingOnce() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-31T23:59:59.999999999Z");
        List<StatementEntry> all = new ArrayList<>();
        String cursor = null;
        do {
            StatementPage page = ledger.statement(SyntheticDataset.OPERATING_CASH, from, to, 7, cursor);
            all.addAll(page.entries());
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(all).isNotEmpty();
        assertThat(all).allSatisfy(e -> assertThat(e.occurredAt()).isBetween(from, to));
        assertThat(all).extracting(StatementEntry::occurredAt).isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(all).extracting(StatementEntry::transactionId).doesNotHaveDuplicates();
        long expected = dataset.transactions.stream()
                .filter(t -> !t.postedAt().isBefore(from) && !t.postedAt().isAfter(to))
                .flatMap(t -> t.postings().stream())
                .filter(p -> p.accountId().equals(SyntheticDataset.OPERATING_CASH))
                .count();
        assertThat(all).hasSize((int) expected);
    }

    @Test
    void hasEveryDivergenceKind() {
        assertThat(dataset.divergences).extracting(d -> d.kind()).containsOnly(
                dev.danilocordeiro.ledgermcp.domain.DivergenceKind.values());
    }
}
