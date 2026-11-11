CREATE TABLE proposal (
    id                      UUID PRIMARY KEY,
    kind                    VARCHAR(16)  NOT NULL CHECK (kind IN ('REVERSAL', 'ADJUSTMENT')),
    status                  VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'EXECUTING', 'EXECUTED', 'FAILED', 'REJECTED', 'EXPIRED')),
    currency                CHAR(3)      NOT NULL,
    postings                JSONB        NOT NULL,
    external_reference      VARCHAR(256),
    target_transaction_id   UUID,
    divergence_reference    VARCHAR(128),
    reason                  TEXT         NOT NULL,
    proposed_by             VARCHAR(256) NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL,
    expires_at              TIMESTAMPTZ  NOT NULL,
    confirmation_code       VARCHAR(16)  NOT NULL,
    decided_by              VARCHAR(256),
    decided_at              TIMESTAMPTZ,
    decision_note           TEXT,
    executed_transaction_id UUID,
    CHECK (status <> 'EXECUTED' OR executed_transaction_id IS NOT NULL),
    CHECK (status = 'PENDING' OR status = 'EXPIRED' OR decided_by IS NOT NULL)
);

-- Two concurrent "propose_reversal" calls for the same transaction cannot both stay live.
CREATE UNIQUE INDEX proposal_one_live_reversal
    ON proposal (target_transaction_id)
    WHERE kind = 'REVERSAL' AND status IN ('PENDING', 'EXECUTING', 'EXECUTED');

CREATE TABLE audit_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    call_id     UUID         NOT NULL,
    phase       VARCHAR(16)  NOT NULL CHECK (phase IN ('STARTED', 'COMPLETED')),
    occurred_at TIMESTAMPTZ  NOT NULL,
    principal   VARCHAR(256) NOT NULL,
    client_id   VARCHAR(256),
    transport   VARCHAR(16)  NOT NULL,
    tool        VARCHAR(64)  NOT NULL,
    arguments   JSONB        NOT NULL,
    outcome     VARCHAR(16)  CHECK (outcome IN ('OK', 'ERROR', 'DENIED')),
    result      JSONB,
    error       TEXT,
    duration_ms BIGINT,
    CHECK ((phase = 'STARTED') = (outcome IS NULL))
);

CREATE INDEX audit_event_call_id ON audit_event (call_id);
CREATE INDEX audit_event_principal_time ON audit_event (principal, occurred_at);
CREATE INDEX audit_event_tool_time ON audit_event (tool, occurred_at);

-- Append-only, enforced by the database and not just by the application.
CREATE FUNCTION audit_event_reject_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_reject_change();

CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_reject_change();
