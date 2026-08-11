package com.auditlog.service.service;

import com.auditlog.service.api.dto.RetentionRunRequest;
import com.auditlog.service.api.dto.RetentionRunResponse;
import com.auditlog.service.api.dto.AuditEventCreateRequest;
import com.auditlog.service.config.RetentionProperties;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.domain.RetentionRun;
import com.auditlog.service.repository.AuditEventRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class RetentionService {
    private static final String STATUS_STARTED = "STARTED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String RETENTION_EVENT_TYPE = "RETENTION_RUN_EXECUTED";

    private final AuditEventRepository auditEventRepository;
    private final AuditEventService auditEventService;
    private final RetentionProperties retentionProperties;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    public RetentionService(
        AuditEventRepository auditEventRepository,
        AuditEventService auditEventService,
        RetentionProperties retentionProperties,
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager,
        Clock clock
    ) {
        this.auditEventRepository = auditEventRepository;
        this.auditEventService = auditEventService;
        this.retentionProperties = retentionProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    public RetentionRunResponse runRetention(RetentionRunRequest request) {
        validateRequest(request);

        boolean dryRun = request.getDryRun() != null ? request.getDryRun() : retentionProperties.isDefaultDryRun();
        int windowDays = request.getRetentionWindowDays() == null ? retentionProperties.getWindowDays() : request.getRetentionWindowDays();
        String requestedBy = request.getRequestedBy() == null ? "" : request.getRequestedBy().trim();
        String approvedBy = request.getApprovedBy() == null ? "" : request.getApprovedBy().trim();
        String approvalRef = request.getApprovalRef() == null ? "" : request.getApprovalRef().trim();
        String reason = request.getReason() == null ? null : request.getReason().trim();

        String runId = UUID.randomUUID().toString();
        String startedAt = formatUtc(clock.instant());
        String cutoffTimestamp = formatUtc(clock.instant().minus(Duration.ofDays(windowDays)));

        insertRetentionRun(runId, requestedBy, approvedBy, approvalRef, windowDays, dryRun, cutoffTimestamp, startedAt, reason);

        try {
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            return transactionTemplate.execute(status -> {
                long candidateCount = auditEventRepository.countUnarchivedOlderThan(cutoffTimestamp);
                int archivedCount = 0;

                if (!dryRun) {
                    archivedCount = auditEventRepository.archiveEligibleEvents(
                        cutoffTimestamp,
                        runId,
                        requestedBy,
                        reason,
                        formatUtc(clock.instant())
                    );

                    if (archivedCount > 0) {
                        createRetentionCertificateEvent(runId, reason);
                    }
                }

                String completedAt = formatUtc(clock.instant());
                updateRetentionRun(
                    runId,
                    candidateCount,
                    archivedCount,
                    STATUS_COMPLETED,
                    completedAt
                );

                return new RetentionRunResponse(
                    runId,
                    STATUS_COMPLETED,
                    dryRun,
                    windowDays,
                    cutoffTimestamp,
                    (int) candidateCount,
                    archivedCount,
                    startedAt,
                    completedAt
                );
            });
        } catch (RuntimeException ex) {
            updateRetentionRunFailure(runId, ex.getMessage());
            throw ex;
        }
    }

    private void validateRequest(RetentionRunRequest request) {
        if (request == null) {
            throw new RetentionRequestValidationException("request body is required");
        }
        if (request.getRetentionWindowDays() == null) {
            request.setRetentionWindowDays(retentionProperties.getWindowDays());
        }
        if (request.getRetentionWindowDays() == null || request.getRetentionWindowDays() <= 0) {
            throw new RetentionRequestValidationException("retentionWindowDays must be positive");
        }
        if (request.getRequestedBy() == null || request.getRequestedBy().trim().isEmpty()) {
            throw new RetentionRequestValidationException("requestedBy is required");
        }
        if (request.getDryRun() == null) {
            request.setDryRun(retentionProperties.isDefaultDryRun());
        }
        boolean apply = !Boolean.TRUE.equals(request.getDryRun());
        if (apply && retentionProperties.isRequireApproval()) {
            if (request.getApprovalRef() == null || request.getApprovalRef().trim().isEmpty()) {
                throw new RetentionRequestValidationException("approvalRef is required for applied runs");
            }
            if (request.getApprovedBy() == null || request.getApprovedBy().trim().isEmpty()) {
                throw new RetentionRequestValidationException("approvedBy is required for applied runs");
            }
        }
    }

    private void insertRetentionRun(
        String runId,
        String requestedBy,
        String approvedBy,
        String approvalRef,
        int windowDays,
        boolean dryRun,
        String cutoffTimestamp,
        String startedAt,
        String reason
    ) {
        jdbcTemplate.update(
            "INSERT INTO retention_runs (id, requested_by, approved_by, approval_ref, window_days, dry_run, cutoff_timestamp, started_at, completed_at, candidate_count, archived_count, status, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 0, 0, ?, ?)",
            runId,
            requestedBy,
            approvedBy,
            approvalRef,
            windowDays,
            dryRun,
            cutoffTimestamp,
            startedAt,
            STATUS_STARTED,
            reason
        );
    }

    private void updateRetentionRun(
        String runId,
        long candidateCount,
        int archivedCount,
        String status,
        String completedAt
    ) {
        jdbcTemplate.update(
            "UPDATE retention_runs SET candidate_count = ?, archived_count = ?, status = ?, completed_at = ? WHERE id = ?",
            (int) candidateCount,
            archivedCount,
            status,
            completedAt,
            runId
        );
    }

    private void updateRetentionRunFailure(String runId, String errorMessage) {
        try {
            TransactionTemplate failureTemplate = new TransactionTemplate(transactionManager);
            failureTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            failureTemplate.executeWithoutResult(status -> jdbcTemplate.update(
                "UPDATE retention_runs SET status = ?, reason = ? WHERE id = ?",
                STATUS_FAILED,
                errorMessage,
                runId
            ));
        } catch (Exception ignored) {
            // Best-effort failure recording after rollback.
        }
    }

    private void createRetentionCertificateEvent(String runId, String reason) {
        AuditEventCreateRequest request = new AuditEventCreateRequest();
        request.setEventType(RETENTION_EVENT_TYPE);
        request.setActorId("retention-service");
        request.setResourceType("RETENTION_RUN");
        request.setResourceId(runId);
        request.setPayload(new tools.jackson.databind.ObjectMapper().createObjectNode()
            .put("runId", runId)
            .put("reason", reason != null ? reason : "")
            .put("eventType", RETENTION_EVENT_TYPE)
        );
        request.setTimestamp(formatUtc(clock.instant()));
        AuditEvent createdEvent = auditEventService.createEvent(request);
        jdbcTemplate.update(
            "UPDATE audit_events SET retention_run_id = ? WHERE id = ?",
            runId,
            createdEvent.getId()
        );
    }

    public RetentionRun findById(String runId) {
        return jdbcTemplate.query(
            "SELECT id, requested_by, approved_by, approval_ref, window_days, dry_run, cutoff_timestamp, started_at, completed_at, candidate_count, archived_count, status, reason FROM retention_runs WHERE id = ?",
            rs -> rs.next() ? new RetentionRun(
                rs.getString("id"),
                rs.getString("requested_by"),
                rs.getString("approved_by"),
                rs.getString("approval_ref"),
                rs.getInt("window_days"),
                rs.getBoolean("dry_run"),
                rs.getString("cutoff_timestamp"),
                rs.getString("started_at"),
                rs.getString("completed_at"),
                rs.getInt("candidate_count"),
                rs.getInt("archived_count"),
                rs.getString("status"),
                rs.getString("reason")
            ) : null,
            runId
        );
    }

    private String formatUtc(Instant instant) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC)).replace("+00:00", "Z");
    }
}
