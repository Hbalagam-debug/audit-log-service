package com.auditlog.service.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DatabaseSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        ensureAuditEventsColumns();
        ensureRetentionRunsTable();
        ensureAuditEventRedactionsTable();
        ensureAuditEventIndexes();
    }

    private void ensureAuditEventsColumns() {
        if (!tableExists("audit_events")) {
            return;
        }

        addColumnIfMissing("audit_events", "is_archived", "BOOLEAN NOT NULL DEFAULT FALSE");
        addColumnIfMissing("audit_events", "archived_at", "TEXT");
        addColumnIfMissing("audit_events", "archived_by", "TEXT");
        addColumnIfMissing("audit_events", "archive_reason", "TEXT");
        addColumnIfMissing("audit_events", "retention_run_id", "TEXT");
    }

    private void ensureRetentionRunsTable() {
        jdbcTemplate.execute("""
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
            )
            """);
    }

    private void ensureAuditEventRedactionsTable() {
        jdbcTemplate.execute("""
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
            )
            """);
    }

    private void ensureAuditEventIndexes() {
        createIndexIfMissing("idx_is_archived", "CREATE INDEX IF NOT EXISTS idx_is_archived ON audit_events(is_archived)");
        createIndexIfMissing("idx_archived_at", "CREATE INDEX IF NOT EXISTS idx_archived_at ON audit_events(archived_at)");
        createIndexIfMissing("idx_retention_run_id", "CREATE INDEX IF NOT EXISTS idx_retention_run_id ON audit_events(retention_run_id)");
        createIndexIfMissing("idx_retention_run_status", "CREATE INDEX IF NOT EXISTS idx_retention_run_status ON retention_runs(status)");
        createIndexIfMissing("idx_retention_run_started_at", "CREATE INDEX IF NOT EXISTS idx_retention_run_started_at ON retention_runs(started_at)");
        createIndexIfMissing("idx_redaction_event_id", "CREATE INDEX IF NOT EXISTS idx_redaction_event_id ON audit_event_redactions(event_id)");
        createIndexIfMissing("idx_redaction_event_pointer", "CREATE INDEX IF NOT EXISTS idx_redaction_event_pointer ON audit_event_redactions(event_id, json_pointer)");
    }

    private boolean tableExists(String tableName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name=?", tableName);
        return !rows.isEmpty();
    }

    private boolean hasColumn(String tableName, String columnName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")");
        return rows.stream().anyMatch(row -> columnName.equals(row.get("name")));
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        if (hasColumn(tableName, columnName)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
    }

    private void createIndexIfMissing(String indexName, String createStatement) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT name FROM sqlite_master WHERE type='index' AND name=?", indexName);
        if (rows.isEmpty()) {
            jdbcTemplate.execute(createStatement);
        }
    }
}
