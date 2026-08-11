-- Schema metadata for scaffold validation
CREATE TABLE IF NOT EXISTS schema_metadata (
    schema_key TEXT PRIMARY KEY,
    schema_value TEXT NOT NULL,
    created_at TEXT NOT NULL
);

INSERT INTO schema_metadata (schema_key, schema_value, created_at)
SELECT 'version', 'v1-scaffold', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM schema_metadata WHERE schema_key = 'version');

-- Audit events table - Scenario A core with retention metadata
CREATE TABLE IF NOT EXISTS audit_events (
    id TEXT PRIMARY KEY,
    chain_position INTEGER UNIQUE NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    actor_id VARCHAR(256) NOT NULL,
    resource_type VARCHAR(128) NOT NULL,
    resource_id VARCHAR(512) NOT NULL,
    payload_json TEXT NOT NULL,
    event_timestamp TEXT NOT NULL,
    ingested_at TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    previous_hash TEXT NOT NULL,
    chain_hash TEXT NOT NULL,
    hash_version TEXT NOT NULL,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    archived_at TEXT,
    archived_by TEXT,
    archive_reason TEXT,
    retention_run_id TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS retention_runs (
    id TEXT PRIMARY KEY,
    requested_by TEXT NOT NULL,
    approved_by TEXT,
    approval_ref TEXT,
    window_days INTEGER NOT NULL,
    dry_run BOOLEAN NOT NULL,
    cutoff_timestamp TEXT NOT NULL,
    started_at TEXT NOT NULL,
    completed_at TEXT,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    archived_count INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    reason TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit_event_redactions (
    id TEXT PRIMARY KEY,
    event_id TEXT NOT NULL,
    event_chain_position INTEGER NOT NULL,
    json_pointer TEXT NOT NULL,
    redaction_mode TEXT NOT NULL,
    reason_code TEXT NOT NULL,
    approval_ref TEXT NOT NULL,
    requested_by TEXT NOT NULL,
    approved_by TEXT NOT NULL,
    applied_at TEXT NOT NULL,
    certificate_event_id TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    FOREIGN KEY (event_id) REFERENCES audit_events(id)
);

CREATE TABLE IF NOT EXISTS audit_event_encryption_keys (
    key_ref TEXT PRIMARY KEY,
    event_id TEXT NOT NULL,
    encrypted_pointers_json TEXT NOT NULL,
    wrap_algorithm TEXT NOT NULL,
    wrapped_dek TEXT,
    wrap_nonce TEXT,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    destroyed_at TEXT,
    destroyed_by TEXT,
    destruction_reason TEXT,
    approval_ref TEXT,
    destruction_certificate_event_id TEXT,
    encryption_version TEXT NOT NULL,
    FOREIGN KEY (event_id) REFERENCES audit_events(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_chain_position ON audit_events(chain_position);
CREATE INDEX IF NOT EXISTS idx_actor_id ON audit_events(actor_id);
CREATE INDEX IF NOT EXISTS idx_resource_type ON audit_events(resource_type);
CREATE INDEX IF NOT EXISTS idx_resource_id ON audit_events(resource_id);
CREATE INDEX IF NOT EXISTS idx_event_type ON audit_events(event_type);
CREATE INDEX IF NOT EXISTS idx_event_timestamp ON audit_events(event_timestamp);
CREATE INDEX IF NOT EXISTS idx_retention_runs_status ON retention_runs(status);
CREATE INDEX IF NOT EXISTS idx_retention_runs_started_at ON retention_runs(started_at);
CREATE INDEX IF NOT EXISTS idx_redaction_event_id ON audit_event_redactions(event_id);
CREATE INDEX IF NOT EXISTS idx_redaction_event_pointer ON audit_event_redactions(event_id, json_pointer);
CREATE INDEX IF NOT EXISTS idx_encryption_key_event_id ON audit_event_encryption_keys(event_id);
