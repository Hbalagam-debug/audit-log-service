package com.auditlog.service.service;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

@Service
public class ExportVerifier {

    public record VerificationResult(boolean valid, String recordsDigestStatus, String bundleDigestStatus, List<String> errors) {}

    private final CanonicalHashService canonicalHashService;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public ExportVerifier(CanonicalHashService canonicalHashService) {
        this.canonicalHashService = canonicalHashService;
    }

    public VerificationResult verify(String bundleJson) {
        try {
            ObjectNode parsed = (ObjectNode) objectMapper.readTree(bundleJson);

            String storedRecordsDigest = parsed.get("recordsDigest").asText();
            String storedBundleDigest = parsed.get("bundleDigest").asText();

            ArrayNode records = (ArrayNode) parsed.get("records");
            String computedRecordsDigest = ExportDigestSupport.computeRecordsDigest(canonicalHashService, records);

            String recordsDigestStatus = computedRecordsDigest.equals(storedRecordsDigest) ? "MATCH" : "MISMATCH";

            ObjectNode bundleForDigest = (ObjectNode) objectMapper.readTree(bundleJson);
            String computedBundleDigest = ExportDigestSupport.computeBundleDigest(canonicalHashService, bundleForDigest);

            String bundleDigestStatus = computedBundleDigest.equals(storedBundleDigest) ? "MATCH" : "MISMATCH";

            List<String> errors = new ArrayList<>();
            if ("MISMATCH".equals(recordsDigestStatus)) {
                errors.add("recordsDigest mismatch: stored=" + storedRecordsDigest + ", computed=" + computedRecordsDigest);
            }
            if ("MISMATCH".equals(bundleDigestStatus)) {
                errors.add("bundleDigest mismatch: stored=" + storedBundleDigest + ", computed=" + computedBundleDigest);
            }

            boolean valid = errors.isEmpty();
            return new VerificationResult(valid, recordsDigestStatus, bundleDigestStatus, errors);

        } catch (Exception e) {
            List<String> errors = List.of("Failed to parse or verify bundle: " + e.getMessage());
            return new VerificationResult(false, "ERROR", "ERROR", errors);
        }
    }
}
