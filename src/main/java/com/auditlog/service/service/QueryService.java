package com.auditlog.service.service;

import com.auditlog.service.api.dto.AuditEventResponse;
import com.auditlog.service.api.dto.QueryResponse;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QueryService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final String CURSOR_FIELD = "chainPosition";
    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public QueryService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public QueryResponse query(
        String actorId,
        String resourceType,
        String resourceId,
        String eventType,
        String from,
        String to,
        String cursor,
        Integer limit
    ) {
        if (limit == null) {
            limit = DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            limit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        }

        if (from != null && to != null) {
            try {
                if (TimestampNormalizer.parseUtcString(from)
                    .compareTo(TimestampNormalizer.parseUtcString(to)) >= 0) {
                    throw new IllegalArgumentException("'from' must be before 'to'");
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid timestamp range: " + e.getMessage());
            }
        }

        Long afterChainPosition = null;
        if (cursor != null && !cursor.isEmpty()) {
            afterChainPosition = decodeCursor(cursor);
        }

        List<AuditEvent> results = repository.findWithFilters(
            actorId,
            resourceType,
            resourceId,
            eventType,
            from,
            to,
            afterChainPosition,
            limit + 1
        );

        boolean hasMore = results.size() > limit;
        if (hasMore) {
            results = results.subList(0, limit);
        }

        String nextCursor = null;
        if (hasMore && !results.isEmpty()) {
            long lastPosition = results.get(results.size() - 1).getChainPosition();
            nextCursor = encodeCursor(lastPosition);
        }

        List<AuditEventResponse> items = results.stream()
            .map(AuditEventResponse::fromDomain)
            .collect(Collectors.toList());

        return new QueryResponse(items, nextCursor, hasMore);
    }

    String encodeCursor(long chainPosition) {
        try {
            if (chainPosition < 1) {
                throw new IllegalArgumentException("Cursor chain position must be positive");
            }
            String json = objectMapper.createObjectNode()
                .put(CURSOR_FIELD, chainPosition)
                .toString();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode cursor", e);
        }
    }

    long decodeCursor(String cursor) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(restoreBase64Padding(cursor));
            JsonNode root = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));

            if (!root.isObject() || root.size() != 1 || !root.has(CURSOR_FIELD)) {
                throw new InvalidCursorException("Cursor must contain exactly one chainPosition field");
            }

            JsonNode chainPositionNode = root.get(CURSOR_FIELD);
            if (!chainPositionNode.canConvertToLong() || !chainPositionNode.isIntegralNumber()) {
                throw new InvalidCursorException("Cursor chainPosition must be an integer");
            }

            long chainPosition = chainPositionNode.longValue();
            if (chainPosition < 1) {
                throw new InvalidCursorException("Cursor chainPosition must be positive");
            }

            return chainPosition;
        } catch (InvalidCursorException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Invalid cursor");
        } catch (Exception e) {
            throw new InvalidCursorException("Invalid cursor");
        }
    }

    private String restoreBase64Padding(String cursor) {
        int remainder = cursor.length() % 4;
        if (remainder == 0) {
            return cursor;
        }
        return cursor + "=".repeat(4 - remainder);
    }
}
