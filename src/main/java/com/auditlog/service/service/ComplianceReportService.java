package com.auditlog.service.service;

import com.auditlog.service.api.dto.AuditEventResponse;
import com.auditlog.service.api.dto.ComplianceReportRequest;
import com.auditlog.service.api.dto.ComplianceReportResponse;
import com.auditlog.service.config.ComplianceReportProperties;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Service
public class ComplianceReportService {
    private static final String CURSOR_FIELD = "chainPosition";
    private static final String COMPLIANCE_REPORT_GENERATED = "COMPLIANCE_REPORT_GENERATED";
    
    // Exact access-event types as per Scenario C specification
    private static final List<String> ACCESS_EVENT_TYPES = List.of(
        "CLIENT_ACCOUNT_DATA_VIEWED",
        "CLIENT_ACCOUNT_DATA_SEARCHED",
        "CLIENT_ACCOUNT_DATA_EXPORTED",
        "CLIENT_ACCOUNT_DATA_UPDATED",
        "CLIENT_ACCOUNT_ACCESS_DENIED"
    );

    private final AuditEventRepository repository;
    private final RedactionViewService redactionViewService;
    private final ComplianceReportProperties properties;

    public ComplianceReportService(
        AuditEventRepository repository,
        RedactionViewService redactionViewService,
        ComplianceReportProperties properties
    ) {
        this.repository = repository;
        this.redactionViewService = redactionViewService;
        this.properties = properties;
    }

    public ComplianceReportResponse queryAccessReport(ComplianceReportRequest request) {
        // 1. Validate exactly one selector
        int selectorCount = (request.accountId() != null ? 1 : 0) + (request.resourceId() != null ? 1 : 0);
        if (selectorCount != 1) {
            throw new IllegalArgumentException("Exactly one of accountId or resourceId must be specified");
        }

        // 2. Validate and parse timestamps
        String normalizedFrom = validateAndNormalizeTimestamp(request.from(), "from");
        String normalizedTo = validateAndNormalizeTimestamp(request.to(), "to");

        if (normalizedFrom.compareTo(normalizedTo) >= 0) {
            throw new IllegalArgumentException("'from' must be before 'to'");
        }

        // 3. Validate UTC window
        long windowMillis = calculateWindowMillis(normalizedFrom, normalizedTo);
        if (windowMillis > properties.getMaxUtcWindowMillis()) {
            throw new IllegalArgumentException(
                String.format("UTC window %d ms exceeds maximum allowed %d ms",
                    windowMillis, properties.getMaxUtcWindowMillis())
            );
        }

        // 4. Determine selector type for response
        String selectorType = request.accountId() != null ? "accountId" : "resourceId";
        String selectorValue = request.accountId() != null ? request.accountId() : request.resourceId();

        // 5. Validate and normalize page size
        int pageSize = request.limit() != null ? request.limit() : properties.getDefaultPageSize();
        if (pageSize < 1 || pageSize > properties.getMaxPageSize()) {
            pageSize = Math.min(Math.max(pageSize, 1), properties.getMaxPageSize());
        }

        // 6. Decode cursor if provided
        Long afterChainPosition = null;
        if (request.cursor() != null && !request.cursor().isEmpty()) {
            afterChainPosition = decodeCursor(request.cursor());
        }

        // 7. Query audit_events with multiple event type filters
        List<AuditEvent> allMatches = repository.findWithFilters(
            request.actorId(),
            "CLIENT_ACCOUNT",  // Always filter by CLIENT_ACCOUNT resource type
            selectorValue,
            null,  // eventType handled below
            normalizedFrom,
            normalizedTo,
            afterChainPosition,
            pageSize + 1,  // Fetch one extra to detect hasMore
            request.includeArchived()
        );

        // 8. Filter to only include allowed access-event types and exclude COMPLIANCE_REPORT_GENERATED
        List<AuditEvent> filtered = allMatches.stream()
            .filter(e -> ACCESS_EVENT_TYPES.contains(e.getEventType()))
            .filter(e -> !COMPLIANCE_REPORT_GENERATED.equals(e.getEventType()))
            .toList();

        // 9. Further filter by action and outcome if provided
        List<AuditEvent> results = filtered.stream()
            .filter(e -> request.action() == null || matchesAction(e, request.action()))
            .filter(e -> request.outcome() == null || matchesOutcome(e, request.outcome()))
            .toList();

        // 10. Determine pagination
        boolean hasMore = results.size() > pageSize;
        if (hasMore) {
            results = results.subList(0, pageSize);
        }

        String nextCursor = null;
        if (hasMore && !results.isEmpty()) {
            long lastPosition = results.get(results.size() - 1).getChainPosition();
            nextCursor = encodeCursor(lastPosition);
        }

        // 11. Apply masking and redaction via RedactionViewService
        List<AuditEventResponse> items = redactionViewService.maskEvents(results);

        // 12. Build selection metadata
        ComplianceReportResponse.ComplianceReportSelection selection = new ComplianceReportResponse.ComplianceReportSelection(
            selectorType,
            selectorValue,
            normalizedFrom,
            normalizedTo,
            request.actorId(),
            request.action(),
            request.outcome(),
            request.includeArchived()
        );

        return new ComplianceReportResponse(
            selection,
            results.size(),
            items,
            nextCursor,
            hasMore
        );
    }

    private String validateAndNormalizeTimestamp(String timestamp, String fieldName) {
        if (timestamp == null || timestamp.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        try {
            return TimestampNormalizer.parseUtcString(timestamp).toString().replace(" ", "T").replace("+00:00", "Z");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + fieldName + " timestamp format: " + e.getMessage());
        }
    }

    private long calculateWindowMillis(String from, String to) {
        try {
            long fromMillis = TimestampNormalizer.parseUtcString(from).toEpochMilli();
            long toMillis = TimestampNormalizer.parseUtcString(to).toEpochMilli();
            return toMillis - fromMillis;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to calculate time window: " + e.getMessage());
        }
    }

    private boolean matchesAction(AuditEvent event, String actionFilter) {
        if (event.getPayload() == null || event.getPayload().isNull()) {
            return false;
        }
        String action = event.getPayload().path("action").asText(null);
        return actionFilter.equalsIgnoreCase(action);
    }

    private boolean matchesOutcome(AuditEvent event, String outcomeFilter) {
        if (event.getPayload() == null || event.getPayload().isNull()) {
            return false;
        }
        String outcome = event.getPayload().path("outcome").asText(null);
        return outcomeFilter.equalsIgnoreCase(outcome);
    }

    String encodeCursor(long chainPosition) {
        try {
            String json = String.format("{\"%s\":%d}", CURSOR_FIELD, chainPosition);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new InvalidCursorException("Failed to encode cursor: " + e.getMessage());
        }
    }

    Long decodeCursor(String cursor) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String json = new String(decoded, StandardCharsets.UTF_8);
            int startIdx = json.indexOf(":");
            int endIdx = json.lastIndexOf("}");
            if (startIdx < 0 || endIdx < 0) {
                throw new InvalidCursorException("Invalid cursor format");
            }
            String posStr = json.substring(startIdx + 1, endIdx).trim();
            return Long.parseLong(posStr);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Failed to decode cursor: " + e.getMessage());
        }
    }
}
