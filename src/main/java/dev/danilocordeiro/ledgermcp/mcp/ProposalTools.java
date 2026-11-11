package dev.danilocordeiro.ledgermcp.mcp;

import dev.danilocordeiro.ledgermcp.domain.AccountBalance;
import dev.danilocordeiro.ledgermcp.domain.Direction;
import dev.danilocordeiro.ledgermcp.port.LedgerReader;
import dev.danilocordeiro.ledgermcp.proposal.Proposal;
import dev.danilocordeiro.ledgermcp.proposal.ProposalService;
import dev.danilocordeiro.ledgermcp.proposal.ProposalStatus;
import dev.danilocordeiro.ledgermcp.security.CallerResolver;
import dev.danilocordeiro.ledgermcp.security.RequiredScope;
import dev.danilocordeiro.ledgermcp.security.Scopes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * Write tools. Proposing never changes the ledger. Only {@code approve_proposal} posts,
 * and only for a different person than the proposer.
 */
public class ProposalTools {

    private final ProposalService proposals;
    private final LedgerReader ledger;

    public ProposalTools(ProposalService proposals, LedgerReader ledger) {
        this.proposals = proposals;
        this.ledger = ledger;
    }

    @RequiredScope(Scopes.LEDGER_PROPOSE)
    @McpTool(name = "propose_reversal", title = "Propose reversal",
            annotations = @McpAnnotations(title = "Propose reversal", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false),
            description = """
                    Drafts the full reversal of ONE posted transaction: a new transaction that mirrors every posting \
                    (each debit becomes a credit and vice versa). It DOES NOT post anything. It returns a proposal \
                    with an id, the exact postings, the balance impact and a confirmation code, valid for 15 minutes.

                    Use when the user says a whole transaction was a mistake, a duplicate, or must be undone/cancelled/\
                    refunded in full.
                    Example: "Transaction b0000000-0000-4000-8000-000000000123 was posted twice, undo it" \
                    -> propose_reversal(transactionId="b0000000-0000-4000-8000-000000000123", reason="Duplicate posting reported by user").

                    Do not use for:
                    - correcting part of an amount, or moving money between two accounts: use propose_adjustment.
                    - a transaction that is itself a reversal: refused; propose an adjustment instead.

                    After calling: show the proposal to the user and stop. Never call approve_proposal yourself \
                    afterwards; a different person must approve it.""")
    public ToolViews.Proposal proposeReversal(
            @McpToolParam(description = "Id (UUID) of the transaction to reverse.", required = true) String transactionId,
            @McpToolParam(description = "Why it must be reversed, in the user's words (min 10 chars). Stored in the audit trail.",
                    required = true) String reason) {
        Proposal p = proposals.proposeReversal(ToolInputs.uuid("transactionId", transactionId), reason,
                CallerResolver.current().principal());
        return view(p);
    }

