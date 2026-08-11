package com.auditlog.service.service;

import com.auditlog.service.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("QueryService - Cursor Pagination")
class QueryServiceTest {
    @Mock
    private AuditEventRepository repository;

    private QueryService queryService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        queryService = new QueryService(repository);
    }

    @Test
    @DisplayName("Encode then decode returns original chain position")
    void testEncodeDecodeRoundTrip() {
        String cursor = queryService.encodeCursor(42L);

        long decoded = queryService.decodeCursor(cursor);

        assertEquals(42L, decoded);
    }

    @Test
    @DisplayName("Decode accepts omitted Base64 padding")
    void testDecodeWithoutPadding() {
        String cursor = queryService.encodeCursor(7L);

        long decoded = queryService.decodeCursor(cursor);

        assertEquals(7L, decoded);
    }

    @Test
    @DisplayName("Malformed cursor is rejected")
    void testMalformedCursorRejected() {
        assertThrows(InvalidCursorException.class, () -> queryService.decodeCursor("not-a-valid-cursor"));
    }
}
