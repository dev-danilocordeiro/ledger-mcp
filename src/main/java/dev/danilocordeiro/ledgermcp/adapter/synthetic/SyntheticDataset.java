package dev.danilocordeiro.ledgermcp.adapter.synthetic;

import dev.danilocordeiro.ledgermcp.domain.Direction;
import dev.danilocordeiro.ledgermcp.domain.Divergence;
import dev.danilocordeiro.ledgermcp.domain.DivergenceKind;
import dev.danilocordeiro.ledgermcp.domain.LedgerTransaction;
import dev.danilocordeiro.ledgermcp.domain.Posting;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Deterministic, 100% synthetic ledger history: same seed, same data, on every start.
 * Covers 2026-09-01 to 2026-11-09. Every identifier is made up; account names are
 * generic roles, never real companies.
 */
public final class SyntheticDataset {

    public static final UUID OPERATING_CASH = account(1);
    public static final UUID CUSTOMER_FUNDS_PAYABLE = account(2);
    public static final UUID CARD_SETTLEMENT_CLEARING = account(3);
    public static final UUID FEE_REVENUE = account(4);
    public static final UUID SUSPENSE = account(5);
    public static final UUID MERCHANT_0042_WALLET = account(6);
    public static final UUID MERCHANT_0117_WALLET = account(7);
    public static final UUID OWNERS_EQUITY = account(8);
    public static final UUID USD_OPERATING_CASH = account(9);
    public static final UUID USD_OWNERS_EQUITY = account(10);

    static final LocalDate FIRST_DAY = LocalDate.of(2026, 9, 1);
    static final LocalDate LAST_DAY = LocalDate.of(2026, 11, 9);
    private static final long SEED = 20260901L;

    final List<SyntheticAccount> accounts;
    final List<LedgerTransaction> transactions;
    final List<Divergence> divergences;

