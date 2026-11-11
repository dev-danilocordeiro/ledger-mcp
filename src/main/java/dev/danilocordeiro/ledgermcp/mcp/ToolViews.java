package dev.danilocordeiro.ledgermcp.mcp;

import dev.danilocordeiro.ledgermcp.domain.AccountBalance;
import dev.danilocordeiro.ledgermcp.domain.Direction;
import dev.danilocordeiro.ledgermcp.domain.Divergence;
import dev.danilocordeiro.ledgermcp.domain.DivergenceKind;
import dev.danilocordeiro.ledgermcp.domain.LedgerTransaction;
import dev.danilocordeiro.ledgermcp.domain.StatementEntry;
import java.time.Instant;
import java.util.List;

/** What the tools return. Every amount comes as minor units plus a formatted string. */
public final class ToolViews {

    private ToolViews() {
    }

    public record Balance(String accountId, String accountName, String currency, long balanceMinor, String balance,
                          Instant lastPostingAt) {

        static Balance of(AccountBalance b) {
            return new Balance(b.accountId().toString(), b.displayName(), b.currency(), b.balanceMinor(),
                    Amounts.format(b.balanceMinor(), b.currency()), b.updatedAt());
        }
    }

    public record Entry(String transactionId, Direction direction, long amountMinor, long signedAmountMinor,
                        String amount, Instant occurredAt, String externalReference) {

        static Entry of(StatementEntry e) {
            return new Entry(e.transactionId().toString(), e.direction(), e.amountMinor(), e.signedMinor(),
                    Amounts.format(e.signedMinor(), e.currency()), e.occurredAt(), e.externalReference());
        }
    }

    public record Entries(String accountId, Instant from, Instant to, int count, List<Entry> entries, String nextCursor,
                          String note) {
    }

    public record TransactionLeg(String accountId, Direction direction, long amountMinor, String amount) {
    }

    public record Transaction(String transactionId, String currency, Instant postedAt, String externalReference,
                              boolean isReversal, List<TransactionLeg> postings) {

        static Transaction of(LedgerTransaction t) {
            return new Transaction(t.transactionId().toString(), t.currency(), t.postedAt(), t.externalReference(),
                    t.externalReference() != null && t.externalReference().startsWith("reversal-of:"),
                    t.postings().stream()
                            .map(p -> new TransactionLeg(p.accountId().toString(), p.direction(), p.amountMinor(),
                                    Amounts.format(p.amountMinor(), t.currency())))
                            .toList());
        }
    }

    public record DivergenceItem(DivergenceKind kind, String meaning, String reference, String transactionId,
                                 Long ledgerAmountMinor, Long bankAmountMinor, Long differenceMinor, Instant detectedAt) {

        static DivergenceItem of(Divergence d) {
            Long difference = d.ledgerAmountMinor() != null && d.cnabAmountMinor() != null
                    ? d.ledgerAmountMinor() - d.cnabAmountMinor()
                    : null;
            String meaning = switch (d.kind()) {
                case MISSING_IN_CNAB -> "posted in the ledger, absent from the bank return file";
                case MISSING_IN_LEDGER -> "reported by the bank, never posted in the ledger";
                case AMOUNT_MISMATCH -> "present on both sides with different amounts";
            };
            return new DivergenceItem(d.kind(), meaning, d.reference(), d.transactionId(), d.ledgerAmountMinor(),
                    d.cnabAmountMinor(), difference, d.detectedAt());
        }
    }

    public record Divergences(Instant from, Instant to, DivergenceKind kind, int count, List<DivergenceItem> divergences,
                              boolean limitReached) {
    }

    public record ProposedLeg(String accountId, String accountName, Direction direction, long amountMinor, String amount,
                              Long currentBalanceMinor, Long projectedBalanceMinor) {
    }

    public record Proposal(String proposalId, String kind, String status, String currency, List<ProposedLeg> postings,
                           String externalReference, String targetTransactionId, String divergenceReference,
                           String reason, String proposedBy, Instant createdAt, Instant expiresAt,
                           String confirmationCode, String decidedBy, Instant decidedAt, String decisionNote,
                           String executedTransactionId, String nextStep) {
    }
}
