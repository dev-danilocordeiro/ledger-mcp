-- Local stdio mode. Same shape as the PostgreSQL schema; JSON is stored as text.
CREATE TABLE proposal (
    id                      UUID PRIMARY KEY,
    kind                    VARCHAR(16)  NOT NULL CHECK (kind IN ('REVERSAL', 'ADJUSTMENT')),
    status                  VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'EXECUTING', 'EXECUTED', 'FAILED', 'REJECTED', 'EXPIRED')),
    currency                CHAR(3)      NOT NULL,
    postings                CHARACTER VARYING NOT NULL,
    external_reference      VARCHAR(256),
    target_transaction_id   UUID,
    divergence_reference    VARCHAR(128),
    reason                  CHARACTER VARYING NOT NULL,
    proposed_by             VARCHAR(256) NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmation_code       VARCHAR(16)  NOT NULL,
    decided_by              VARCHAR(256),
    decided_at              TIMESTAMP WITH TIME ZONE,
    decision_note           CHARACTER VARYING,
    executed_transaction_id UUID
);

CREATE TABLE audit_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    call_id     UUID         NOT NULL,
    phase       VARCHAR(16)  NOT NULL CHECK (phase IN ('STARTED', 'COMPLETED')),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    principal   VARCHAR(256) NOT NULL,
    client_id   VARCHAR(256),
    transport   VARCHAR(16)  NOT NULL,
    tool        VARCHAR(64)  NOT NULL,
    arguments   CHARACTER VARYING NOT NULL,
    outcome     VARCHAR(16)  CHECK (outcome IN ('OK', 'ERROR', 'DENIED')),
    result      CHARACTER VARYING,
    error       CHARACTER VARYING,
    duration_ms BIGINT
);

CREATE INDEX audit_event_call_id ON audit_event (call_id);

CREATE TRIGGER audit_event_no_update BEFORE UPDATE ON audit_event
    FOR EACH ROW CALL 'dev.danilocordeiro.ledgermcp.audit.H2AppendOnlyTrigger';
CREATE TRIGGER audit_event_no_delete BEFORE DELETE ON audit_event
    FOR EACH ROW CALL 'dev.danilocordeiro.ledgermcp.audit.H2AppendOnlyTrigger';
