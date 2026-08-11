package com.auditlog.service.service;

import com.auditlog.service.config.ExportProperties;
import com.auditlog.service.config.ExportSignatureSupport;
import com.auditlog.service.config.SigningKeyProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

@Service
public class ExportVerifier {
    private static final String SUPPORTED_MANIFEST_VERSION = "1";
    private static final String SUPPORTED_HASH_VERSION = "v1";
    private static final String SUPPORTED_CANONICALIZATION_VERSION = "v1";
    public record VerificationResult(
        boolean valid,
        boolean recordsDigestValid,
        boolean bundleDigestValid,
        boolean signatureValid,
        String keyId,
        List<String> errors
    ) {
    }

    private final CanonicalHashService canonicalHashService;
    private final String trustedKeyId;
    private final String trustedPublicKeyBase64;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public ExportVerifier(CanonicalHashService canonicalHashService) {
        this(canonicalHashService, (String) null, (String) null);
    }

    @Autowired
    public ExportVerifier(
        CanonicalHashService canonicalHashService,
        ExportProperties exportProperties,
        SigningKeyProvider signingKeyProvider
    ) {
        this(
            canonicalHashService,
            exportProperties.getSigning().isEnabled() ? signingKeyProvider.getKeyId() : null,
            exportProperties.getSigning().isEnabled()
                ? ExportSignatureSupport.encodePublicKeyBase64(signingKeyProvider.loadPublicKey())
                : null
        );
    }

    public ExportVerifier(CanonicalHashService canonicalHashService, String trustedKeyId, String trustedPublicKeyBase64) {
        this.canonicalHashService = canonicalHashService;
        this.trustedKeyId = trustedKeyId;
        this.trustedPublicKeyBase64 = trustedPublicKeyBase64;
    }

    public VerificationResult verify(String bundleJson) {
        try {
            ObjectNode parsed = (ObjectNode) objectMapper.readTree(bundleJson);
            List<String> errors = new ArrayList<>();

            validateManifest(parsed, errors);

            String storedRecordsDigest = textValue(parsed, "recordsDigest");
            String storedBundleDigest = textValue(parsed, "bundleDigest");

            ArrayNode records = (ArrayNode) parsed.get("records");
            String computedRecordsDigest = ExportDigestSupport.computeRecordsDigest(canonicalHashService, records);
            boolean recordsDigestValid = computedRecordsDigest.equals(storedRecordsDigest);

            ObjectNode bundleForDigest = (ObjectNode) objectMapper.readTree(bundleJson);
            String computedBundleDigest = ExportDigestSupport.computeBundleDigest(canonicalHashService, bundleForDigest);
            boolean bundleDigestValid = computedBundleDigest.equals(storedBundleDigest);
            if (!recordsDigestValid) {
                errors.add("recordsDigest mismatch: stored=" + storedRecordsDigest + ", computed=" + computedRecordsDigest);
            }
            if (!bundleDigestValid) {
                errors.add("bundleDigest mismatch: stored=" + storedBundleDigest + ", computed=" + computedBundleDigest);
            }

            SignatureVerification signatureVerification = verifySignature(parsed, storedBundleDigest, errors);
            boolean valid = recordsDigestValid && bundleDigestValid && signatureVerification.signatureValid() && errors.isEmpty();
            return new VerificationResult(
                valid,
                recordsDigestValid,
                bundleDigestValid,
                signatureVerification.signatureValid(),
                signatureVerification.keyId(),
                errors
            );

        } catch (Exception e) {
            List<String> errors = List.of("Failed to parse or verify bundle: " + e.getMessage());
            return new VerificationResult(false, false, false, false, null, errors);
        }
    }

    private void validateManifest(ObjectNode parsed, List<String> errors) {
        if (!SUPPORTED_MANIFEST_VERSION.equals(textValue(parsed, "manifestVersion"))) {
            errors.add("Unsupported manifestVersion: " + textValue(parsed, "manifestVersion"));
        }
        if (!SUPPORTED_HASH_VERSION.equals(textValue(parsed, "hashVersion"))) {
            errors.add("Unsupported hashVersion: " + textValue(parsed, "hashVersion"));
        }
        if (!SUPPORTED_CANONICALIZATION_VERSION.equals(textValue(parsed, "canonicalizationVersion"))) {
            errors.add("Unsupported canonicalizationVersion: " + textValue(parsed, "canonicalizationVersion"));
        }
    }

    private SignatureVerification verifySignature(ObjectNode parsed, String bundleDigest, List<String> errors) {
        tools.jackson.databind.JsonNode signatureNodeValue = parsed.get("signature");
        if (signatureNodeValue == null || signatureNodeValue.isNull()) {
            errors.add("Missing signature block");
            return new SignatureVerification(false, null);
        }
        if (!signatureNodeValue.isObject()) {
            errors.add("signature must be a JSON object");
            return new SignatureVerification(false, null);
        }
        ObjectNode signatureNode = (ObjectNode) signatureNodeValue;

        String algorithm = textValue(signatureNode, "algorithm");
        String keyId = textValue(signatureNode, "keyId");
        String signatureValue = textValue(signatureNode, "value");
        String embeddedPublicKey = textValue(signatureNode, "publicKey");

        if (!ExportSignatureSupport.SUPPORTED_SIGNATURE_ALGORITHM.equals(algorithm)) {
            errors.add("Unsupported signature.algorithm: " + algorithm);
            return new SignatureVerification(false, keyId);
        }

        java.security.PublicKey verificationKey;
        try {
            verificationKey = resolveVerificationKey(keyId, embeddedPublicKey, errors);
        } catch (IllegalStateException ex) {
            errors.add(ex.getMessage());
            return new SignatureVerification(false, keyId);
        }

        if (verificationKey == null) {
            return new SignatureVerification(false, keyId);
        }

        try {
            boolean signatureValid = ExportSignatureSupport.verifyDigestSignature(bundleDigest, signatureValue, verificationKey);
            if (!signatureValid) {
                errors.add("Ed25519 signature verification failed");
            }
            return new SignatureVerification(signatureValid, keyId);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            errors.add(ex.getMessage());
            return new SignatureVerification(false, keyId);
        }
    }

    private java.security.PublicKey resolveVerificationKey(String keyId, String embeddedPublicKey, List<String> errors) {
        if (trustedKeyId != null && trustedPublicKeyBase64 != null) {
            if (!trustedKeyId.equals(keyId)) {
                errors.add("Untrusted signature keyId: " + keyId);
                return null;
            }
            String normalizedEmbedded = normalizePublicKey(embeddedPublicKey);
            if (!trustedPublicKeyBase64.equals(normalizedEmbedded)) {
                errors.add("Embedded signature.publicKey does not match the trusted key configured for keyId " + keyId);
                return null;
            }
            return ExportSignatureSupport.decodePublicKey(trustedPublicKeyBase64, "signature.publicKey");
        }
        return ExportSignatureSupport.decodePublicKey(embeddedPublicKey, "signature.publicKey");
    }

    private String normalizePublicKey(String publicKeyBase64) {
        return ExportSignatureSupport.encodePublicKeyBase64(
            ExportSignatureSupport.decodePublicKey(publicKeyBase64, "signature.publicKey")
        );
    }

    private String textValue(tools.jackson.databind.JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !node.has(fieldName) || node.get(fieldName).isNull()) {
            return null;
        }
        return node.get(fieldName).asText();
    }

    private record SignatureVerification(boolean signatureValid, String keyId) {
    }
}
