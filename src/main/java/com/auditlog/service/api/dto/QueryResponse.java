package com.auditlog.service.api.dto;

import java.util.List;

public record QueryResponse(
    List<AuditEventResponse> items,
    String nextCursor,
    boolean hasMore
) {
}
