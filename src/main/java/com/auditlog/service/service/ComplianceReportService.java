package com.auditlog.service.service;

import com.auditlog.service.api.dto.AuditEventCreateRequest;
import com.auditlog.service.api.dto.AuditEventResponse;
import com.auditlog.service.api.dto.ComplianceBundleRequest;
import com.auditlog.service.api.dto.ComplianceReportRequest;
import com.auditlog.service.api.dto.ComplianceReportResponse;
import com.auditlog.service.config.ComplianceReportProperties;
import com.auditlog.service.config.ExportProperties;
import com.auditlog.service.config.ExportSignatureSupport;
import com.auditlog.service.config.SigningKeyProvider;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.repository.AuditEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class ComplianceReportService {
    private static final String CURSOR_FIELD = "chainPosition";
    static final String COMPLIANCE_REPORT_GENERATED = "COMPLIANCE_REPORT_GENERATED";
    private static final String REDACTED_RECORD_LIMITATION =
        "Masked fields are rendered for export. The stored contentHash covers the original or encrypted stored payload, not the masked presentation value.";
    private static final String INTERNAL_ENCRYPTION_METADATA_LIMITATION =
        "Internal encryption key-destruction metadata has been omitted from the export payload.";
    private static final String REDACTED_RECORDS_NOTE =
        "One or more exported records contain masked presentation fields. For those records, the stored contentHash covers the original or encrypted stored payload rather than the masked export view, so independent recomputation from the export bundle is not possible.";

    // Exact access-event types as per Scenario C specification
    static final List<String> ACCESS_EVENT_TYPES = List.of(
        "CLIENT_ACCOUNT_DATA_VIEWED",
        "CLIENT_ACCOUNT_DATA_SEARCHED",
        "CLIENT_ACCOUNT_DATA_EXPORTED",
        "CLIENT_ACCOUNT_DATA_UPDATED",
        "CLIENT_ACCOUNT_ACCESS_DENIED"
    );

    private final AuditEventRepository repository;
    private final RedactionViewService redactionViewService;
    private final ComplianceReportProperties properties;
    // H2 dependencies — null when using 3-arg backward-compat constructor
    private final AuditEventService auditEventService;
    private final CanonicalHashService canonicalHashService;
    private final ExportProperties exportProperties;
    private final SigningKeyProvider signingKeyProvider;
    private final Clock clock;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    // Backward-compatible constructor — H1 use-cases and existing tests that don't need bundle generation
    public ComplianceReportService(
        AuditEventRepository repository,
        RedactionViewService redactionViewService,
        ComplianceReportProperties properties
    ) {
        this(repository, redactionViewService, properties, null, null, null, null, Clock.systemUTC());
    }

    public ComplianceReportService(
        AuditEventRepository repository,
        RedactionViewService redactionViewService,
        ComplianceReportProperties properties,
        AuditEventService auditEventService,
        CanonicalHashService canonicalHashService,
        ExportProperties exportProperties,
        Clock clock
    ) {
        this(
            repository,
            redactionViewService,
            properties,
            auditEventService,
            canonicalHashService,
            exportProperties,
            null,
            clock
        );
    }

    // Full constructor — used by Spring (via @Autowired) and H2 tests
    @Autowired
    public ComplianceReportService(
        AuditEventRepository repository,
        RedactionViewService redactionViewService,
        ComplianceReportProperties properties,
        AuditEventService auditEventService,
        CanonicalHashService canonicalHashService,
        ExportProperties exportProperties,
        SigningKeyProvider signingKeyProvider,
        Clock clock
    ) {
        this.repository = repository;
        this.redactionViewService = redactionViewService;
        this.properties = properties;
        this.auditEventService = auditEventService;
        this.canonicalHashService = canonicalHashService;
        this.exportProperties = exportProperties;
        this.signingKeyProvider = signingKeyProvider;
        this.clock = clock;
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

    // -----------------------------------------------------------------------
    // H2: Signed regulatory bundle generation
    // -----------------------------------------------------------------------

    public JsonNode generateSignedBundle(ComplianceBundleRequest request) {
        requireBundleCapabilities();

        // 1. Validate approvalRef
        if (request.approvalRef() == null || request.approvalRef().isBlank()) {
            throw new IllegalArgumentException("approvalRef is required");
        }

        // 2. Validate exactly one selector
        int selectorCount = (request.accountId() != null ? 1 : 0) + (request.resourceId() != null ? 1 : 0);
        if (selectorCount != 1) {
            throw new IllegalArgumentException("Exactly one of accountId or resourceId must be specified");
        }

        // 3. Validate and normalize timestamps
        String normalizedFrom = validateAndNormalizeTimestamp(request.from(), "from");
        String normalizedTo = validateAndNormalizeTimestamp(request.to(), "to");
        if (normalizedFrom.compareTo(normalizedTo) >= 0) {
            throw new IllegalArgumentException("'from' must be before 'to'");
        }
        long windowMillis = calculateWindowMillis(normalizedFrom, normalizedTo);
        if (windowMillis > properties.getMaxUtcWindowMillis()) {
            throw new IllegalArgumentException(
                String.format("UTC window %d ms exceeds maximum allowed %d ms",
                    windowMillis, properties.getMaxUtcWindowMillis())
            );
        }

        // 4. Determine selector
        String selectorType = request.accountId() != null ? "accountId" : "resourceId";
        String selectorValue = request.accountId() != null ? request.accountId() : request.resourceId();

        // 5. Fetch all matching events as a bounded snapshot (no pagination)
        int maxRecords = exportProperties.getMaxRecords();
        List<AuditEvent> allMatches = repository.findWithFilters(
            request.actorId(),
            "CLIENT_ACCOUNT",
            selectorValue,
            null,
            normalizedFrom,
            normalizedTo,
            null,               // no cursor — full bounded snapshot
            (long) maxRecords + 1,
            request.includeArchived()
        );
        if (allMatches.size() > maxRecords) {
            throw new IllegalArgumentException("Bundle exceeds maximum size of " + maxRecords + " records");
        }

        // 6. Apply access-event taxonomy filter + exclude certificate events
        List<AuditEvent> filtered = allMatches.stream()
            .filter(e -> ACCESS_EVENT_TYPES.contains(e.getEventType()))
            .filter(e -> !COMPLIANCE_REPORT_GENERATED.equals(e.getEventType()))
            .toList();

        // 7. Apply optional action / outcome filters (same as H1)
        List<AuditEvent> results = filtered.stream()
            .filter(e -> request.action() == null || matchesAction(e, request.action()))
            .filter(e -> request.outcome() == null || matchesOutcome(e, request.outcome()))
            .toList();

        // 8. Apply Scenario B masking and destroyed-key redaction
        List<AuditEventResponse> records = redactionViewService.maskEvents(results);

        // 9. Build records array (identical format to ExportService so ExportVerifier can verify)
        ArrayNode recordsArray = objectMapper.createArrayNode();
        for (AuditEventResponse r : records) {
            recordsArray.add(buildBundleRecordNode(r));
        }

        // 10. Compute recordsDigest
        String recordsDigest = ExportDigestSupport.computeRecordsDigest(canonicalHashService, recordsArray);

        // 11. Assemble bundle
        String generatedAt = TimestampNormalizer.nowUtcString(clock);
        boolean redactedPresent = records.stream().anyMatch(AuditEventResponse::redacted);
        Long firstChainPos = records.isEmpty() ? null : records.get(0).chainPosition();
        Long lastChainPos  = records.isEmpty() ? null : records.get(records.size() - 1).chainPosition();
        String firstPrevHash = records.isEmpty() ? null : records.get(0).previousHash();
        String lastChainHash = records.isEmpty() ? null : records.get(records.size() - 1).chainHash();

        ObjectNode bundle = objectMapper.createObjectNode();
        bundle.put("manifestVersion", "1");
        bundle.put("reportType", "COMPLIANCE_ACCESS_REPORT");
        bundle.put("generatedAt", generatedAt);
        bundle.set("selection", buildBundleSelectionNode(request, selectorType, selectorValue, normalizedFrom, normalizedTo));
        bundle.put("recordCount", records.size());
        putNullableLong(bundle, "firstChainPosition", firstChainPos);
        putNullableLong(bundle, "lastChainPosition", lastChainPos);
        putNullableText(bundle, "firstPreviousHash", firstPrevHash);
        putNullableText(bundle, "lastChainHash", lastChainHash);
        bundle.put("hashVersion", "v1");
        bundle.put("canonicalizationVersion", "v1");
        bundle.set("records", recordsArray);
        bundle.put("recordsDigest", recordsDigest);
        bundle.put("redactedRecordsPresent", redactedPresent);
        if (redactedPresent) {
            bundle.put("redactedRecordsNote", REDACTED_RECORDS_NOTE);
        } else {
            bundle.putNull("redactedRecordsNote");
        }

        // 12. Compute bundleDigest over all fields except bundleDigest+signature
        String bundleDigest = ExportDigestSupport.computeBundleDigest(canonicalHashService, bundle);
        bundle.put("bundleDigest", bundleDigest);

        // 13. Sign — proves integrity and signer authenticity, NOT absolute completeness
        if (exportProperties.getSigning().isEnabled()) {
            bundle.set("signature", buildBundleSignatureNode(bundleDigest));
        }

        // 14. Compute a non-PII criteria digest for the certificate event
        String criteriaDigest = computeCriteriaDigest(
            selectorType, selectorValue, normalizedFrom, normalizedTo,
            request.actorId(), request.action(), request.outcome(), request.includeArchived()
        );

        // 15. Append COMPLIANCE_REPORT_GENERATED certificate event — MUST succeed before returning
        //     The cert event is appended after the bundle snapshot is taken; it is never in the bundle.
        appendComplianceCertificateEvent(
            request.approvalRef(),
            request.reasonCode(),
            records.size(),
            bundleDigest,
            criteriaDigest,
            generatedAt
        );

        return bundle;
    }

    private void requireBundleCapabilities() {
        if (auditEventService == null || canonicalHashService == null || exportProperties == null) {
            throw new IllegalStateException(
                "ComplianceReportService is not configured for bundle generation (missing H2 dependencies)");
        }
    }

    private ObjectNode buildBundleSelectionNode(
        ComplianceBundleRequest request,
        String selectorType, String selectorValue,
        String normalizedFrom, String normalizedTo
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("reportType", "COMPLIANCE_ACCESS_REPORT");
        node.put("selectorType", selectorType);
        node.put("selectorValue", selectorValue);
        node.put("from", normalizedFrom);
        node.put("to", normalizedTo);
        putNullableText(node, "actorIdFilter", request.actorId());
        putNullableText(node, "actionFilter", request.action());
        putNullableText(node, "outcomeFilter", request.outcome());
        node.put("includeArchivedEvents", request.includeArchived());
        node.put("approvalRef", request.approvalRef());
        putNullableText(node, "reasonCode", request.reasonCode());
        node.put("signatureIntegrityNote",
            "Signature proves integrity and signer authenticity. " +
            "It does not independently prove absolute completeness of the audit record set.");
        return node;
    }

    private ObjectNode buildBundleRecordNode(AuditEventResponse r) {
        BundlePayloadView payloadView = buildBundleExportPayload(r);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", r.id());
        node.put("chainPosition", r.chainPosition());
        node.put("eventType", r.eventType());
        putNullableText(node, "actorId", r.actorId());
        putNullableText(node, "resourceType", r.resourceType());
        putNullableText(node, "resourceId", r.resourceId());
        node.set("payload", payloadView.payload());
        putNullableText(node, "timestamp", r.timestamp());
        putNullableText(node, "ingestedAt", r.ingestedAt());
        putNullableText(node, "contentHash", r.contentHash());
        putNullableText(node, "previousHash", r.previousHash());
        putNullableText(node, "chainHash", r.chainHash());
        putNullableText(node, "hashVersion", r.hashVersion());
        node.put("redacted", r.redacted());
        ArrayNode ptrs = objectMapper.createArrayNode();
        if (r.redactedPointers() != null) {
            r.redactedPointers().forEach(ptrs::add);
        }
        node.set("redactedPointers", ptrs);
        boolean limited = payloadView.hasVerificationLimitations();
        node.put("contentHashVerificationStatus", limited ? "LIMITED_BY_EXPORTED_PRESENTATION" : "REPRODUCIBLE_FROM_EXPORT");
        if (limited) {
            node.put("contentHashVerificationNote", String.join(" ", payloadView.verificationLimitations()));
        } else {
            node.putNull("contentHashVerificationNote");
        }
        return node;
    }

    private BundlePayloadView buildBundleExportPayload(AuditEventResponse response) {
        JsonNode payload = response.payload().deepCopy();
        List<String> limitations = new ArrayList<>();
        if (response.redacted()) {
            limitations.add(REDACTED_RECORD_LIMITATION);
        }
        if ("REDACTION_APPLIED".equals(response.eventType()) && payload.isObject()) {
            ObjectNode sanitized = (ObjectNode) payload.deepCopy();
            boolean removedInternal = sanitized.has("keyRefs")
                || sanitized.has("keyStatus")
                || sanitized.has("encryptionVersion");
            sanitized.remove(List.of("keyRefs", "keyStatus", "encryptionVersion"));
            payload = sanitized;
            if (removedInternal) {
                limitations.add(INTERNAL_ENCRYPTION_METADATA_LIMITATION);
            }
        }
        return new BundlePayloadView(payload, limitations);
    }

    private ObjectNode buildBundleSignatureNode(String bundleDigest) {
        if (signingKeyProvider == null) {
            throw new IllegalStateException("Compliance bundle signing is enabled but no SigningKeyProvider is configured");
        }
        ObjectNode sig = objectMapper.createObjectNode();
        sig.put("algorithm", exportProperties.getSigning().getAlgorithm());
        sig.put("keyId", signingKeyProvider.getKeyId());
        sig.put("value", ExportSignatureSupport.signDigestToBase64(bundleDigest, signingKeyProvider.loadPrivateKey()));
        sig.put("publicKey", ExportSignatureSupport.encodePublicKeyBase64(signingKeyProvider.loadPublicKey()));
        return sig;
    }

    private String computeCriteriaDigest(
        String selectorType, String selectorValue,
        String from, String to,
        String actorId, String action, String outcome, boolean includeArchived
    ) {
        ObjectNode criteria = objectMapper.createObjectNode();
        criteria.put("selectorType", selectorType);
        criteria.put("selectorValue", selectorValue);
        criteria.put("from", from);
        criteria.put("to", to);
        if (actorId != null) criteria.put("actorId", actorId);
        if (action != null)  criteria.put("action", action);
        if (outcome != null) criteria.put("outcome", outcome);
        criteria.put("includeArchived", includeArchived);
        return canonicalHashService.sha256Hex(canonicalHashService.canonicalizeValue(criteria));
    }

    private void appendComplianceCertificateEvent(
        String approvalRef, String reasonCode,
        int recordCount, String bundleDigest, String criteriaDigest, String generatedAt
    ) {
        if (exportProperties.getSigning().isEnabled() && signingKeyProvider == null) {
            throw new IllegalStateException("Compliance bundle signing is enabled but no SigningKeyProvider is configured");
        }
        String signingKeyId = exportProperties.getSigning().isEnabled()
            ? signingKeyProvider.getKeyId()
            : null;

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("approvalRef", approvalRef);
        if (reasonCode != null && !reasonCode.isBlank()) {
            payload.put("reasonCode", reasonCode);
        }
        payload.put("recordCount", recordCount);
        payload.put("bundleDigest", bundleDigest);
        payload.put("criteriaDigest", criteriaDigest);
        payload.put("generatedAt", generatedAt);
        if (signingKeyId != null) {
            payload.put("signingKeyId", signingKeyId);
        }

        AuditEventCreateRequest certRequest = new AuditEventCreateRequest();
        certRequest.setEventType(COMPLIANCE_REPORT_GENERATED);
        certRequest.setActorId("system:compliance-report-service");
        certRequest.setResourceType("COMPLIANCE_REPORT");
        certRequest.setResourceId(approvalRef);
        certRequest.setPayload(payload);
        // timestamp left null — AuditEventService sets it to now

        auditEventService.createEvent(certRequest);
    }

    private void putNullableText(ObjectNode node, String field, String value) {
        if (value == null) {
            node.set(field, JsonNodeFactory.instance.nullNode());
        } else {
            node.put(field, value);
        }
    }

    private void putNullableLong(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.set(field, JsonNodeFactory.instance.nullNode());
        } else {
            node.put(field, value);
        }
    }

    private record BundlePayloadView(JsonNode payload, List<String> verificationLimitations) {
        boolean hasVerificationLimitations() {
            return !verificationLimitations.isEmpty();
        }
    }
}
