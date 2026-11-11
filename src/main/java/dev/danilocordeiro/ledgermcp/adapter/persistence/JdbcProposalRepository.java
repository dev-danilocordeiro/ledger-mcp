package dev.danilocordeiro.ledgermcp.adapter.persistence;

import dev.danilocordeiro.ledgermcp.domain.Posting;
import dev.danilocordeiro.ledgermcp.proposal.Proposal;
import dev.danilocordeiro.ledgermcp.proposal.ProposalKind;
import dev.danilocordeiro.ledgermcp.proposal.ProposalRepository;
import dev.danilocordeiro.ledgermcp.proposal.ProposalStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public class JdbcProposalRepository implements ProposalRepository {

    private static final TypeReference<List<Posting>> POSTINGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String jsonCast;

    public JdbcProposalRepository(JdbcClient jdbc, JsonMapper json, boolean postgres) {
        this.jdbc = jdbc;
        this.json = json;
        this.jsonCast = postgres ? "::jsonb" : "";
    }

    @Override
    public void insert(Proposal p) {
        jdbc.sql("""
                        INSERT INTO proposal (id, kind, status, currency, postings, external_reference, target_transaction_id,
                                              divergence_reference, reason, proposed_by, created_at, expires_at, confirmation_code)
                        VALUES (:id, :kind, :status, :currency, CAST(:postings AS VARCHAR)%s, :externalReference, :target,
                                :divergenceReference, :reason, :proposedBy, :createdAt, :expiresAt, :code)
                        """.formatted(jsonCast))
                .param("id", p.id())
                .param("kind", p.kind().name())
                .param("status", p.status().name())
                .param("currency", p.currency())
                .param("postings", json.writeValueAsString(p.postings()))
                .param("externalReference", p.externalReference())
                .param("target", p.targetTransactionId())
                .param("divergenceReference", p.divergenceReference())
                .param("reason", p.reason())
                .param("proposedBy", p.proposedBy())
                .param("createdAt", utc(p.createdAt()))
                .param("expiresAt", utc(p.expiresAt()))
                .param("code", p.confirmationCode())
                .update();
    }

    @Override
    public Optional<Proposal> find(UUID id) {
        return jdbc.sql("SELECT * FROM proposal WHERE id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Proposal> findActiveReversalOf(UUID transactionId) {
        return jdbc.sql("""
                        SELECT * FROM proposal
                         WHERE kind = 'REVERSAL' AND target_transaction_id = :target
                           AND status IN ('PENDING', 'EXECUTING', 'EXECUTED')
                        """)
                .param("target", transactionId)
                .query(this::map)
                .optional();
    }

    @Override
    public boolean transition(UUID id, ProposalStatus expected, ProposalStatus next, String decidedBy, Instant decidedAt, String note) {
        return jdbc.sql("""
                        UPDATE proposal
                           SET status = :next, decided_by = :decidedBy, decided_at = :decidedAt,
                               decision_note = COALESCE(:note, decision_note)
                         WHERE id = :id AND status = :expected
                        """)
                .param("next", next.name())
                .param("decidedBy", decidedBy)
                .param("decidedAt", utc(decidedAt))
                .param("note", note)
                .param("id", id)
                .param("expected", expected.name())
                .update() == 1;
    }

    @Override
    public void markExecuted(UUID id, UUID transactionId) {
        int updated = jdbc.sql("""
                        UPDATE proposal SET status = 'EXECUTED', executed_transaction_id = :tx
                         WHERE id = :id AND status = 'EXECUTING'
                        """)
                .param("tx", transactionId)
                .param("id", id)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("proposal " + id + " was not EXECUTING when its posting completed");
        }
    }

    private Proposal map(ResultSet rs, int row) throws SQLException {
        return new Proposal(
                rs.getObject("id", UUID.class),
                ProposalKind.valueOf(rs.getString("kind")),
                ProposalStatus.valueOf(rs.getString("status")),
                rs.getString("currency").trim(),
                json.readValue(rs.getString("postings"), POSTINGS),
                rs.getString("external_reference"),
                rs.getObject("target_transaction_id", UUID.class),
                rs.getString("divergence_reference"),
                rs.getString("reason"),
                rs.getString("proposed_by"),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                rs.getString("confirmation_code"),
                rs.getString("decided_by"),
                instant(rs, "decided_at"),
                rs.getString("decision_note"),
                rs.getObject("executed_transaction_id", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
