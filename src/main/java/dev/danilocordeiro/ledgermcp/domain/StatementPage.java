package dev.danilocordeiro.ledgermcp.domain;

import java.util.List;
import java.util.UUID;

/** Newest entries first. {@code nextCursor} is null on the last page. */
public record StatementPage(UUID accountId, List<StatementEntry> entries, String nextCursor) {
}
