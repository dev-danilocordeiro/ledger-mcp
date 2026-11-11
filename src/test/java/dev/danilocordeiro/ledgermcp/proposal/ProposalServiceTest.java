package dev.danilocordeiro.ledgermcp.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.danilocordeiro.ledgermcp.adapter.synthetic.SyntheticDataset;
import dev.danilocordeiro.ledgermcp.adapter.synthetic.SyntheticLedger;
import dev.danilocordeiro.ledgermcp.domain.Direction;
import dev.danilocordeiro.ledgermcp.domain.Posting;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProposalServiceTest {

    static final UUID CASH = SyntheticDataset.OPERATING_CASH;
    static final UUID SUSPENSE = SyntheticDataset.SUSPENSE;
    static final UUID USD_CASH = SyntheticDataset.USD_OPERATING_CASH;

    MutableClock clock;
    SyntheticLedger ledger;
    InMemoryProposals repository;
    ProposalService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-10T12:00:00Z"));
        ledger = new SyntheticLedger(clock);
        repository = new InMemoryProposals();
        service = new ProposalService(ledger, ledger, repository, clock, Duration.ofMinutes(15), true);
    }

    @Test
    void proposingPostsNothing() {
        long before = ledger.balance(SUSPENSE).orElseThrow().balanceMinor();

        Proposal p = service.proposeAdjustment(SUSPENSE, CASH, Direction.DEBIT, 1_000, "brl", "test adjustment reason", null, "ana");

        assertThat(p.status()).isEqualTo(ProposalStatus.PENDING);
        assertThat(p.currency()).isEqualTo("BRL");
        assertThat(p.postings()).containsExactly(
                new Posting(SUSPENSE, Direction.DEBIT, 1_000), new Posting(CASH, Direction.CREDIT, 1_000));
        assertThat(ledger.balance(SUSPENSE).orElseThrow().balanceMinor()).isEqualTo(before);
    }

    @Test
    void approvalByAnotherPersonPostsExactlyOnce() {
        long before = ledger.balance(SUSPENSE).orElseThrow().balanceMinor();
        Proposal p = service.proposeAdjustment(SUSPENSE, CASH, Direction.DEBIT, 1_000, "BRL", "test adjustment reason", null, "ana");

        Proposal executed = service.approve(p.id(), p.confirmationCode().toLowerCase(), "bruno");
        Proposal again = service.approve(p.id(), p.confirmationCode(), "bruno");

        assertThat(executed.status()).isEqualTo(ProposalStatus.EXECUTED);
        assertThat(again.executedTransactionId()).isEqualTo(executed.executedTransactionId());
        assertThat(ledger.balance(SUSPENSE).orElseThrow().balanceMinor()).isEqualTo(before + 1_000);
    }

    @Test
    void proposerCannotApprove() {
        Proposal p = service.proposeAdjustment(SUSPENSE, CASH, Direction.DEBIT, 1_000, "BRL", "test adjustment reason", null, "ana");

        assertThatThrownBy(() -> service.approve(p.id(), p.confirmationCode(), "ana"))
                .isInstanceOf(ProposalRuleException.class).hasMessageContaining("Four-eyes");
        assertThat(service.get(p.id()).status()).isEqualTo(ProposalStatus.PENDING);
    }

    @Test
    void wrongCodeIsRefused() {
        Proposal p = service.proposeAdjustment(SUSPENSE, CASH, Direction.DEBIT, 1_000, "BRL", "test adjustment reason", null, "ana");

        assertThatThrownBy(() -> service.approve(p.id(), "AAAA-BBBB", "bruno"))
                .isInstanceOf(ProposalRuleException.class).hasMessageContaining("Confirmation code");
    }

    @Test
    void tamperedProposalIsRefusedEvenWithTheOriginalCode() {
        Proposal p = service.proposeAdjustment(SUSPENSE, CASH, Direction.DEBIT, 1_000, "BRL", "test adjustment reason", null, "ana");
        repository.replace(new Proposal(p.id(), p.kind(), p.status(), p.currency(),
                java.util.List.of(new Posting(SUSPENSE, Direction.DEBIT, 900_000), new Posting(CASH, Direction.CREDIT, 900_000)),
                p.externalReference(), p.targetTransactionId(), p.divergenceReference(), p.reason(), p.proposedBy(),
                p.createdAt(), p.expiresAt(), p.confirmationCode(), null, null, null, null));

        assertThatThrownBy(() -> service.approve(p.id(), p.confirmationCode(), "bruno"))
                .hasMessageContaining("does not match its confirmation code");
    }

    @Test
    void expiredProposalCannotBeApproved() {
        Proposal p = service.proposeAdjustment(SUSPENSE, CASH, Direction.DEBIT, 1_000, "BRL", "test adjustment reason", null, "ana");
        clock.advance(Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.approve(p.id(), p.confirmationCode(), "bruno"))
                .hasMessageContaining("EXPIRED");
        assertThat(service.get(p.id()).status()).isEqualTo(ProposalStatus.EXPIRED);
    }

    @Test
    void disabledApprovalsRefuseBeforeAnythingElse() {
        ProposalService localOnly = new ProposalService(ledger, ledger, repository, clock, Duration.ofMinutes(15), false);
        Proposal p = localOnly.proposeAdjustment(SUSPENSE, CASH, Direction.DEBIT, 1_000, "BRL", "test adjustment reason", null, "ana");

        assertThatThrownBy(() -> localOnly.approve(p.id(), p.confirmationCode(), "bruno"))
                .hasMessageContaining("Approvals are disabled");
    }

    @Test
    void reversalMirrorsEveryPostingAndCannotBeDuplicated() {
        UUID deposit = SyntheticDataset.transaction(3);
        Proposal reversal = service.proposeReversal(deposit, "duplicated deposit", "ana");

        assertThat(reversal.postings()).isEqualTo(
                ledger.transaction(deposit).orElseThrow().postings().stream().map(Posting::mirrored).toList());
        assertThat(reversal.externalReference()).isEqualTo("reversal-of:" + deposit);
        assertThatThrownBy(() -> service.proposeReversal(deposit, "asking a second time", "ana"))
                .hasMessageContaining("already has reversal proposal");
    }

    @Test
    void aReversalCannotItselfBeReversed() {
        UUID existingReversal = ledger.statement(SUSPENSE, null, null, 200, null).entries().stream()
                .filter(e -> e.externalReference() != null && e.externalReference().startsWith("reversal-of:"))
                .findFirst().orElseThrow().transactionId();

        assertThatThrownBy(() -> service.proposeReversal(existingReversal, "undo the undo please", "ana"))
                .hasMessageContaining("is itself a reversal");
    }

    @Test
    void adjustmentAcrossCurrenciesIsRefused() {
        assertThatThrownBy(() -> service.proposeAdjustment(USD_CASH, CASH, Direction.DEBIT, 1_000, "BRL",
                "mixing currencies by mistake", null, "ana"))
                .hasMessageContaining("denominated in USD");
    }

    @Test
    void ledgerRejectionMarksTheProposalFailed() {
        Proposal p = service.proposeAdjustment(CASH, SUSPENSE, Direction.CREDIT, 999_999_999_999L, "BRL",
                "more than the cash account holds", null, "ana");

        assertThatThrownBy(() -> service.approve(p.id(), p.confirmationCode(), "bruno"))
                .hasMessageContaining("insufficient funds");
        assertThat(service.get(p.id()).status()).isEqualTo(ProposalStatus.FAILED);
    }

    @Test
    void reasonIsRequired() {
        assertThatThrownBy(() -> service.proposeAdjustment(SUSPENSE, CASH, Direction.DEBIT, 1, "BRL", " ok ", null, "ana"))
                .hasMessageContaining("reason is required");
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class InMemoryProposals implements ProposalRepository {
        private final Map<UUID, Proposal> rows = new HashMap<>();

        void replace(Proposal p) {
            rows.put(p.id(), p);
        }

        @Override
        public void insert(Proposal p) {
            rows.put(p.id(), p);
        }

        @Override
        public Optional<Proposal> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Proposal> findActiveReversalOf(UUID transactionId) {
            return rows.values().stream()
                    .filter(p -> p.kind() == ProposalKind.REVERSAL && transactionId.equals(p.targetTransactionId()))
                    .filter(p -> p.status() == ProposalStatus.PENDING || p.status() == ProposalStatus.EXECUTING
                            || p.status() == ProposalStatus.EXECUTED)
                    .findFirst();
        }

        @Override
        public boolean transition(UUID id, ProposalStatus expected, ProposalStatus next, String decidedBy, Instant at, String note) {
            Proposal p = rows.get(id);
            if (p == null || p.status() != expected) {
                return false;
            }
            rows.put(id, new Proposal(p.id(), p.kind(), next, p.currency(), p.postings(), p.externalReference(),
                    p.targetTransactionId(), p.divergenceReference(), p.reason(), p.proposedBy(), p.createdAt(),
                    p.expiresAt(), p.confirmationCode(), decidedBy, at, note != null ? note : p.decisionNote(),
                    p.executedTransactionId()));
            return true;
        }

        @Override
        public void markExecuted(UUID id, UUID transactionId) {
            Proposal p = rows.get(id);
            rows.put(id, new Proposal(p.id(), p.kind(), ProposalStatus.EXECUTED, p.currency(), p.postings(),
                    p.externalReference(), p.targetTransactionId(), p.divergenceReference(), p.reason(), p.proposedBy(),
                    p.createdAt(), p.expiresAt(), p.confirmationCode(), p.decidedBy(), p.decidedAt(), p.decisionNote(),
                    transactionId));
        }
    }
}
