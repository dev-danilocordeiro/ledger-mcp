package dev.danilocordeiro.ledgermcp.port;

import dev.danilocordeiro.ledgermcp.domain.Divergence;
import dev.danilocordeiro.ledgermcp.domain.DivergenceKind;
import java.time.Instant;
import java.util.List;

public interface DivergenceReader {

    /** Divergences detected in {@code [from, to]}, newest first. {@code kind} null means all kinds. */
    List<Divergence> divergences(Instant from, Instant to, DivergenceKind kind, int limit);
}
