package com.auditlog.service.service;

import com.auditlog.service.api.dto.RedactionRequest;
import com.auditlog.service.api.dto.RedactionResponse;
import com.auditlog.service.config.RedactionProperties;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RedactionServiceUnitTest {
    @Mock
    private AuditEventRepository repository;
    @Mock
    private AuditEventService auditEventService;

    private RedactionService redactionService;
    private RedactionProperties redactionProperties;
    private ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        redactionProperties = new RedactionProperties();
        redactionProperties.setAllowList("/ipAddress,/device/id,/accountNumber");
        redactionProperties.setRequireApproval(false);
        redactionService = new RedactionService(repository, auditEventService, redactionProperties, Clock.systemUTC());
    }

    @Test
    void testNormalizationAndEscapedPointersAreCanonicalized() {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode device = payload.putObject("device");
        device.put("id", "abc");
        
        AuditEvent event = buildEvent("event-1", payload);
        when(repository.findById("event-1")).thenReturn(Optional.of(event));
        when(repository.findActiveOverlaysForEventId("event-1")).thenReturn(List.of());
        when(auditEventService.createEvent(any())).thenReturn(buildEvent("cert-1", objectMapper.createObjectNode()));

        RedactionRequest request = new RedactionRequest();
        request.setJsonPointers(List.of("/device/id"));
        request.setReasonCode("PRIVACY_REQUEST");
        request.setRequestedBy("privacy-user");

        RedactionResponse response = redactionService.applyRedaction("event-1", request);

        assertEquals(List.of("/device/id"), response.appliedPointers());
    }

    @Test
    void testInvalidEscapeSequenceIsRejected() {
        AuditEvent event = buildEvent("event-2", objectMapper.createObjectNode().put("ipAddress", "1.2.3.4"));
        when(repository.findById("event-2")).thenReturn(Optional.of(event));

        RedactionRequest request = new RedactionRequest();
        request.setJsonPointers(List.of("/ipAddress~2"));
        request.setReasonCode("PRIVACY_REQUEST");
        request.setRequestedBy("privacy-user");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> redactionService.applyRedaction("event-2", request));
        assertTrue(ex.getMessage().contains("Invalid escape sequence"));
    }

    @Test
    void testMissingPathIsRejected() {
        AuditEvent event = buildEvent("event-3", objectMapper.createObjectNode().put("ipAddress", "1.2.3.4"));
        when(repository.findById("event-3")).thenReturn(Optional.of(event));

        RedactionRequest request = new RedactionRequest();
        request.setJsonPointers(List.of("/device/id"));
        request.setReasonCode("PRIVACY_REQUEST");
        request.setRequestedBy("privacy-user");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> redactionService.applyRedaction("event-3", request));
        assertTrue(ex.getMessage().contains("does not exist"));
    }

    @Test
    void testAllowListRejectionIsApplied() {
        AuditEvent event = buildEvent("event-4", objectMapper.createObjectNode().put("forbiddenField", "secret-value"));
        when(repository.findById("event-4")).thenReturn(Optional.of(event));

        RedactionRequest request = new RedactionRequest();
        request.setJsonPointers(List.of("/forbiddenField"));
        request.setReasonCode("PRIVACY_REQUEST");
        request.setRequestedBy("privacy-user");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> redactionService.applyRedaction("event-4", request));
        assertTrue(ex.getMessage().contains("not allowed"));
    }

    @Test
    void testMaskingDoesNotMutateTheOriginalPayload() {
        ObjectNode original = objectMapper.createObjectNode();
        original.putObject("device").put("id", "abc");
        original.putObject("profile").put("name", "Alice");
        AuditEvent event = buildEvent("event-5", original);

        RedactionViewService viewService = new RedactionViewService(repository, redactionProperties);
        when(repository.findActiveOverlaysForEventIds(List.of("event-5"))).thenReturn(List.of(
            new com.auditlog.service.domain.RedactionOverlay(
                "overlay-1",
                "event-5",
                5,
                "/device/id",
                "MASK",
                "PRIVACY_REQUEST",
                "",
                "privacy-user",
                "",
                "2026-08-10T00:00:00Z",
                "cert-1",
                true
            )
        ));

        var response = viewService.maskEvents(List.of(event)).get(0);

        assertEquals("abc", original.get("device").get("id").asText());
        assertEquals("[REDACTED]", response.payload().get("device").get("id").asText());
        assertTrue(response.redacted());
        assertEquals(List.of("/device/id"), response.redactedPointers());
    }

    private AuditEvent buildEvent(String id, ObjectNode payload) {
        return new AuditEvent(
            id,
            1,
            "USER_LOGIN",
            "user",
            "ACCOUNT",
            "acc-1",
            payload,
            "2026-08-10T00:00:00Z",
            "2026-08-10T00:00:01Z",
            "hash",
            "prev",
            "chain",
            "v1"
        );
    }
}