    @RequiredScope(Scopes.LEDGER_PROPOSE)
    @McpTool(name = "propose_adjustment", title = "Propose adjustment",
            annotations = @McpAnnotations(title = "Propose adjustment", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false),
            description = """
                    Drafts a two-legged correcting entry: `direction` of `amountMinor` on accountId, and the opposite \
                    direction on offsetAccountId. It DOES NOT post anything. It returns a proposal with an id, the \
                    exact postings, the balance impact and a confirmation code, valid for 15 minutes.

                    Use when the user wants to correct a balance by a specific amount, book a missing entry, write \
                    off a difference, or fix a reconciliation divergence (pass its reference as divergenceReference). \
                    Use the Suspense account a0000000-0000-4000-8000-000000000005 as the offset when the user does not name one.
                    Example: "Bank paid 12.40 BRL less on NN-001045; book the difference against suspense" \
                    -> propose_adjustment(accountId="a0000000-0000-4000-8000-000000000003", \
                    offsetAccountId="a0000000-0000-4000-8000-000000000005", direction="CREDIT", amountMinor=1240, \
                    currency="BRL", reason="Bank short-paid NN-001045", divergenceReference="NN-001045").

                    Do not use for:
                    - undoing a whole transaction: use propose_reversal.
                    - executing the change: proposals are executed only by approve_proposal, called by a human approver.

                    After calling: show the proposal to the user and stop. Never call approve_proposal yourself \
                    afterwards.""")
    public ToolViews.Proposal proposeAdjustment(
            @McpToolParam(description = "Account (UUID) the adjustment is about.", required = true) String accountId,
            @McpToolParam(description = "Account (UUID) that takes the opposite leg, e.g. Suspense.", required = true)
            String offsetAccountId,
            @McpToolParam(description = "Side posted on accountId; offsetAccountId gets the opposite side.", required = true)
            Direction direction,
            @McpToolParam(description = "Positive amount in minor units (cents): 12.40 BRL is 1240.", required = true)
            Long amountMinor,
            @McpToolParam(description = "ISO-4217 code of both accounts, e.g. BRL.", required = true) String currency,
            @McpToolParam(description = "Why the adjustment is needed (min 10 chars). Stored in the audit trail.",
                    required = true) String reason,
            @McpToolParam(description = "Reference of the reconciliation divergence this fixes, e.g. NN-001045.",
                    required = false) String divergenceReference) {
        if (direction == null || amountMinor == null) {
            throw new IllegalArgumentException("direction and amountMinor are required.");
        }
        Proposal p = proposals.proposeAdjustment(ToolInputs.uuid("accountId", accountId),
                ToolInputs.uuid("offsetAccountId", offsetAccountId), direction, amountMinor, currency, reason,
                divergenceReference, CallerResolver.current().principal());
        return view(p);
    }

