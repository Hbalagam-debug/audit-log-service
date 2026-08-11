package com.auditlog.service.service;

import com.auditlog.service.api.dto.AuditEventCreateRequest;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("AuditEventService - Write and Validation Logic")
class AuditEventServiceTest {
    @Mock
    private AuditEventRepository repository;
    
    private AuditEventService service;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AuditEventService(repository, new CanonicalHashService());
        when(repository.findMaxChainPosition()).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Rejects null eventType")
    void testRejectNullEventType() {
        AuditEventCreateRequest request = new AuditEventCreateRequest();
        request.setEventType(null);
        request.setActorId("user-123");
        request.setResourceType("ACCOUNT");
        request.setResourceId("account-456");
        request.setPayload(objectMapper.createObjectNode().put("key", "value"));

        assertThrows(
            IllegalArgumentException.class,
            () -> service.createEvent(request),
            "Null eventType should be rejected"
        );
    }

    @Test
    @DisplayName("Rejects eventType exceeding 128 characters")
    void testRejectEventTypeExceedingMaxLength() {
        AuditEventCreateRequest request = new AuditEventCreateRequest();
        request.setEventType("A".repeat(129));
        request.setActorId("user-123");
        request.setResourceType("ACCOUNT");
        request.setResourceId("account-456");
        request.setPayload(objectMapper.createObjectNode().put("key", "value"));

        assertThrows(
            IllegalArgumentException.class,
            () -> service.createEvent(request),
            "EventType exceeding 128 chars should be rejected"
        );
    }

    @Test
    @DisplayName("Rejects eventType with invalid characters")
    void testRejectEventTypeWithInvalidChars() {
        AuditEventCreateRequest request = new AuditEventCreateRequest();
        request.setEventType("INVALID-EVENT");
        request.setActorId("user-123");
        request.setResourceType("ACCOUNT");
        request.setResourceId("account-456");
        request.setPayload(objectMapper.createObjectNode().put("key", "value"));

        assertThrows(
            IllegalArgumentException.class,
            () -> service.createEvent(request),
            "EventType with special characters should be rejected"
        );
    }

    @Test
    @DisplayName("Rejects payload that is not a JSON object")
    void testRejectNonObjectPayload() {
        AuditEventCreateRequest request = new AuditEventCreateRequest();
        request.setEventType("TEST_EVENT");
        request.setActorId("user-123");
        request.setResourceType("ACCOUNT");
        request.setResourceId("account-456");
        request.setPayload(objectMapper.getNodeFactory().arrayNode().add("item"));

        assertThrows(
            IllegalArgumentException.class,
            () -> service.createEvent(request),
            "Non-object payload should be rejected"
        );
    }

    @Test
    @DisplayName("Accepts valid audit event creation request")
    void testAcceptsValidRequest() {
        AuditEventCreateRequest request = new AuditEventCreateRequest();
        request.setEventType("USER_LOGIN");
        request.setActorId("user-123");
        request.setResourceType("ACCOUNT");
        request.setResourceId("account-456");
        request.setPayload(objectMapper.createObjectNode()
            .put("result", "SUCCESS")
            .put("ipAddress", "192.0.2.1")
        );

        AuditEvent result = service.createEvent(request);
        
        assertNotNull(result);
        assertNotNull(result.getId());
        assertEquals(1, result.getChainPosition());
        assertEquals("USER_LOGIN", result.getEventType());
    }
}
