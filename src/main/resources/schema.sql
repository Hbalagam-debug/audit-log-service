-- Schema metadata for scaffold validation
CREATE TABLE IF NOT EXISTS schema_metadata (
    schema_key TEXT PRIMARY KEY,
    schema_value TEXT NOT NULL,
    created_at TEXT NOT NULL
);

INSERT OR IGNORE INTO schema_metadata (schema_key, schema_value, created_at) VALUES ('version', 'v1-scaffold', datetime('now'));