    private SyntheticDataset(List<SyntheticAccount> accounts, List<LedgerTransaction> transactions, List<Divergence> divergences) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.divergences = divergences;
    }

    public static UUID account(int n) {
        return UUID.fromString(String.format("a0000000-0000-4000-8000-%012d", n));
    }

    public static UUID transaction(int n) {
        return UUID.fromString(String.format("b0000000-0000-4000-8000-%012d", n));
    }

    static SyntheticDataset generate() {
        List<SyntheticAccount> accounts = List.of(
                new SyntheticAccount(OPERATING_CASH, "Operating cash", "BRL", Direction.DEBIT, false),
                new SyntheticAccount(CUSTOMER_FUNDS_PAYABLE, "Customer funds payable", "BRL", Direction.CREDIT, false),
                new SyntheticAccount(CARD_SETTLEMENT_CLEARING, "Card settlement clearing", "BRL", Direction.DEBIT, true),
                new SyntheticAccount(FEE_REVENUE, "Fee revenue", "BRL", Direction.CREDIT, false),
                new SyntheticAccount(SUSPENSE, "Suspense", "BRL", Direction.DEBIT, true),
                new SyntheticAccount(MERCHANT_0042_WALLET, "Merchant 0042 wallet", "BRL", Direction.CREDIT, false),
                new SyntheticAccount(MERCHANT_0117_WALLET, "Merchant 0117 wallet", "BRL", Direction.CREDIT, false),
                new SyntheticAccount(OWNERS_EQUITY, "Owner's equity", "BRL", Direction.CREDIT, false),
                new SyntheticAccount(USD_OPERATING_CASH, "USD operating cash", "USD", Direction.DEBIT, false),
                new SyntheticAccount(USD_OWNERS_EQUITY, "USD owner's equity", "USD", Direction.CREDIT, false));

        Builder b = new Builder();
        Random random = new Random(SEED);

        b.post("BRL", at(FIRST_DAY, 8, 0), "opening-balance",
                debit(OPERATING_CASH, 50_000_000), credit(OWNERS_EQUITY, 50_000_000));
        b.post("USD", at(FIRST_DAY, 8, 5), "opening-balance-usd",
                debit(USD_OPERATING_CASH, 2_000_000), credit(USD_OWNERS_EQUITY, 2_000_000));

        Map<UUID, Long> wallets = new HashMap<>(Map.of(MERCHANT_0042_WALLET, 0L, MERCHANT_0117_WALLET, 0L));
        long clearing = 0;
        int ourNumber = 1000;
        List<LedgerTransaction> settlements = new ArrayList<>();

        for (LocalDate day = FIRST_DAY; !day.isAfter(LAST_DAY); day = day.plusDays(1)) {
            int dayIndex = (int) (day.toEpochDay() - FIRST_DAY.toEpochDay());

            long deposit = 50_000 + random.nextInt(1_950_000);
            b.post("BRL", at(day, 9, random.nextInt(60)), null,
                    debit(OPERATING_CASH, deposit), credit(CUSTOMER_FUNDS_PAYABLE, deposit));

            UUID merchant = dayIndex % 2 == 0 ? MERCHANT_0042_WALLET : MERCHANT_0117_WALLET;
            long sale = 20_000 + random.nextInt(980_000);
            String reference = String.format("NN-%06d", ++ourNumber);
            settlements.add(b.post("BRL", at(day, 11, random.nextInt(60)), reference,
                    debit(CARD_SETTLEMENT_CLEARING, sale), credit(merchant, sale)));
            clearing += sale;

            long fee = Math.max(1, Math.round(sale * 0.025));
            b.post("BRL", at(day, 11, 59), "fee-" + reference,
                    debit(merchant, fee), credit(FEE_REVENUE, fee));
            wallets.merge(merchant, sale - fee, Long::sum);

            if (dayIndex % 2 == 1 && clearing > 0) {
                b.post("BRL", at(day, 16, 30), "bank-settlement-" + day,
                        debit(OPERATING_CASH, clearing), credit(CARD_SETTLEMENT_CLEARING, clearing));
                clearing = 0;
            }

            if (dayIndex % 7 == 6) {
                for (UUID wallet : List.of(MERCHANT_0042_WALLET, MERCHANT_0117_WALLET)) {
                    long payout = wallets.get(wallet) * 8 / 10;
                    if (payout > 0) {
                        b.post("BRL", at(day, 18, 0), "payout-" + day,
                                debit(wallet, payout), credit(OPERATING_CASH, payout));
                        wallets.merge(wallet, -payout, Long::sum);
                    }
                }
            }

            if (day.equals(LocalDate.of(2026, 9, 20))) {
                LedgerTransaction mistaken = b.post("BRL", at(day, 14, 0), "manual-entry-0001",
                        debit(SUSPENSE, 125_000), credit(OPERATING_CASH, 125_000));
                b.post("BRL", at(day, 15, 10), "reversal-of:" + mistaken.transactionId(),
                        debit(OPERATING_CASH, 125_000), credit(SUSPENSE, 125_000));
            }
        }

        return new SyntheticDataset(accounts, List.copyOf(b.transactions), divergencesFrom(settlements, random));
    }

    /** Picks settlements from October onward and derives each divergence kind from them. */
    private static List<Divergence> divergencesFrom(List<LedgerTransaction> settlements, Random random) {
        Instant october = at(LocalDate.of(2026, 10, 1), 0, 0);
        List<LedgerTransaction> candidates = settlements.stream().filter(t -> !t.postedAt().isBefore(october)).toList();
        List<Divergence> result = new ArrayList<>();
        int[] missingInCnab = {2, 9, 17, 31};
        int[] amountMismatch = {5, 13, 22, 36};
        for (int i : missingInCnab) {
            LedgerTransaction t = candidates.get(i);
            result.add(new Divergence(DivergenceKind.MISSING_IN_CNAB, t.externalReference(), t.transactionId().toString(),
                    t.postings().getFirst().amountMinor(), null, detectedAfter(t)));
        }
        for (int i : amountMismatch) {
            LedgerTransaction t = candidates.get(i);
            long ledgerAmount = t.postings().getFirst().amountMinor();
            long bankAmount = ledgerAmount - (1 + random.nextInt(4_999));
            result.add(new Divergence(DivergenceKind.AMOUNT_MISMATCH, t.externalReference(), t.transactionId().toString(),
                    ledgerAmount, bankAmount, detectedAfter(t)));
        }
        int[] missingInLedgerDays = {6, 19, 33};
        for (int k = 0; k < missingInLedgerDays.length; k++) {
            LocalDate day = LocalDate.of(2026, 10, 1).plusDays(missingInLedgerDays[k]);
            result.add(new Divergence(DivergenceKind.MISSING_IN_LEDGER, String.format("NN-9%05d", k + 1), null,
                    null, 10_000L + random.nextInt(500_000), at(day, 6, 0)));
        }
        result.sort((a, b) -> b.detectedAt().compareTo(a.detectedAt()));
        return List.copyOf(result);
    }

    private static Instant detectedAfter(LedgerTransaction t) {
        LocalDate next = t.postedAt().atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
        return at(next, 6, 0);
    }

    static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

    private static Posting debit(UUID account, long amount) {
        return new Posting(account, Direction.DEBIT, amount);
    }

    private static Posting credit(UUID account, long amount) {
        return new Posting(account, Direction.CREDIT, amount);
    }

    private static final class Builder {
        private final List<LedgerTransaction> transactions = new ArrayList<>();

        LedgerTransaction post(String currency, Instant at, String externalReference, Posting... postings) {
            LedgerTransaction t = new LedgerTransaction(transaction(transactions.size() + 1), currency,
                    List.of(postings), externalReference, at);
            transactions.add(t);
            return t;
        }
    }
}
