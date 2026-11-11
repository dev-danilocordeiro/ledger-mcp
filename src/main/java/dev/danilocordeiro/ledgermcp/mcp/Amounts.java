package dev.danilocordeiro.ledgermcp.mcp;

import java.math.BigDecimal;
import java.util.Currency;

/** Human-readable amounts next to the minor-unit integers, so the model never divides by 100 itself. */
final class Amounts {

    private Amounts() {
    }

    static String format(Long minor, String currency) {
        if (minor == null) {
            return null;
        }
        int digits;
        try {
            digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException e) {
            digits = 2;
        }
        return BigDecimal.valueOf(minor, digits).toPlainString() + " " + currency;
    }
}