    @RequiredScope(Scopes.LEDGER_PROPOSE)
    @McpTool(name = "get_proposal", title = "Get proposal",
            annotations = @McpAnnotations(title = "Get proposal", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = """
                    Returns one reversal or adjustment proposal with its current status: PENDING (awaiting a human), \
                    EXECUTED (posted; includes the ledger transaction id), REJECTED, EXPIRED, FAILED (the ledger \
                    refused it) or EXECUTING.

                    Use when the user asks whether a proposal was approved, what it contains, or why it failed.
                    Example: "Was proposal 6f1c...e2 approved?" -> get_proposal(proposalId="6f1c...e2").

                    Do not use for: posted transactions that did not come from a proposal; use get_transaction.""")
    public ToolViews.Proposal getProposal(
            @McpToolParam(description = "Proposal id (UUID) returned by propose_reversal or propose_adjustment.",
                    required = true) String proposalId) {
        return view(proposals.get(ToolInputs.uuid("proposalId", proposalId)));
    }

    @RequiredScope(Scopes.LEDGER_APPROVE)
    @McpTool(name = "approve_proposal", title = "Approve and execute proposal",
            annotations = @McpAnnotations(title = "Approve and execute proposal", readOnlyHint = false,
                    destructiveHint = true, idempotentHint = true, openWorldHint = false),
            description = """
                    EXECUTES a pending proposal: posts its transaction to the ledger. This is the only tool that \
                    changes money. It requires the ledger:approve scope, the proposal's confirmation code, and an \
                    approver who is not the person who created the proposal (four-eyes rule).

                    Use ONLY when a human approver, in this conversation, explicitly says to approve a specific \
                    proposal id and gives its confirmation code.
                    Example: "I reviewed proposal 6f1c...e2, approve it, code 7A3F-19C2" \
                    -> approve_proposal(proposalId="6f1c...e2", confirmationCode="7A3F-19C2").

                    Do not use:
                    - right after propose_reversal or propose_adjustment, or on your own initiative.
                    - when the user only asked for a proposal, a draft, or "what would it take".
                    - to reject a proposal: use reject_proposal.""")
    public ToolViews.Proposal approveProposal(
            @McpToolParam(description = "Proposal id (UUID).", required = true) String proposalId,
            @McpToolParam(description = "Confirmation code the human approver read from the proposal, e.g. 7A3F-19C2.",
                    required = true) String confirmationCode) {
        Proposal p = proposals.approve(ToolInputs.uuid("proposalId", proposalId), confirmationCode,
                CallerResolver.current().principal());
        return view(p);
    }

    @RequiredScope(Scopes.LEDGER_APPROVE)
    @McpTool(name = "reject_proposal", title = "Reject proposal",
            annotations = @McpAnnotations(title = "Reject proposal", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false),
            description = """
                    Rejects a PENDING proposal so it can never be executed. Nothing is posted.

                    Use when a human approver says a proposal is wrong, not needed, or must not go ahead.
                    Example: "Reject proposal 6f1c...e2, the amount is wrong" \
                    -> reject_proposal(proposalId="6f1c...e2", reason="Amount is wrong per approver").

                    Do not use for: proposals that are already EXECUTED (propose a reversal of the posted \
                    transaction instead), or to approve (use approve_proposal).""")
    public ToolViews.Proposal rejectProposal(
            @McpToolParam(description = "Proposal id (UUID).", required = true) String proposalId,
            @McpToolParam(description = "Why it is rejected (min 10 chars). Stored in the audit trail.", required = true)
            String reason) {
        Proposal p = proposals.reject(ToolInputs.uuid("proposalId", proposalId), reason,
                CallerResolver.current().principal());
        return view(p);
    }

    private ToolViews.Proposal view(Proposal p) {
        boolean pending = p.status() == ProposalStatus.PENDING;
        Map<UUID, Optional<AccountBalance>> balances = new HashMap<>();
        List<ToolViews.ProposedLeg> legs = p.postings().stream().map(leg -> {
            Optional<AccountBalance> balance = pending
                    ? balances.computeIfAbsent(leg.accountId(), ledger::balance)
                    : Optional.empty();
            return new ToolViews.ProposedLeg(leg.accountId().toString(),
                    balance.map(AccountBalance::displayName).orElse(null), leg.direction(), leg.amountMinor(),
                    Amounts.format(leg.amountMinor(), p.currency()),
                    balance.map(AccountBalance::balanceMinor).orElse(null),
                    balance.map(b -> b.projectedAfter(leg)).orElse(null));
        }).toList();
        return new ToolViews.Proposal(p.id().toString(), p.kind().name(), p.status().name(), p.currency(), legs,
                p.externalReference(), str(p.targetTransactionId()), p.divergenceReference(), p.reason(), p.proposedBy(),
                p.createdAt(), p.expiresAt(), pending ? p.confirmationCode() : null, p.decidedBy(), p.decidedAt(),
                p.decisionNote(), str(p.executedTransactionId()), nextStep(p));
    }

    private static String nextStep(Proposal p) {
        return switch (p.status()) {
            case PENDING -> "Nothing has been posted. Show the user these postings, the reason, the confirmation code "
                    + p.confirmationCode() + " and the expiry (" + p.expiresAt() + "). A person other than "
                    + p.proposedBy() + ", holding the ledger:approve scope, must approve it. Do not call "
                    + "approve_proposal unless that person explicitly tells you to.";
            case EXECUTING -> "Posting is in progress or its outcome is unknown. The approver can call approve_proposal "
                    + "again with the same id and code; the ledger deduplicates on the proposal id.";
            case EXECUTED -> "Posted to the ledger as transaction " + p.executedTransactionId() + ".";
            case FAILED -> "The ledger refused it (" + p.decisionNote() + "). Nothing was posted.";
            case REJECTED -> "Rejected by " + p.decidedBy() + ". Nothing was posted.";
            case EXPIRED -> "Expired before approval. Nothing was posted. Propose again if still needed.";
        };
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }
}
