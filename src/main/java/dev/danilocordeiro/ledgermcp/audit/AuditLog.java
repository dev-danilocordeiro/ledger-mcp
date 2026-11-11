package dev.danilocordeiro.ledgermcp.audit;

import dev.danilocordeiro.ledgermcp.security.Caller;
import java.time.Instant;
import java.util.UUID;

/**
 * Two append-only rows per tool call, sharing a call id. STARTED is written before the
 * tool runs, so a call whose audit cannot be written never executes. COMPLETED carries
 * the outcome. Both rows repeat who, which tool and the arguments, so either row alone
 * answers "who asked for what".
 */
public interface AuditLog {

    void started(UUID callId, Instant at, Caller caller, String tool, String argumentsJson);

    void completed(UUID callId, Instant at, Caller caller, String tool, String argumentsJson,
                   AuditOutcome outcome, String resultJson, String error, long durationMs);
}
