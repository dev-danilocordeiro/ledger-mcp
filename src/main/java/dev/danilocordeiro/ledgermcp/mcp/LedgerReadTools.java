package dev.danilocordeiro.ledgermcp.mcp;

import dev.danilocordeiro.ledgermcp.domain.DivergenceKind;
import dev.danilocordeiro.ledgermcp.domain.StatementPage;
import dev.danilocordeiro.ledgermcp.port.DivergenceReader;
import dev.danilocordeiro.ledgermcp.port.LedgerReader;
import dev.danilocordeiro.ledgermcp.security.RequiredScope;
import dev.danilocordeiro.ledgermcp.security.Scopes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/** Read-only tools. None of them can change the ledger. */
public class LedgerReadTools {

    static final int DEFAULT_ENTRIES = 50;
    static final int MAX_ENTRIES = 200;
    static final int DEFAULT_DIVERGENCES = 50;
    static final int MAX_DIVERGENCES = 200;

    private final LedgerReader ledger;
    private final DivergenceReader recon;

    public LedgerReadTools(LedgerReader ledger, DivergenceReader recon) {
        this.ledger = ledger;
        this.recon = recon;
    }

    @RequiredScope(Scopes.LEDGER_READ)
    @McpTool(name = "get_account_balance", title = "Get account balance",
            annotations = @McpAnnotations(title = "Get account balance", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = """
                    Returns the CURRENT balance of one ledger account, as of its latest posting, in minor units \
                    (cents) plus a formatted amount and the currency.

                    Use when the user asks how much an account holds right now, or before proposing an adjustment \
                    to see where an account stands.
                    Example: "What's the balance of account a0000000-0000-4000-8000-000000000001?" \
                    -> get_account_balance(accountId="a0000000-0000-4000-8000-000000000001").

                    Do not use for:
                    - money movements over a period, or "what happened on this account": use list_account_entries.
                    - a balance on a past date: not available; explain that and offer list_account_entries for the period.
                    - differences between the ledger and the bank: use list_reconciliation_divergences.""")
    public ToolViews.Balance getAccountBalance(
            @McpToolParam(description = "Account id (UUID), e.g. a0000000-0000-4000-8000-000000000001.", required = true)
            String accountId) {
        UUID id = ToolInputs.uuid("accountId", accountId);
        return ledger.balance(id).map(ToolViews.Balance::of)
                .orElseThrow(() -> new IllegalArgumentException("No account " + id + " exists in the ledger."));
    }

    @RequiredScope(Scopes.LEDGER_READ)
    @McpTool(name = "list_account_entries", title = "List account entries",
            annotations = @McpAnnotations(title = "List account entries", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = """
                    Lists the postings that hit ONE account within a date range, newest first: transaction id, \
                    debit or credit, amount (positive when it increases the balance), timestamp and external \
                    reference. Dates are UTC and both ends are inclusive.

                    Use when the user asks what moved on an account, what came in or went out in a period, or needs a \
                    transaction id to inspect or reverse.
                    Example: "Show what hit account a0000000-0000-4000-8000-000000000006 in October 2026" \
                    -> list_account_entries(accountId="a0000000-0000-4000-8000-000000000006", from="2026-10-01", to="2026-10-31").

                    Do not use for:
                    - the current balance only: use get_account_balance (one call, no summing).
                    - all postings of one known transaction: use get_transaction.
                    - bank reconciliation problems: use list_reconciliation_divergences.

                    Results are paged. When nextCursor is not null and the user needs everything, call again with the \
                    same arguments plus cursor=nextCursor.""")
    public ToolViews.Entries listAccountEntries(
            @McpToolParam(description = "Account id (UUID).", required = true) String accountId,
            @McpToolParam(description = "First day to include, YYYY-MM-DD (UTC). Omit for no lower bound.", required = false)
            String from,
            @McpToolParam(description = "Last day to include, YYYY-MM-DD (UTC). Omit for no upper bound.", required = false)
            String to,
            @McpToolParam(description = "Maximum entries in this page, 1-200. Default 50.", required = false) Integer limit,
            @McpToolParam(description = "Opaque nextCursor from a previous page with the same arguments. Omit on the first call.",
                    required = false) String cursor) {
        UUID id = ToolInputs.uuid("accountId", accountId);
        Instant fromInstant = ToolInputs.startOf("from", from);
        Instant toInstant = ToolInputs.endOf("to", to);
        ToolInputs.requireOrdered(fromInstant, toInstant);
        if (ledger.balance(id).isEmpty()) {
            throw new IllegalArgumentException("No account " + id + " exists in the ledger.");
        }
        StatementPage page = ledger.statement(id, fromInstant, toInstant,
                ToolInputs.limit(limit, DEFAULT_ENTRIES, MAX_ENTRIES), cursor);
        List<ToolViews.Entry> entries = page.entries().stream().map(ToolViews.Entry::of).toList();
        String note = entries.isEmpty() ? "No postings on this account in the requested range."
                : page.nextCursor() != null ? "More entries exist; pass nextCursor to get the next page." : null;
        return new ToolViews.Entries(id.toString(), fromInstant, toInstant, entries.size(), entries, page.nextCursor(), note);
    }

    @RequiredScope(Scopes.LEDGER_READ)
    @McpTool(name = "get_transaction", title = "Get transaction",
            annotations = @McpAnnotations(title = "Get transaction", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = """
                    Returns every posting (all accounts, both sides) of ONE transaction, with its currency, posting \
                    time, external reference and whether it is itself a reversal.

                    Use when the user gives a transaction id and asks what it did, which accounts it touched, or \
                    before proposing a reversal so you can show what would be undone.
                    Example: "What did transaction b0000000-0000-4000-8000-000000000123 do?" \
                    -> get_transaction(transactionId="b0000000-0000-4000-8000-000000000123").

                    Do not use for:
                    - finding transactions by date or account: use list_account_entries first, then this.
                    - transactions flagged by the bank reconciliation: use list_reconciliation_divergences.""")
    public ToolViews.Transaction getTransaction(
            @McpToolParam(description = "Transaction id (UUID).", required = true) String transactionId) {
        UUID id = ToolInputs.uuid("transactionId", transactionId);
        return ledger.transaction(id).map(ToolViews.Transaction::of)
                .orElseThrow(() -> new IllegalArgumentException("No transaction " + id + " exists in the ledger."));
    }

    @RequiredScope(Scopes.RECON_READ)
    @McpTool(name = "list_reconciliation_divergences", title = "List reconciliation divergences",
            annotations = @McpAnnotations(title = "List reconciliation divergences", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false),
            description = """
                    Lists disagreements found when reconciling the ledger against the bank's CNAB240 return files, \
                    newest first. Each item has a kind:
                    - MISSING_IN_CNAB: the ledger posted it, the bank has no record (money may not have arrived).
                    - MISSING_IN_LEDGER: the bank reports it, the ledger never posted it.
                    - AMOUNT_MISMATCH: both have it with different amounts; differenceMinor = ledger - bank.
                    Dates filter on when the divergence was detected (UTC, inclusive).

                    Use when the user asks about reconciliation, unmatched or missing payments, "what doesn't match \
                    the bank", or before proposing an adjustment that fixes a divergence.
                    Example: "Which amounts disagree with the bank this month?" \
                    -> list_reconciliation_divergences(kind="AMOUNT_MISMATCH", from="2026-11-01", to="2026-11-30").

                    Do not use for:
                    - an account's balance or movements: use get_account_balance or list_account_entries.
                    - fixing a divergence: this only reads. Propose the fix with propose_adjustment.""")
    public ToolViews.Divergences listReconciliationDivergences(
            @McpToolParam(description = "First detection day to include, YYYY-MM-DD (UTC). Omit for no lower bound.",
                    required = false) String from,
            @McpToolParam(description = "Last detection day to include, YYYY-MM-DD (UTC). Omit for no upper bound.",
                    required = false) String to,
            @McpToolParam(description = "Only this kind. Omit for all kinds.", required = false) DivergenceKind kind,
            @McpToolParam(description = "Maximum items, 1-200. Default 50.", required = false) Integer limit) {
        Instant fromInstant = ToolInputs.startOf("from", from);
        Instant toInstant = ToolInputs.endOf("to", to);
        ToolInputs.requireOrdered(fromInstant, toInstant);
        int max = ToolInputs.limit(limit, DEFAULT_DIVERGENCES, MAX_DIVERGENCES);
        List<ToolViews.DivergenceItem> items = recon.divergences(fromInstant, toInstant, kind, max).stream()
                .map(ToolViews.DivergenceItem::of).toList();
        return new ToolViews.Divergences(fromInstant, toInstant, kind, items.size(), items, items.size() >= max);
    }
}
