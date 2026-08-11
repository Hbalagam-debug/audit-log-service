package com.auditlog.service.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class JsonPointerUtils {
    private JsonPointerUtils() {
    }

    public static String canonicalize(String pointer) {
        if (pointer == null) {
            throw new IllegalArgumentException("Pointer must not be null");
        }
        String trimmed = pointer.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) {
            throw new IllegalArgumentException("Invalid pointer syntax: " + pointer);
        }
        List<String> tokens = parse(trimmed);
        return "/" + tokens.stream().map(JsonPointerUtils::escapeToken).reduce((left, right) -> left + "/" + right).orElse("");
    }

    public static List<String> normalize(List<String> pointers) {
        if (pointers == null || pointers.isEmpty()) {
            throw new IllegalArgumentException("jsonPointers is required");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String pointer : pointers) {
            normalized.add(canonicalize(pointer));
        }
        return normalized.stream().sorted().toList();
    }

    public static List<String> parse(String pointer) {
        if (pointer.equals("/")) {
            return List.of("");
        }
        String remainder = pointer.substring(1);
        if (remainder.isEmpty()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        for (String segment : remainder.split("/", -1)) {
            StringBuilder token = new StringBuilder();
            for (int i = 0; i < segment.length(); i++) {
                char ch = segment.charAt(i);
                if (ch != '~') {
                    token.append(ch);
                    continue;
                }
                if (i + 1 >= segment.length()) {
                    throw new IllegalArgumentException("Invalid escape sequence in pointer: " + pointer);
                }
                char next = segment.charAt(++i);
                if (next == '0') {
                    token.append('~');
                } else if (next == '1') {
                    token.append('/');
                } else {
                    throw new IllegalArgumentException("Invalid escape sequence in pointer: " + pointer);
                }
            }
            tokens.add(token.toString());
        }
        return tokens;
    }

    public static boolean exists(JsonNode root, String pointer) {
        return getNode(root, pointer) != null;
    }

    public static JsonNode getNode(JsonNode root, String pointer) {
        List<String> tokens = parse(pointer);
        JsonNode current = root;
        for (String token : tokens) {
            if (current == null || current.isMissingNode()) {
                return null;
            }
            if (current.isObject()) {
                if (!current.has(token)) {
                    return null;
                }
                current = current.get(token);
                continue;
            }
            if (current.isArray()) {
                try {
                    int index = Integer.parseInt(token);
                    if (index < 0 || index >= current.size()) {
                        return null;
                    }
                    current = current.get(index);
                } catch (NumberFormatException ex) {
                    return null;
                }
                continue;
            }
            return null;
        }
        return current;
    }

    public static void setValue(JsonNode root, String pointer, JsonNode value) {
        List<String> tokens = parse(pointer);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Root replacement is not supported");
        }

        JsonNode current = root;
        for (int i = 0; i < tokens.size() - 1; i++) {
            String token = tokens.get(i);
            if (current == null) {
                throw new IllegalArgumentException("Pointer does not exist in payload: " + pointer);
            }
            if (current.isObject()) {
                current = current.get(token);
            } else if (current.isArray()) {
                int index = Integer.parseInt(token);
                current = current.get(index);
            } else {
                throw new IllegalArgumentException("Pointer does not exist in payload: " + pointer);
            }
        }

        String lastToken = tokens.get(tokens.size() - 1);
        if (current == null) {
            throw new IllegalArgumentException("Pointer does not exist in payload: " + pointer);
        }
        if (current.isObject()) {
            ((ObjectNode) current).set(lastToken, value);
            return;
        }
        if (current.isArray()) {
            int index = Integer.parseInt(lastToken);
            ((ArrayNode) current).set(index, value);
            return;
        }
        throw new IllegalArgumentException("Pointer does not exist in payload: " + pointer);
    }

    public static void validateNoOverlaps(List<String> pointers) {
        List<String> normalized = normalize(pointers);
        for (int i = 0; i < normalized.size(); i++) {
            List<String> left = parse(normalized.get(i));
            for (int j = i + 1; j < normalized.size(); j++) {
                List<String> right = parse(normalized.get(j));
                if (isPrefix(left, right) || isPrefix(right, left)) {
                    throw new IllegalStateException("Overlapping encryption pointers are not allowed: " + normalized.get(i) + " and " + normalized.get(j));
                }
            }
        }
    }

    private static boolean isPrefix(List<String> prefix, List<String> value) {
        if (prefix.size() >= value.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(value.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String escapeToken(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
