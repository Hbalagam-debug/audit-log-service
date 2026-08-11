package com.auditlog.service.api.dto;

import java.util.List;

public record RedactionResponse(
    String eventId,
    long eventChainPosition,
    List<String> appliedPointers,
    List<String> alreadyRedactedPointers,
    String redactionCertificateEventId,
    String status
) {
}
