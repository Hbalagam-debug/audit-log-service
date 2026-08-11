package com.auditlog.service.service;

import com.auditlog.service.api.dto.AuditEventResponse;
import com.auditlog.service.config.ExportProperties;
import com.auditlog.service.config.ExportSignatureSupport;
import com.auditlog.service.config.SigningKeyProvider;
import com.auditlog.service.repository.AuditEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class ExportService {
    private static final String MANIFEST_VERSION = "1";
    private static final String HASH_VERSION = "v1";
    private static final String CANONICALIZATION_VERSION = "v1";
    private static final String CONTENT_HASH_STATUS_REPRODUCIBLE = "REPRODUCIBLE_FROM_EXPORT";
    private static final String CONTENT_HASH_STATUS_LIMITED = "LIMITED_BY_EXPORTED_PRESENTATION";
    private static final String REDACTED_RECORDS_NOTE =
        "One or more exported records contain masked presentation fields. For those records, the stored contentHash covers the original or encrypted stored payload rather than the masked export view, so independent recomputation from the export bundle is not possible.";
    private static final String REDACTED_RECORD_LIMITATION =
        "Masked fields are rendered for export. The stored contentHash covers the original or encrypted stored payload, not the masked presentation value.";
    private static final String INTERNAL_ENCRYPTION_METADATA_LIMITATION =
        "Internal encryption key-destruction metadata has been omitted from the export payload.";

    private final AuditEventRepository repository;
    private final RedactionViewService redactionViewService;
    private final CanonicalHashService canonicalHashService;
    private final ExportProperties exportProperties;
    private final SigningKeyProvider signingKeyProvider;
    private final Clock clock;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public ExportService(
        AuditEventRepository repository,
        RedactionViewService redactionViewService,
        CanonicalHashService canonicalHashService,
        ExportProperties exportProperties
    ) {
        this(repository, redactionViewService, canonicalHashService, exportProperties, null, Clock.systemUTC());
    }

    @Autowired
    public ExportService(
        AuditEventRepository repository,
        RedactionViewService redactionViewService,
        CanonicalHashService canonicalHashService,
        ExportProperties exportProperties,
        SigningKeyProvider signingKeyProvider,
        Clock clock
    ) {
        this.repository = repository;
        this.redactionViewService = redactionViewService;
        this.canonicalHashService = canonicalHashService;
        this.exportProperties = exportProperties;
        this.signingKeyProvider = signingKeyProvider;
        this.clock = clock;
    }

    public ExportService(
        AuditEventRepository repository,
        RedactionViewService redactionViewService,
        CanonicalHashService canonicalHashService,
        ExportProperties exportProperties,
        Clock clock
    ) {
        this(repository, redactionViewService, canonicalHashService, exportProperties, null, clock);
    }

    public JsonNode export(String actorId, String resourceId, String from, String to, boolean includeArchived) {
        ExportSelection selection = validateAndNormalizeSelection(actorId, resourceId, from, to, includeArchived);

        int maxRecords = exportProperties.getMaxRecords();
        List<com.auditlog.service.domain.AuditEvent> rawEvents = repository.findWithFilters(
            selection.actorId(),
            null,
            selection.resourceId(),
            null,
            selection.from(),
            selection.to(),
            null,
            (long) maxRecords + 1,
            selection.includeArchived()
        );

        if (rawEvents.size() > maxRecords) {
            throw new IllegalArgumentException("Export exceeds maximum size of " + maxRecords + " records");
        }

        List<AuditEventResponse> records = redactionViewService.maskEvents(rawEvents);
        ArrayNode recordsArray = objectMapper.createArrayNode();
        for (AuditEventResponse r : records) {
            recordsArray.add(buildRecordNode(r));
        }

        String recordsDigest = ExportDigestSupport.computeRecordsDigest(canonicalHashService, recordsArray);

        Long firstChainPosition = records.isEmpty() ? null : records.get(0).chainPosition();
        Long lastChainPosition = records.isEmpty() ? null : records.get(records.size() - 1).chainPosition();
        String firstPreviousHash = records.isEmpty() ? null : records.get(0).previousHash();
        String lastChainHash = records.isEmpty() ? null : records.get(records.size() - 1).chainHash();
        boolean redactedPresent = records.stream().anyMatch(AuditEventResponse::redacted);

        ObjectNode bundle = objectMapper.createObjectNode();
        bundle.put("manifestVersion", MANIFEST_VERSION);
        bundle.put("generatedAt", TimestampNormalizer.nowUtcString(clock));
        bundle.set("selection", buildSelectionNode(selection));
        bundle.put("recordCount", records.size());
        putNullableLong(bundle, "firstChainPosition", firstChainPosition);
        putNullableLong(bundle, "lastChainPosition", lastChainPosition);
        putNullableText(bundle, "firstPreviousHash", firstPreviousHash);
        putNullableText(bundle, "lastChainHash", lastChainHash);
        bundle.put("hashVersion", HASH_VERSION);
        bundle.put("canonicalizationVersion", CANONICALIZATION_VERSION);
        bundle.set("records", recordsArray);
        bundle.put("recordsDigest", recordsDigest);
        bundle.put("redactedRecordsPresent", redactedPresent);
        if (redactedPresent) {
            bundle.put("redactedRecordsNote", REDACTED_RECORDS_NOTE);
        } else {
            bundle.putNull("redactedRecordsNote");
        }

        String bundleDigest = ExportDigestSupport.computeBundleDigest(canonicalHashService, bundle);
        bundle.put("bundleDigest", bundleDigest);
        if (exportProperties.getSigning().isEnabled()) {
            bundle.set("signature", buildSignatureNode(bundleDigest));
        }
        return bundle;
    }

    private ObjectNode buildRecordNode(AuditEventResponse r) {
        ExportPayloadView payloadView = buildExportPayload(r);
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
        node.put(
            "contentHashVerificationStatus",
            payloadView.hasVerificationLimitations() ? CONTENT_HASH_STATUS_LIMITED : CONTENT_HASH_STATUS_REPRODUCIBLE
        );
        if (payloadView.hasVerificationLimitations()) {
            node.put("contentHashVerificationNote", String.join(" ", payloadView.verificationLimitations()));
        } else {
            node.putNull("contentHashVerificationNote");
        }
        return node;
    }

    private ObjectNode buildSelectionNode(ExportSelection selection) {
        ObjectNode selectionNode = objectMapper.createObjectNode();
        putNullableText(selectionNode, "actorId", selection.actorId());
        putNullableText(selectionNode, "resourceId", selection.resourceId());
        putNullableText(selectionNode, "from", selection.from());
        putNullableText(selectionNode, "to", selection.to());
        selectionNode.put("includeArchived", selection.includeArchived());
        return selectionNode;
    }

    private ExportSelection validateAndNormalizeSelection(
        String actorId,
        String resourceId,
        String from,
        String to,
        boolean includeArchived
    ) {
        String normalizedActorId = normalizeSelector(actorId);
        String normalizedResourceId = normalizeSelector(resourceId);
        boolean hasActor = normalizedActorId != null;
        boolean hasResource = normalizedResourceId != null;
        if (hasActor == hasResource) {
            throw new IllegalArgumentException("Exactly one of actorId or resourceId must be provided");
        }

        String normalizedFrom = normalizeTimestampBound(from, "from");
        String normalizedTo = normalizeTimestampBound(to, "to");
        if (normalizedFrom != null && normalizedTo != null) {
            Instant fromInstant = TimestampNormalizer.parseUtcString(normalizedFrom);
            Instant toInstant = TimestampNormalizer.parseUtcString(normalizedTo);
            if (!fromInstant.isBefore(toInstant)) {
                throw new IllegalArgumentException("'from' must be before 'to'");
            }
        }

        return new ExportSelection(normalizedActorId, normalizedResourceId, normalizedFrom, normalizedTo, includeArchived);
    }

    private String normalizeSelector(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeTimestampBound(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("'" + fieldName + "' must be a non-empty ISO-8601 UTC timestamp");
        }
        try {
            Instant instant = TimestampNormalizer.parseUtcString(trimmed);
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(instant.atOffset(ZoneOffset.UTC))
                .replace("+00:00", "Z");
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid '" + fieldName + "' timestamp: " + value, ex);
        }
    }

    private ExportPayloadView buildExportPayload(AuditEventResponse response) {
        tools.jackson.databind.JsonNode payload = response.payload().deepCopy();
        List<String> verificationLimitations = new ArrayList<>();

        if (response.redacted()) {
            verificationLimitations.add(REDACTED_RECORD_LIMITATION);
        }

        if ("REDACTION_APPLIED".equals(response.eventType()) && payload.isObject()) {
            ObjectNode sanitizedPayload = (ObjectNode) payload.deepCopy();
            boolean removedInternalMetadata = sanitizedPayload.has("keyRefs")
                || sanitizedPayload.has("keyStatus")
                || sanitizedPayload.has("encryptionVersion");
            sanitizedPayload.remove(List.of("keyRefs", "keyStatus", "encryptionVersion"));
            payload = sanitizedPayload;
            if (removedInternalMetadata) {
                verificationLimitations.add(INTERNAL_ENCRYPTION_METADATA_LIMITATION);
            }
        }

        return new ExportPayloadView(payload, verificationLimitations);
    }

    private void putNullableText(ObjectNode node, String fieldName, String value) {
        if (value == null) {
            node.set(fieldName, JsonNodeFactory.instance.nullNode());
            return;
        }
        node.put(fieldName, value);
    }

    private void putNullableLong(ObjectNode node, String fieldName, Long value) {
        if (value == null) {
            node.set(fieldName, JsonNodeFactory.instance.nullNode());
            return;
        }
        node.put(fieldName, value);
    }

    private ObjectNode buildSignatureNode(String bundleDigest) {
        if (signingKeyProvider == null) {
            throw new IllegalStateException("Export signing is enabled but no SigningKeyProvider is configured");
        }
        ObjectNode signatureNode = objectMapper.createObjectNode();
        signatureNode.put("algorithm", exportProperties.getSigning().getAlgorithm());
        signatureNode.put("keyId", signingKeyProvider.getKeyId());
        signatureNode.put(
            "value",
            ExportSignatureSupport.signDigestToBase64(bundleDigest, signingKeyProvider.loadPrivateKey())
        );
        signatureNode.put("publicKey", ExportSignatureSupport.encodePublicKeyBase64(signingKeyProvider.loadPublicKey()));
        return signatureNode;
    }

    private record ExportSelection(
        String actorId,
        String resourceId,
        String from,
        String to,
        boolean includeArchived
    ) {
    }

    private record ExportPayloadView(
        tools.jackson.databind.JsonNode payload,
        List<String> verificationLimitations
    ) {
        private boolean hasVerificationLimitations() {
            return !verificationLimitations.isEmpty();
        }
    }
}
