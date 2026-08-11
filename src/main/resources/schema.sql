-- Schema metadata for scaffold validation
CREATE TABLE IF NOT EXISTS schema_metadata (
    schema_key TEXT PRIMARY KEY,
    schema_value TEXT NOT NULL,
    created_at TEXT NOT NULL
);

INSERT INTO schema_metadata (schema_key, schema_value, created_at)
SELECT 'version', 'v1-scaffold', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM schema_metadata WHERE schema_key = 'version');
