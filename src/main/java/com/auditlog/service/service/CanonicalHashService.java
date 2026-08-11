package com.auditlog.service.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CanonicalHashService {
    private static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String HASH_VERSION = "v1";
    private static final String DOMAIN_SEPARATOR = "AUDIT_v1";
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public String computeContentHash(
        String eventType,
        String actorId,
        String resourceType,
        String resourceId,
        JsonNode payload,
        String timestamp
    ) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("actorId", actorId);
        canonical.set("payload", payload);
        canonical.put("eventType", eventType);
        canonical.put("resourceId", resourceId);
        canonical.put("resourceType", resourceType);
        canonical.put("timestamp", timestamp);

        String canonical_json = canonicalizeJson(canonical);
        return sha256Hex(canonical_json);
    }

    public String computeChainHash(long chainPosition, String previousHash, String contentHash) {
        String input = DOMAIN_SEPARATOR + ":" + chainPosition + ":" + previousHash + ":" + contentHash;
        return sha256Hex(input);
    }

    public String getGenesisHash() {
        return GENESIS_HASH;
    }

    public String getHashVersion() {
        return HASH_VERSION;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 computation failed", e);
        }
    }

    private String canonicalizeJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? "true" : "false";
        }
        if (node.isNumber()) {
            return node.toString();
        }
        if (node.isTextual()) {
            return "\"" + escapeJsonString(node.asText()) + "\"";
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (JsonNode element : node) {
                if (!first) sb.append(",");
                sb.append(canonicalizeJson(element));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (var property : node.properties()) {
                sorted.put(property.getKey(), property.getValue());
            }

            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (String key : sorted.keySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJsonString(key)).append("\":");
                sb.append(canonicalizeJson(sorted.get(key)));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        return node.toString();
    }

    private String escapeJsonString(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
