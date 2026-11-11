package dev.danilocordeiro.ledgermcp.audit;

import dev.danilocordeiro.ledgermcp.security.Caller;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcAuditLog implements AuditLog {

    private final JdbcClient jdbc;
    private final String jsonCast;

    /** @param postgres whether JSON columns need an explicit jsonb cast */
    public JdbcAuditLog(JdbcClient jdbc, boolean postgres) {
        this.jdbc = jdbc;
        this.jsonCast = postgres ? "::jsonb" : "";
    }

    @Override
    public void started(UUID callId, Instant at, Caller caller, String tool, String argumentsJson) {
        jdbc.sql("""
                        INSERT INTO audit_event (call_id, phase, occurred_at, principal, client_id, transport, tool, arguments)
                        VALUES (:callId, 'STARTED', :at, :principal, :clientId, :transport, :tool, CAST(:arguments AS VARCHAR)%s)
                        """.formatted(jsonCast))
                .param("callId", callId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("principal", caller.principal())
                .param("clientId", caller.clientId())
                .param("transport", caller.transport())
                .param("tool", tool)
                .param("arguments", argumentsJson)
                .update();
    }

    @Override
    public void completed(UUID callId, Instant at, Caller caller, String tool, String argumentsJson,
                          AuditOutcome outcome, String resultJson, String error, long durationMs) {
        jdbc.sql("""
                        INSERT INTO audit_event (call_id, phase, occurred_at, principal, client_id, transport, tool,
                                                 arguments, outcome, result, error, duration_ms)
                        VALUES (:callId, 'COMPLETED', :at, :principal, :clientId, :transport, :tool,
                                CAST(:arguments AS VARCHAR)%1$s, :outcome, CAST(:result AS VARCHAR)%1$s, :error, :durationMs)
                        """.formatted(jsonCast))
                .param("callId", callId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("principal", caller.principal())
                .param("clientId", caller.clientId())
                .param("transport", caller.transport())
                .param("tool", tool)
                .param("arguments", argumentsJson)
                .param("outcome", outcome.name())
                .param("result", resultJson)
                .param("error", error)
                .param("durationMs", durationMs)
                .update();
    }
}
