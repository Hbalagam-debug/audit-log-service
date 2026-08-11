package com.auditlog.service.service;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

final class ExportDigestSupport {
    private ExportDigestSupport() {
    }

    static String computeRecordsDigest(CanonicalHashService canonicalHashService, ArrayNode records) {
        return canonicalHashService.sha256Hex(canonicalHashService.canonicalizeValue(records));
    }

    static String computeBundleDigest(CanonicalHashService canonicalHashService, ObjectNode bundle) {
        return canonicalHashService.sha256Hex(canonicalHashService.canonicalizeValue(unsignedBundle(bundle)));
    }

    static ObjectNode unsignedBundle(ObjectNode bundle) {
        ObjectNode unsignedBundle = bundle.deepCopy();
        List<String> fieldNames = new ArrayList<>();
        for (var property : unsignedBundle.properties()) {
            fieldNames.add(property.getKey());
        }
        for (String fieldName : fieldNames) {
            if (isExcludedFromBundleDigest(fieldName)) {
                unsignedBundle.remove(fieldName);
            }
        }
        return unsignedBundle;
    }

    private static boolean isExcludedFromBundleDigest(String fieldName) {
        return "bundleDigest".equals(fieldName)
            || "signature".equals(fieldName)
            || fieldName.startsWith("signature")
            || fieldName.startsWith("signing");
    }
}
