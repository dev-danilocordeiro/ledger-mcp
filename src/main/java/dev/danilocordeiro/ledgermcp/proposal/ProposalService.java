package dev.danilocordeiro.ledgermcp.proposal;

import dev.danilocordeiro.ledgermcp.domain.AccountBalance;
import dev.danilocordeiro.ledgermcp.domain.Direction;
import dev.danilocordeiro.ledgermcp.domain.LedgerRejectedException;
import dev.danilocordeiro.ledgermcp.domain.LedgerTransaction;
import dev.danilocordeiro.ledgermcp.domain.PostedTransaction;
import dev.danilocordeiro.ledgermcp.domain.Posting;
import dev.danilocordeiro.ledgermcp.domain.Postings;
import dev.danilocordeiro.ledgermcp.domain.TransactionRequest;
import dev.danilocordeiro.ledgermcp.port.LedgerReader;
import dev.danilocordeiro.ledgermcp.port.TransactionPoster;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Write path. Nothing here posts on the first call: {@code propose*} only records what
 * would be posted, and {@link #approve} executes it after checking, in order, that
 * approvals are enabled, the proposal is still pending and unexpired, the confirmation
 * code matches the stored content, and the approver is not the proposer.
 */
public class ProposalService {

    public static final String REVERSAL_PREFIX = "reversal-of:";
    static final int MIN_REASON_LENGTH = 10;

    private final LedgerReader ledger;
    private final TransactionPoster poster;
    private final ProposalRepository proposals;
    private final Clock clock;
    private final Duration ttl;
    private final boolean approvalsEnabled;

    public ProposalService(LedgerReader ledger, TransactionPoster poster, ProposalRepository proposals,
                           Clock clock, Duration ttl, boolean approvalsEnabled) {
        this.ledger = ledger;
        this.poster = poster;
        this.proposals = proposals;
        this.clock = clock;
        this.ttl = ttl;
        this.approvalsEnabled = approvalsEnabled;
    }

    public Proposal proposeReversal(UUID transactionId, String reason, String proposer) {
        requireReason(reason);
        LedgerTransaction original = ledger.transaction(transactionId)
                .orElseThrow(() -> new ProposalRuleException("No transaction " + transactionId + " exists in the ledger."));
        if (original.externalReference() != null && original.externalReference().startsWith(REVERSAL_PREFIX)) {
            throw new ProposalRuleException("Transaction " + transactionId + " is itself a reversal ("
                    + original.externalReference() + "). Reversing it would re-apply the original; propose an adjustment instead.");
        }
        proposals.findActiveReversalOf(transactionId).ifPresent(existing -> {
            throw new ProposalRuleException("Transaction " + transactionId + " already has reversal proposal "
                    + existing.id() + " in status " + existing.status() + ". Use get_proposal on it instead of proposing again.");
        });
        List<Posting> mirrored = original.postings().stream().map(Posting::mirrored).toList();
        return create(ProposalKind.REVERSAL, original.currency(), mirrored, REVERSAL_PREFIX + transactionId,
                transactionId, null, reason, proposer);
    }

    public Proposal proposeAdjustment(UUID accountId, UUID offsetAccountId, Direction direction, long amountMinor,
                                      String currency, String reason, String divergenceReference, String proposer) {
        requireReason(reason);
        if (accountId.equals(offsetAccountId)) {
            throw new ProposalRuleException("accountId and offsetAccountId must be different accounts.");
        }
        if (amountMinor <= 0) {
            throw new ProposalRuleException("amountMinor must be a positive integer number of minor units (cents).");
        }
        String normalizedCurrency = currency == null ? "" : currency.trim().toUpperCase();
        requireAccountInCurrency(accountId, normalizedCurrency);
        requireAccountInCurrency(offsetAccountId, normalizedCurrency);
        List<Posting> postings = List.of(
                new Posting(accountId, direction, amountMinor),
                new Posting(offsetAccountId, direction.opposite(), amountMinor));
        String externalReference = divergenceReference == null || divergenceReference.isBlank()
                ? null
                : "adjustment-for:" + divergenceReference.trim();
        return create(ProposalKind.ADJUSTMENT, normalizedCurrency, postings, externalReference, null,
                blankToNull(divergenceReference), reason, proposer);
    }

    /** Reads a proposal, expiring it first if its deadline has passed. */
    public Proposal get(UUID id) {
        Proposal proposal = load(id);
        if (proposal.isExpiredAt(clock.instant())) {
            proposals.transition(id, ProposalStatus.PENDING, ProposalStatus.EXPIRED, "system", clock.instant(), "expired before approval");
            return load(id);
        }
        return proposal;
    }

    public Proposal approve(UUID id, String confirmationCode, String approver) {
        if (!approvalsEnabled) {
            throw new ProposalRuleException("Approvals are disabled on this server. In local stdio mode a proposal can "
                    + "only be approved over the HTTP transport, by a person holding the ledger:approve scope.");
        }
        Proposal proposal = get(id);
        String expectedCode = ConfirmationCodes.compute(proposal.id(), proposal.kind(), proposal.currency(),
                proposal.externalReference(), proposal.postings());
        if (!expectedCode.equals(proposal.confirmationCode())) {
            throw new IllegalStateException("Stored proposal " + id + " does not match its confirmation code; refusing to execute.");
        }
        if (!ConfirmationCodes.matches(expectedCode, confirmationCode)) {
            throw new ProposalRuleException("Confirmation code does not match proposal " + id
                    + ". Ask the human approver to read it from the proposal they reviewed.");
        }
        switch (proposal.status()) {
            case EXECUTED:
                return proposal;
            case EXECUTING:
                if (!approver.equals(proposal.decidedBy())) {
                    throw new ProposalRuleException("Proposal " + id + " is already being executed by " + proposal.decidedBy() + ".");
                }
                return execute(proposal);
            case PENDING:
                break;
            default:
                throw new ProposalRuleException("Proposal " + id + " is " + proposal.status()
                        + " and can no longer be approved. Create a new proposal if the change is still needed.");
        }
        if (approver.equals(proposal.proposedBy())) {
            throw new ProposalRuleException("Four-eyes rule: " + approver + " created proposal " + id
                    + " and cannot approve it. A different person with the ledger:approve scope must approve.");
        }
        if (!proposals.transition(id, ProposalStatus.PENDING, ProposalStatus.EXECUTING, approver, clock.instant(), null)) {
            throw new ProposalRuleException("Proposal " + id + " changed status while being approved; read it again with get_proposal.");
        }
        return execute(load(id));
    }

    public Proposal reject(UUID id, String reason, String rejecter) {
        requireReason(reason);
        Proposal proposal = get(id);
        if (proposal.status() != ProposalStatus.PENDING) {
            throw new ProposalRuleException("Proposal " + id + " is " + proposal.status() + "; only PENDING proposals can be rejected.");
        }
        if (!proposals.transition(id, ProposalStatus.PENDING, ProposalStatus.REJECTED, rejecter, clock.instant(), reason.trim())) {
            throw new ProposalRuleException("Proposal " + id + " changed status while being rejected; read it again with get_proposal.");
        }
        return load(id);
    }

    private Proposal execute(Proposal proposal) {
        TransactionRequest request = new TransactionRequest(proposal.id().toString(), proposal.currency(),
                proposal.postings(), proposal.externalReference());
        PostedTransaction posted;
        try {
            posted = poster.post(request);
        } catch (LedgerRejectedException e) {
            proposals.transition(proposal.id(), ProposalStatus.EXECUTING, ProposalStatus.FAILED,
                    proposal.decidedBy(), clock.instant(), e.getMessage());
            throw new ProposalRuleException("The ledger rejected proposal " + proposal.id() + ": " + e.getMessage()
                    + ". Nothing was posted. The proposal is now FAILED.");
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not confirm whether proposal " + proposal.id()
                    + " was posted (" + e.getMessage() + "). It stays EXECUTING. Calling approve_proposal again with the "
                    + "same id and code is safe: the ledger deduplicates on the proposal id.", e);
        }
        proposals.markExecuted(proposal.id(), posted.transactionId());
        return load(proposal.id());
    }

    private Proposal create(ProposalKind kind, String currency, List<Posting> postings, String externalReference,
                            UUID targetTransactionId, String divergenceReference, String reason, String proposer) {
        Postings.requireBalanced(postings);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String code = ConfirmationCodes.compute(id, kind, currency, externalReference, postings);
        Proposal proposal = new Proposal(id, kind, ProposalStatus.PENDING, currency, postings, externalReference,
                targetTransactionId, divergenceReference, reason.trim(), proposer, now, now.plus(ttl), code,
                null, null, null, null);
        proposals.insert(proposal);
        return proposal;
    }

    private void requireAccountInCurrency(UUID accountId, String currency) {
        AccountBalance balance = ledger.balance(accountId)
                .orElseThrow(() -> new ProposalRuleException("Account " + accountId + " does not exist in the ledger."));
        if (!balance.currency().equals(currency)) {
            throw new ProposalRuleException("Account " + accountId + " is denominated in " + balance.currency()
                    + ", not " + currency + ". Both accounts of an adjustment must use the adjustment's currency.");
        }
    }

    private Proposal load(UUID id) {
        return proposals.find(id).orElseThrow(() -> new ProposalRuleException("No proposal " + id + " exists."));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < MIN_REASON_LENGTH) {
            throw new ProposalRuleException("reason is required (at least " + MIN_REASON_LENGTH
                    + " characters) and is recorded in the audit trail. Ask the user why the change is needed.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
