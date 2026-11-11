package dev.danilocordeiro.ledgermcp.mcp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** Parses tool arguments with error messages that tell the model how to fix the call. */
final class ToolInputs {

    private ToolInputs() {
    }

    static UUID uuid(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be a UUID such as a0000000-0000-4000-8000-000000000001, got '"
                    + value + "'. Account and transaction names are not accepted; ask the user for the id.");
        }
    }

    /** Start of the given UTC day, or the exact instant when a full timestamp is passed. */
    static Instant startOf(String name, String value) {
        return parse(name, value, LocalTime.MIN);
    }

    /** Last nanosecond of the given UTC day, so a date range includes its end date. */
    static Instant endOf(String name, String value) {
        return parse(name, value, LocalTime.MAX);
    }

    static void requireOrdered(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to.");
        }
    }

    static int limit(Integer requested, int defaultValue, int max) {
        if (requested == null) {
            return defaultValue;
        }
        if (requested < 1) {
            throw new IllegalArgumentException("limit must be at least 1.");
        }
        return Math.min(requested, max);
    }

    private static Instant parse(String name, String value, LocalTime timeOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            if (trimmed.length() == 10) {
                return LocalDate.parse(trimmed).atTime(timeOfDay).toInstant(ZoneOffset.UTC);
            }
            return Instant.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be a date like 2026-10-31 (UTC) or an ISO-8601 instant, got '"
                    + value + "'.");
        }
    }
}
