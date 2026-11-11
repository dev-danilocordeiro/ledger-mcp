package dev.danilocordeiro.ledgermcp.adapter.live;

import dev.danilocordeiro.ledgermcp.domain.Divergence;
import dev.danilocordeiro.ledgermcp.domain.DivergenceKind;
import dev.danilocordeiro.ledgermcp.port.DivergenceReader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ledger-recon has no HTTP API for its results, so this reads its
 * {@code reconciliation_result} table directly, with a read-only database role.
 * Each unmatched row holds a JSON report whose {@code divergences} array uses the
 * same field names as ledger-recon's Go model.
 */
public class ReconDivergenceReader implements DivergenceReader {

    private static final Instant MIN = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59Z");

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ReconDivergenceReader(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private record Row(String transactionId, String report, Instant processedAt) {
    }

    @Override
    public List<Divergence> divergences(Instant from, Instant to, DivergenceKind kind, int limit) {
        List<Row> rows = jdbc.sql("""
                        SELECT transaction_id, report::text AS report, processed_at
                          FROM reconciliation_result
                         WHERE matched = false AND processed_at >= :from AND processed_at <= :to
                         ORDER BY processed_at DESC, transaction_id DESC
                        """)
                .param("from", OffsetDateTime.ofInstant(from == null ? MIN : from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to == null ? MAX : to, ZoneOffset.UTC))
                .query((rs, n) -> new Row(rs.getString("transaction_id"), rs.getString("report"),
                        rs.getObject("processed_at", OffsetDateTime.class).toInstant()))
                .list();
        List<Divergence> result = new ArrayList<>();
        for (Row row : rows) {
            JsonNode divergences = json.readTree(row.report()).path("divergences");
            for (JsonNode d : divergences) {
                DivergenceKind k = DivergenceKind.fromWireName(d.path("kind").asString());
                if (kind != null && k != kind) {
                    continue;
                }
                result.add(new Divergence(k, d.path("reference").asString(), row.transactionId(),
                        optionalLong(d, "ledgerAmountMinor"), optionalLong(d, "cnabAmountMinor"), row.processedAt()));
                if (result.size() >= limit) {
                    return result;
                }
            }
        }
        return result;
    }

    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }
}
