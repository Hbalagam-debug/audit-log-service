-- Schema metadata for scaffold validation
CREATE TABLE IF NOT EXISTS schema_metadata (
    schema_key TEXT PRIMARY KEY,
    schema_value TEXT NOT NULL,
    created_at TEXT NOT NULL
);

INSERT INTO schema_metadata (schema_key, schema_value, created_at)
SELECT 'version', 'v1-scaffold', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM schema_metadata WHERE schema_key = 'version');

-- Audit events table - Scenario A core
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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_chain_position ON audit_events(chain_position);
CREATE INDEX IF NOT EXISTS idx_actor_id ON audit_events(actor_id);
CREATE INDEX IF NOT EXISTS idx_resource_type ON audit_events(resource_type);
CREATE INDEX IF NOT EXISTS idx_resource_id ON audit_events(resource_id);
CREATE INDEX IF NOT EXISTS idx_event_type ON audit_events(event_type);
CREATE INDEX IF NOT EXISTS idx_event_timestamp ON audit_events(event_timestamp);
