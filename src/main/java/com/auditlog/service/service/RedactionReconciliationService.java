package com.auditlog.service.service;

import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.domain.RedactionOverlay;
import com.auditlog.service.repository.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RedactionReconciliationService {
    private final AuditEventRepository repository;

    public RedactionReconciliationService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public List<String> reconcile(String eventId) {
        List<String> issues = new ArrayList<>();
        AuditEvent targetEvent = repository.findById(eventId).orElse(null);
        if (targetEvent == null) {
            return List.of("Event not found");
        }
        List<RedactionOverlay> overlays = repository.findActiveOverlaysForEventId(eventId);
        for (RedactionOverlay overlay : overlays) {
            if (overlay.getEventId() == null || !overlay.getEventId().equals(eventId)) {
                issues.add("Overlay references unexpected event");
            }
            if (overlay.getCertificateEventId() == null || overlay.getCertificateEventId().isBlank()) {
                issues.add("Overlay has no certificate_event_id");
            } else {
                AuditEvent certificate = repository.findById(overlay.getCertificateEventId()).orElse(null);
                if (certificate == null) {
                    issues.add("Certificate event missing: " + overlay.getCertificateEventId());
                } else if (!"REDACTION_APPLIED".equals(certificate.getEventType())) {
                    issues.add("Certificate event has unexpected type: " + certificate.getEventType());
                }
            }
        }
        return issues;
    }
}
