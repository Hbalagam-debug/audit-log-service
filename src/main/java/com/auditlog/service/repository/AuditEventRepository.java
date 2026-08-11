package com.auditlog.service.repository;

import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.domain.AuditEventEncryptionKey;
import com.auditlog.service.domain.RedactionOverlay;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
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
            String payloadJson = rs.getString("payload_json");
            JsonNode payload;
            if (payloadJson == null || payloadJson.isBlank()) {
                payload = objectMapper.createObjectNode();
            } else {
                payload = objectMapper.readTree(payloadJson);
                if (payload == null || payload.isNull()) {
                    payload = objectMapper.createObjectNode();
                }
            }
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

    private final RowMapper<RedactionOverlay> redactionOverlayRowMapper = (rs, rowNum) -> new RedactionOverlay(
        rs.getString("id"),
        rs.getString("event_id"),
        rs.getLong("event_chain_position"),
        rs.getString("json_pointer"),
        rs.getString("redaction_mode"),
        rs.getString("reason_code"),
        rs.getString("approval_ref"),
        rs.getString("requested_by"),
        rs.getString("approved_by"),
        rs.getString("applied_at"),
        rs.getString("certificate_event_id"),
        rs.getBoolean("active")
    );

    private final RowMapper<AuditEventEncryptionKey> encryptionKeyRowMapper = (rs, rowNum) -> {
        try {
            List<String> encryptedPointers = objectMapper.readerForListOf(String.class)
                .readValue(rs.getString("encrypted_pointers_json"));
            return new AuditEventEncryptionKey(
                rs.getString("key_ref"),
                rs.getString("event_id"),
                encryptedPointers,
                rs.getString("wrap_algorithm"),
                rs.getString("wrapped_dek"),
                rs.getString("wrap_nonce"),
                rs.getString("status"),
                rs.getString("created_at"),
                rs.getString("destroyed_at"),
                rs.getString("destroyed_by"),
                rs.getString("destruction_reason"),
                rs.getString("approval_ref"),
                rs.getString("destruction_certificate_event_id"),
                rs.getString("encryption_version")
            );
        } catch (Exception ex) {
            throw new RuntimeException("Failed to map row to AuditEventEncryptionKey", ex);
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

    public List<RedactionOverlay> findActiveOverlaysForEventId(String eventId) {
        String sql = "SELECT * FROM audit_event_redactions WHERE event_id = ? AND active = TRUE ORDER BY json_pointer ASC";
        return jdbcTemplate.query(sql, redactionOverlayRowMapper, eventId);
    }

    public List<RedactionOverlay> findActiveOverlaysForEventIds(List<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(eventIds.size(), "?"));
        String sql = "SELECT * FROM audit_event_redactions WHERE active = TRUE AND event_id IN (" + placeholders + ") ORDER BY event_id, json_pointer ASC";
        return jdbcTemplate.query(sql, redactionOverlayRowMapper, eventIds.toArray());
    }

    public void insertRedactionOverlay(
        String id,
        String eventId,
        long eventChainPosition,
        String jsonPointer,
        String redactionMode,
        String reasonCode,
        String approvalRef,
        String requestedBy,
        String approvedBy,
        String appliedAt,
        String certificateEventId,
        boolean active
    ) {
        String sql = "INSERT INTO audit_event_redactions (id, event_id, event_chain_position, json_pointer, redaction_mode, reason_code, approval_ref, requested_by, approved_by, applied_at, certificate_event_id, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(
            sql,
            id,
            eventId,
            eventChainPosition,
            jsonPointer,
            redactionMode,
            reasonCode,
            approvalRef,
            requestedBy,
            approvedBy,
            appliedAt,
            certificateEventId,
            active
        );
    }

    public void linkRedactionOverlaysToCertificate(String eventId, List<String> pointers, String certificateEventId) {
        if (pointers == null || pointers.isEmpty()) {
            return;
        }
        for (String pointer : pointers) {
            jdbcTemplate.update(
                "UPDATE audit_event_redactions SET certificate_event_id = ? WHERE event_id = ? AND json_pointer = ? AND active = TRUE",
                certificateEventId,
                eventId,
                pointer
            );
        }
    }

    public void insertEncryptionKey(AuditEventEncryptionKey encryptionKey) {
        String sql = "INSERT INTO audit_event_encryption_keys " +
            "(key_ref, event_id, encrypted_pointers_json, wrap_algorithm, wrapped_dek, wrap_nonce, status, created_at, destroyed_at, destroyed_by, destruction_reason, approval_ref, destruction_certificate_event_id, encryption_version) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(
            sql,
            encryptionKey.getKeyRef(),
            encryptionKey.getEventId(),
            writePointersJson(encryptionKey.getEncryptedPointers()),
            encryptionKey.getWrapAlgorithm(),
            encryptionKey.getWrappedDek(),
            encryptionKey.getWrapNonce(),
            encryptionKey.getStatus(),
            encryptionKey.getCreatedAt(),
            encryptionKey.getDestroyedAt(),
            encryptionKey.getDestroyedBy(),
            encryptionKey.getDestructionReason(),
            encryptionKey.getApprovalRef(),
            encryptionKey.getDestructionCertificateEventId(),
            encryptionKey.getEncryptionVersion()
        );
    }

    public List<AuditEventEncryptionKey> findEncryptionKeysForEventId(String eventId) {
        String sql = "SELECT * FROM audit_event_encryption_keys WHERE event_id = ? ORDER BY key_ref ASC";
        return jdbcTemplate.query(sql, encryptionKeyRowMapper, eventId);
    }

    public List<AuditEventEncryptionKey> findEncryptionKeysForEventIds(List<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return List.of();
        }
        String placeholders = eventIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT * FROM audit_event_encryption_keys WHERE event_id IN (" + placeholders + ") ORDER BY event_id, key_ref ASC";
        return jdbcTemplate.query(sql, encryptionKeyRowMapper, eventIds.toArray());
    }

    public void destroyEncryptionKey(
        String keyRef,
        String destroyedAt,
        String destroyedBy,
        String destructionReason,
        String approvalRef,
        String destructionCertificateEventId
    ) {
        jdbcTemplate.update(
            "UPDATE audit_event_encryption_keys SET status = 'DESTROYED', wrapped_dek = NULL, wrap_nonce = NULL, destroyed_at = ?, destroyed_by = ?, destruction_reason = ?, approval_ref = ?, destruction_certificate_event_id = ? WHERE key_ref = ?",
            destroyedAt,
            destroyedBy,
            destructionReason,
            approvalRef,
            destructionCertificateEventId,
            keyRef
        );
    }

    public List<AuditEventEncryptionKey> findDestroyedEncryptionKeysForEventId(String eventId) {
        String sql = "SELECT * FROM audit_event_encryption_keys WHERE event_id = ? AND status = 'DESTROYED' ORDER BY key_ref ASC";
        return jdbcTemplate.query(sql, encryptionKeyRowMapper, eventId);
    }

    private String writePointersJson(List<String> pointers) {
        try {
            return objectMapper.writeValueAsString(pointers);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialize encryption pointers", ex);
        }
    }
}
