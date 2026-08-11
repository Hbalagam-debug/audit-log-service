package com.auditlog.service.service;

import com.auditlog.service.api.dto.AuditEventResponse;
import com.auditlog.service.config.RedactionProperties;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.domain.RedactionOverlay;
import com.auditlog.service.repository.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

@Service
public class RedactionViewService {
    private final AuditEventRepository repository;
    private final RedactionProperties redactionProperties;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public RedactionViewService(AuditEventRepository repository, RedactionProperties redactionProperties) {
        this.repository = repository;
        this.redactionProperties = redactionProperties;
    }

    public List<AuditEventResponse> maskEvents(List<AuditEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }

        List<String> eventIds = events.stream().map(AuditEvent::getId).toList();
        List<RedactionOverlay> overlays = repository.findActiveOverlaysForEventIds(eventIds);
        Map<String, List<RedactionOverlay>> overlaysByEvent = overlays.stream()
            .collect(Collectors.groupingBy(RedactionOverlay::getEventId, LinkedHashMap::new, Collectors.toList()));

        List<AuditEventResponse> responses = new ArrayList<>();
        for (AuditEvent event : events) {
            List<RedactionOverlay> eventOverlays = overlaysByEvent.getOrDefault(event.getId(), List.of());
            JsonNode maskedPayload = event.getPayload().deepCopy();
            List<String> pointerList = eventOverlays.stream()
                .map(RedactionOverlay::getJsonPointer)
                .distinct()
                .sorted()
                .toList();
            for (String pointer : pointerList) {
                applyMask(maskedPayload, pointer);
            }
            boolean redacted = !pointerList.isEmpty();
            responses.add(AuditEventResponse.fromDomain(event, maskedPayload, redacted ? pointerList : List.of()));
        }
        return responses;
    }

    private void applyMask(JsonNode root, String pointer) {
        List<String> tokens = parsePointer(pointer);
        JsonNode current = root;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (current == null || current.isMissingNode()) {
                return;
            }
            if (i == tokens.size() - 1) {
                if (current.isObject()) {
                    if (current.has(token)) {
                        ((ObjectNode) current).put(token, redactionProperties.getMaskValue());
                    }
                } else if (current.isArray()) {
                    try {
                        int index = Integer.parseInt(token);
                        if (index >= 0 && index < current.size()) {
                            ((ArrayNode) current).set(index, objectMapper.getNodeFactory().textNode(redactionProperties.getMaskValue()));
                        }
                    } catch (NumberFormatException ignored) {
                        // Ignore invalid array tokens.
                    }
                }
                return;
            }
            if (current.isObject()) {
                if (!current.has(token)) {
                    return;
                }
                current = current.get(token);
            } else if (current.isArray()) {
                try {
                    int index = Integer.parseInt(token);
                    if (index < 0 || index >= current.size()) {
                        return;
                    }
                    current = current.get(index);
                } catch (NumberFormatException e) {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private List<String> parsePointer(String pointer) {
        if (pointer == null || pointer.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        String remainder = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        if (remainder.isEmpty()) {
            return tokens;
        }
        String[] segments = remainder.split("/");
        for (String segment : segments) {
            StringBuilder token = new StringBuilder();
            for (int i = 0; i < segment.length(); i++) {
                char ch = segment.charAt(i);
                if (ch == '~') {
                    if (i + 1 >= segment.length()) {
                        throw new IllegalArgumentException("Invalid escape sequence in pointer: " + pointer);
                    }
                    char next = segment.charAt(i + 1);
                    if (next == '0') {
                        token.append('~');
                    } else if (next == '1') {
                        token.append('/');
                    } else {
                        throw new IllegalArgumentException("Invalid escape sequence in pointer: " + pointer);
                    }
                    i++;
                } else {
                    token.append(ch);
                }
            }
            tokens.add(token.toString());
        }
        return tokens;
    }
}
