package dev.danilocordeiro.ledgermcp.adapter.synthetic;

import dev.danilocordeiro.ledgermcp.domain.AccountBalance;
import dev.danilocordeiro.ledgermcp.domain.Divergence;
import dev.danilocordeiro.ledgermcp.domain.DivergenceKind;
import dev.danilocordeiro.ledgermcp.domain.LedgerRejectedException;
import dev.danilocordeiro.ledgermcp.domain.LedgerTransaction;
import dev.danilocordeiro.ledgermcp.domain.PostedTransaction;
import dev.danilocordeiro.ledgermcp.domain.Posting;
import dev.danilocordeiro.ledgermcp.domain.StatementEntry;
import dev.danilocordeiro.ledgermcp.domain.StatementPage;
import dev.danilocordeiro.ledgermcp.domain.TransactionRequest;
import dev.danilocordeiro.ledgermcp.port.DivergenceReader;
import dev.danilocordeiro.ledgermcp.port.LedgerReader;
import dev.danilocordeiro.ledgermcp.port.TransactionPoster;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory ledger seeded from {@link SyntheticDataset}. Postings made through
 * {@link #post} live until the process exits, which is enough to watch an approved
 * proposal change a balance.
 */
public class SyntheticLedger implements LedgerReader, TransactionPoster, DivergenceReader {

    private static final Comparator<StatementEntry> NEWEST_FIRST = Comparator
            .comparing(StatementEntry::occurredAt).reversed()
            .thenComparing(StatementEntry::transactionId, Comparator.reverseOrder());

    private final Clock clock;
    private final Map<UUID, SyntheticAccount> accounts = new LinkedHashMap<>();
    private final Map<UUID, LedgerTransaction> transactions = new LinkedHashMap<>();
    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<UUID, Instant> updatedAt = new HashMap<>();
    private final Map<String, Posted> byIdempotencyKey = new HashMap<>();
    private final List<Divergence> divergences;

    private record Posted(TransactionRequest request, PostedTransaction result) {
    }

    public SyntheticLedger(Clock clock) {
        this.clock = clock;
        SyntheticDataset dataset = SyntheticDataset.generate();
        dataset.accounts.forEach(a -> accounts.put(a.id(), a));
        dataset.transactions.forEach(this::apply);
        this.divergences = dataset.divergences;
    }

    @Override
    public synchronized Optional<AccountBalance> balance(UUID accountId) {
        SyntheticAccount account = accounts.get(accountId);
        if (account == null) {
            return Optional.empty();
        }
        return Optional.of(new AccountBalance(account.id(), account.name(), account.currency(),
                balances.getOrDefault(accountId, 0L), account.normalSide(), updatedAt.get(accountId)));
    }

    @Override
    public synchronized StatementPage statement(UUID accountId, Instant from, Instant to, int limit, String cursor) {
        SyntheticAccount account = accounts.get(accountId);
        if (account == null) {
            return new StatementPage(accountId, List.of(), null);
        }
        List<StatementEntry> matching = new ArrayList<>();
        for (LedgerTransaction t : transactions.values()) {
            if ((from != null && t.postedAt().isBefore(from)) || (to != null && t.postedAt().isAfter(to))) {
                continue;
            }
            for (Posting p : t.postings()) {
                if (p.accountId().equals(accountId)) {
                    long signed = p.direction() == account.normalSide() ? p.amountMinor() : -p.amountMinor();
                    matching.add(new StatementEntry(t.transactionId(), p.direction(), p.amountMinor(), signed,
                            t.currency(), t.postedAt(), t.externalReference()));
                }
            }
        }
        matching.sort(NEWEST_FIRST);
        int offset = decodeCursor(cursor);
        int end = Math.min(matching.size(), offset + limit);
        String next = end < matching.size() ? encodeCursor(end) : null;
        return new StatementPage(accountId, List.copyOf(matching.subList(Math.min(offset, end), end)), next);
    }

    @Override
    public synchronized Optional<LedgerTransaction> transaction(UUID transactionId) {
        return Optional.ofNullable(transactions.get(transactionId));
    }

    @Override
    public List<Divergence> divergences(Instant from, Instant to, DivergenceKind kind, int limit) {
        return divergences.stream()
                .filter(d -> from == null || !d.detectedAt().isBefore(from))
                .filter(d -> to == null || !d.detectedAt().isAfter(to))
                .filter(d -> kind == null || d.kind() == kind)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized PostedTransaction post(TransactionRequest request) {
        Posted previous = byIdempotencyKey.get(request.idempotencyKey());
        if (previous != null) {
            if (!previous.request().equals(request)) {
                throw new LedgerRejectedException("idempotency key " + request.idempotencyKey() + " was already used with a different payload");
            }
            PostedTransaction first = previous.result();
            return new PostedTransaction(first.transactionId(), first.postedAt(), true);
        }
        Map<UUID, Long> projected = new HashMap<>();
        for (Posting p : request.postings()) {
            SyntheticAccount account = accounts.get(p.accountId());
            if (account == null) {
                throw new LedgerRejectedException("account " + p.accountId() + " does not exist");
            }
            if (!account.currency().equals(request.currency())) {
                throw new LedgerRejectedException("account " + p.accountId() + " is in " + account.currency()
                        + ", transaction is in " + request.currency());
            }
            long delta = p.direction() == account.normalSide() ? p.amountMinor() : -p.amountMinor();
            projected.merge(p.accountId(), balances.getOrDefault(p.accountId(), 0L) + delta,
                    (current, ignored) -> current + delta);
        }
        projected.forEach((accountId, balance) -> {
            if (balance < 0 && !accounts.get(accountId).allowsNegative()) {
                throw new LedgerRejectedException("insufficient funds: account " + accountId + " would go to " + balance);
            }
        });
        LedgerTransaction t = new LedgerTransaction(UUID.randomUUID(), request.currency(), request.postings(),
                request.externalReference(), clock.instant());
        apply(t);
        PostedTransaction result = new PostedTransaction(t.transactionId(), t.postedAt(), false);
        byIdempotencyKey.put(request.idempotencyKey(), new Posted(request, result));
        return result;
    }

    private void apply(LedgerTransaction t) {
        transactions.put(t.transactionId(), t);
        for (Posting p : t.postings()) {
            SyntheticAccount account = accounts.get(p.accountId());
            long delta = p.direction() == account.normalSide() ? p.amountMinor() : -p.amountMinor();
            balances.merge(p.accountId(), delta, Long::sum);
            updatedAt.put(p.accountId(), t.postedAt());
        }
    }

    private static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("offset:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            return Integer.parseInt(raw.substring("offset:".length()));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid cursor: pass the nextCursor value from the previous page unchanged");
        }
    }
}
