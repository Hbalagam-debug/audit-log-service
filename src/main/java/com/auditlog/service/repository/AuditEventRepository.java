package com.auditlog.service.repository;

import com.auditlog.service.domain.AuditEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AuditEventRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public AuditEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<AuditEvent> eventRowMapper = (rs, rowNum) -> {
        try {
            JsonNode payload = objectMapper.readTree(rs.getString("payload_json"));
            return new AuditEvent(
                rs.getString("id"),
                rs.getLong("chain_position"),
                rs.getString("event_type"),
                rs.getString("actor_id"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                payload,
                rs.getString("event_timestamp"),
                rs.getString("ingested_at"),
                rs.getString("content_hash"),
                rs.getString("previous_hash"),
                rs.getString("chain_hash"),
                rs.getString("hash_version")
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to map row to AuditEvent", e);
        }
    };

    public void insert(AuditEvent event) {
        String sql = "INSERT INTO audit_events " +
            "(id, chain_position, event_type, actor_id, resource_type, resource_id, " +
            "payload_json, event_timestamp, ingested_at, content_hash, previous_hash, chain_hash, hash_version) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        jdbcTemplate.update(sql,
            event.getId(),
            event.getChainPosition(),
            event.getEventType(),
            event.getActorId(),
            event.getResourceType(),
            event.getResourceId(),
            event.getPayload().toString(),
            event.getTimestamp(),
            event.getIngestedAt(),
            event.getContentHash(),
            event.getPreviousHash(),
            event.getChainHash(),
            event.getHashVersion()
        );
    }

    public Optional<AuditEvent> findById(String id) {
        String sql = "SELECT * FROM audit_events WHERE id = ?";
        List<AuditEvent> results = jdbcTemplate.query(sql, new Object[]{id}, eventRowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<Long> findMaxChainPosition() {
        String sql = "SELECT MAX(chain_position) FROM audit_events";
        Long result = jdbcTemplate.queryForObject(sql, Long.class);
        return result == null ? Optional.empty() : Optional.of(result);
    }

    public Optional<AuditEvent> findByChainPosition(long chainPosition) {
        String sql = "SELECT * FROM audit_events WHERE chain_position = ?";
        List<AuditEvent> results = jdbcTemplate.query(sql, new Object[]{chainPosition}, eventRowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<AuditEvent> findWithFilters(
        String actorId,
        String resourceType,
        String resourceId,
        String eventType,
        String fromTimestamp,
        String toTimestamp,
        Long afterChainPosition,
        long limit,
        boolean includeArchived
    ) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_events WHERE 1=1");
        java.util.List<Object> params = new java.util.ArrayList<>();

        if (!includeArchived) {
            sql.append(" AND is_archived = FALSE");
        }

        if (actorId != null) {
            sql.append(" AND actor_id = ?");
            params.add(actorId);
        }
        if (resourceType != null) {
            sql.append(" AND resource_type = ?");
            params.add(resourceType);
        }
        if (resourceId != null) {
            sql.append(" AND resource_id = ?");
            params.add(resourceId);
        }
        if (eventType != null) {
            sql.append(" AND event_type = ?");
            params.add(eventType);
        }
        if (fromTimestamp != null) {
            sql.append(" AND event_timestamp >= ?");
            params.add(fromTimestamp);
        }
        if (toTimestamp != null) {
            sql.append(" AND event_timestamp < ?");
            params.add(toTimestamp);
        }
        if (afterChainPosition != null) {
            sql.append(" AND chain_position > ?");
            params.add(afterChainPosition);
        }

        sql.append(" ORDER BY chain_position ASC LIMIT ?");
        params.add(limit);

        return jdbcTemplate.query(sql.toString(), params.toArray(), eventRowMapper);
    }

    public long countWithFilters(
        String actorId,
        String resourceType,
        String resourceId,
        String eventType,
        String fromTimestamp,
        String toTimestamp,
        boolean includeArchived
    ) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM audit_events WHERE 1=1");
        java.util.List<Object> params = new java.util.ArrayList<>();

        if (!includeArchived) {
            sql.append(" AND is_archived = FALSE");
        }

        if (actorId != null) {
            sql.append(" AND actor_id = ?");
            params.add(actorId);
        }
        if (resourceType != null) {
            sql.append(" AND resource_type = ?");
            params.add(resourceType);
        }
        if (resourceId != null) {
            sql.append(" AND resource_id = ?");
            params.add(resourceId);
        }
        if (eventType != null) {
            sql.append(" AND event_type = ?");
            params.add(eventType);
        }
        if (fromTimestamp != null) {
            sql.append(" AND event_timestamp >= ?");
            params.add(fromTimestamp);
        }
        if (toTimestamp != null) {
            sql.append(" AND event_timestamp < ?");
            params.add(toTimestamp);
        }

        Long count = jdbcTemplate.queryForObject(sql.toString(), params.toArray(), Long.class);
        return count == null ? 0 : count;
    }

    public long countUnarchivedOlderThan(String cutoffTimestamp) {
        String sql = "SELECT COUNT(*) FROM audit_events WHERE is_archived = FALSE AND event_type <> ? AND ingested_at < ?";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, "RETENTION_RUN_EXECUTED", cutoffTimestamp);
        return count == null ? 0 : count;
    }

    public int archiveEligibleEvents(String cutoffTimestamp, String retentionRunId, String archivedBy, String reason, String archivedAt) {
        String sql = "UPDATE audit_events SET is_archived = TRUE, archived_at = ?, archived_by = ?, archive_reason = ?, retention_run_id = ? WHERE is_archived = FALSE AND event_type <> ? AND ingested_at < ?";
        return jdbcTemplate.update(sql, archivedAt, archivedBy, reason, retentionRunId, "RETENTION_RUN_EXECUTED", cutoffTimestamp);
    }

    public List<AuditEvent> findAllOrderedByPosition() {
        String sql = "SELECT * FROM audit_events ORDER BY chain_position ASC";
        return jdbcTemplate.query(sql, eventRowMapper);
    }
}
