package com.auditlog.service.integration;

import com.auditlog.service.api.ApiExceptionHandler;
import com.auditlog.service.api.AuditEventController;
import com.auditlog.service.api.dto.QueryResponse;
import com.auditlog.service.api.dto.RetentionRunRequest;
import com.auditlog.service.api.dto.RetentionRunResponse;
import com.auditlog.service.api.dto.VerificationResultResponse;
import com.auditlog.service.config.ExportProperties;
import com.auditlog.service.config.RedactionProperties;
import com.auditlog.service.config.RetentionProperties;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.repository.AuditEventRepository;
import com.auditlog.service.service.AuditEventService;
import com.auditlog.service.service.CanonicalHashService;
import com.auditlog.service.service.ChainVerificationService;
import com.auditlog.service.service.ExportService;
import com.auditlog.service.service.QueryService;
import com.auditlog.service.service.RedactionService;
import com.auditlog.service.service.RedactionViewService;
import com.auditlog.service.service.RetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("RetentionControllerIntegrationTest - retention checkpoint")
class RetentionControllerIntegrationTest {
    private JdbcTemplate jdbcTemplate;
    private AuditEventService auditEventService;
    private AuditEventRepository auditEventRepository;
    private CanonicalHashService canonicalHashService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Clock fixedClock = new FixedClock(Instant.parse("2026-08-10T00:00:00Z"));

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = Path.of("target", "retention-controller-test.db");
        Files.createDirectories(dbPath.getParent());
        Files.deleteIfExists(dbPath);

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        populator.execute(dataSource);

        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.canonicalHashService = new CanonicalHashService();
        this.auditEventRepository = new AuditEventRepository(jdbcTemplate);
        this.auditEventService = new AuditEventService(auditEventRepository, canonicalHashService, fixedClock);
        RetentionProperties retentionProperties = new RetentionProperties();
        RetentionService retentionService = new RetentionService(
            auditEventRepository,
            auditEventService,
            retentionProperties,
            jdbcTemplate,
            new DataSourceTransactionManager(dataSource),
            fixedClock
        );
        QueryService queryService = new QueryService(auditEventRepository);
        ChainVerificationService verificationService = new ChainVerificationService(auditEventRepository, canonicalHashService);
        RedactionProperties redactionProperties = new RedactionProperties();
        RedactionService redactionService = new RedactionService(auditEventRepository, auditEventService, redactionProperties, fixedClock);
        RedactionViewService redactionViewService = new RedactionViewService(auditEventRepository, redactionProperties);
        ExportService exportService = new ExportService(
            auditEventRepository,
            redactionViewService,
            canonicalHashService,
            new ExportProperties(),
            fixedClock
        );
        AuditEventController controller = new AuditEventController(
            auditEventService,
            queryService,
            verificationService,
            retentionService,
            redactionService,
            redactionViewService,
            exportService
        );

        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
        jdbcTemplate.update("DELETE FROM audit_events");
        jdbcTemplate.update("DELETE FROM retention_runs");
    }

    @Test
    @DisplayName("Dry-run returns candidate count without changing archived state")
    void testDryRunReturnsCandidateCountWithoutChanges() throws Exception {
        String oldId = createTestEvent("OLD_EVENT", "user-1", "ACCOUNT", "acc-1", "2026-03-01T00:00:00Z", "2026-03-01T00:00:00Z");
        String recentId = createTestEvent("RECENT_EVENT", "user-2", "ACCOUNT", "acc-2", "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z");

        RetentionRunRequest request = new RetentionRunRequest();
        request.setRetentionWindowDays(90);
        request.setDryRun(true);
        request.setRequestedBy("ops-user");
        request.setReason("Quarterly retention run");

        MvcResult result = mockMvc.perform(
            post("/audit/retention/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isOk())
        .andReturn();

        RetentionRunResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), RetentionRunResponse.class);
        assertTrue(response.dryRun());
        assertEquals(1, response.candidateCount());
        assertEquals(0, response.archivedCount());
        assertTrue(response.cutoffTimestamp().startsWith("2026-05"));

        Boolean oldArchived = jdbcTemplate.queryForObject("SELECT is_archived FROM audit_events WHERE id = ?", Boolean.class, oldId);
        Boolean recentArchived = jdbcTemplate.queryForObject("SELECT is_archived FROM audit_events WHERE id = ?", Boolean.class, recentId);
        assertFalse(oldArchived);
        assertFalse(recentArchived);
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM retention_runs", Integer.class));
    }

    @Test
    @DisplayName("Applied run archives only eligible old rows and appends a certificate event")
    void testAppliedRunArchivesEligibleRowsAndAppendsCertificateEvent() throws Exception {
        String oldId = createTestEvent("OLD_EVENT", "user-1", "ACCOUNT", "acc-1", "2026-03-01T00:00:00Z", "2026-03-01T00:00:00Z");
        String recentId = createTestEvent("RECENT_EVENT", "user-2", "ACCOUNT", "acc-2", "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z");

        RetentionRunRequest request = new RetentionRunRequest();
        request.setRetentionWindowDays(90);
        request.setDryRun(false);
        request.setApprovalRef("CHG-001");
        request.setRequestedBy("ops-user");
        request.setApprovedBy("ops-manager");
        request.setReason("Quarterly retention run");

        MvcResult result = mockMvc.perform(
            post("/audit/retention/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isOk())
        .andReturn();

        RetentionRunResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), RetentionRunResponse.class);
        assertEquals(1, response.archivedCount());
        assertEquals(1, response.candidateCount());

        Boolean oldArchived = jdbcTemplate.queryForObject("SELECT is_archived FROM audit_events WHERE id = ?", Boolean.class, oldId);
        Boolean recentArchived = jdbcTemplate.queryForObject("SELECT is_archived FROM audit_events WHERE id = ?", Boolean.class, recentId);
        assertTrue(oldArchived);
        assertFalse(recentArchived);
        assertEquals("RETENTION_RUN_EXECUTED", jdbcTemplate.queryForObject("SELECT event_type FROM audit_events WHERE retention_run_id IS NOT NULL AND event_type = 'RETENTION_RUN_EXECUTED' ORDER BY chain_position DESC LIMIT 1", String.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_type = 'RETENTION_RUN_EXECUTED'", Integer.class));
    }

    @Test
    @DisplayName("Applied run without approval data returns 400")
    void testApplyWithoutApprovalDataReturns400() throws Exception {
        RetentionRunRequest request = new RetentionRunRequest();
        request.setRetentionWindowDays(90);
        request.setDryRun(false);
        request.setRequestedBy("ops-user");

        mockMvc.perform(
            post("/audit/retention/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("approvalRef is required for applied runs"));
    }

    @Test
    @DisplayName("GET /audit/events excludes archived rows by default and includes them with includeArchived=true")
    void testQueryExcludesArchivedByDefaultAndIncludesWhenRequested() throws Exception {
        String oldId = createTestEvent("OLD_EVENT", "user-1", "ACCOUNT", "acc-1", "2026-03-01T00:00:00Z", "2026-03-01T00:00:00Z");
        createTestEvent("RECENT_EVENT", "user-2", "ACCOUNT", "acc-2", "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z");

        applyRetentionRun();

        MvcResult defaultResult = mockMvc.perform(get("/audit/events").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        QueryResponse defaultResponse = objectMapper.readValue(defaultResult.getResponse().getContentAsString(), QueryResponse.class);
        assertEquals(2, defaultResponse.items().size());
        assertFalse(defaultResponse.items().stream().anyMatch(item -> oldId.equals(item.id())));
        assertTrue(defaultResponse.items().stream().anyMatch(item -> "RECENT_EVENT".equals(item.eventType())));
        assertTrue(defaultResponse.items().stream().anyMatch(item -> "RETENTION_RUN_EXECUTED".equals(item.eventType())));

        MvcResult includeArchivedResult = mockMvc.perform(get("/audit/events").param("includeArchived", "true").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        QueryResponse includeArchivedResponse = objectMapper.readValue(includeArchivedResult.getResponse().getContentAsString(), QueryResponse.class);
        assertEquals(3, includeArchivedResponse.items().size());
        assertTrue(includeArchivedResponse.items().stream().anyMatch(item -> oldId.equals(item.id())));
    }

    @Test
    @DisplayName("Existing filtering and cursor pagination continue to work")
    void testFilteringAndCursorPaginationStillWork() throws Exception {
        createTestEvent("EVENT_1", "user-123", "ACCOUNT", "acc-1", "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z");
        createTestEvent("EVENT_2", "user-123", "ACCOUNT", "acc-2", "2026-08-02T00:00:00Z", "2026-08-02T00:00:00Z");

        MvcResult firstPage = mockMvc.perform(get("/audit/events").param("actorId", "user-123").param("limit", "1").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        QueryResponse pageOne = objectMapper.readValue(firstPage.getResponse().getContentAsString(), QueryResponse.class);
        assertEquals(1, pageOne.items().size());
        assertNotNull(pageOne.nextCursor());

        MvcResult secondPage = mockMvc.perform(get("/audit/events").param("actorId", "user-123").param("limit", "1").param("cursor", pageOne.nextCursor()).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        QueryResponse pageTwo = objectMapper.readValue(secondPage.getResponse().getContentAsString(), QueryResponse.class);
        assertEquals(1, pageTwo.items().size());
        assertFalse(pageTwo.items().get(0).id().equals(pageOne.items().get(0).id()));
    }

    @Test
    @DisplayName("GET /audit/verify stays intact after archival and detects archived-row tampering")
    void testVerifyRemainsIntactAndDetectsTamperingAfterArchival() throws Exception {
        String oldId = createTestEvent("OLD_EVENT", "user-1", "ACCOUNT", "acc-1", "2026-03-01T00:00:00Z", "2026-03-01T00:00:00Z");
        createTestEvent("RECENT_EVENT", "user-2", "ACCOUNT", "acc-2", "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z");

        applyRetentionRun();

        MvcResult verifyResult = mockMvc.perform(get("/audit/verify").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        VerificationResultResponse verifyResponse = objectMapper.readValue(verifyResult.getResponse().getContentAsString(), VerificationResultResponse.class);
        assertTrue(verifyResponse.intact());

        jdbcTemplate.update("UPDATE audit_events SET content_hash = ? WHERE id = ?", "tampered", oldId);

        MvcResult tamperedResult = mockMvc.perform(get("/audit/verify").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        VerificationResultResponse tamperedResponse = objectMapper.readValue(tamperedResult.getResponse().getContentAsString(), VerificationResultResponse.class);
        assertFalse(tamperedResponse.intact());
    }

    private void applyRetentionRun() throws Exception {
        RetentionRunRequest request = new RetentionRunRequest();
        request.setRetentionWindowDays(90);
        request.setDryRun(false);
        request.setApprovalRef("CHG-001");
        request.setRequestedBy("ops-user");
        request.setApprovedBy("ops-manager");
        request.setReason("Quarterly retention run");

        mockMvc.perform(
            post("/audit/retention/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isOk());
    }

    private String createTestEvent(String eventType, String actorId, String resourceType, String resourceId, String eventTimestamp, String ingestedAt) {
        JsonNode payload = objectMapper.createObjectNode().put("data", eventType);
        String contentHash = canonicalHashService.computeContentHash(
            eventType,
            actorId,
            resourceType,
            resourceId,
            payload,
            eventTimestamp
        );
        long chainPosition = auditEventRepository.findMaxChainPosition().orElse(0L) + 1;
        String previousHash = chainPosition == 1
            ? canonicalHashService.getGenesisHash()
            : auditEventRepository.findByChainPosition(chainPosition - 1)
                .orElseThrow(() -> new IllegalStateException("Previous record not found"))
                .getChainHash();
        String chainHash = canonicalHashService.computeChainHash(chainPosition, previousHash, contentHash);

        AuditEvent event = new AuditEvent(
            java.util.UUID.randomUUID().toString(),
            chainPosition,
            eventType,
            actorId,
            resourceType,
            resourceId,
            payload,
            eventTimestamp,
            ingestedAt,
            contentHash,
            previousHash,
            chainHash,
            canonicalHashService.getHashVersion()
        );
        auditEventRepository.insert(event);
        return event.getId();
    }

    private static class FixedClock extends Clock {
        private final Instant instant;

        private FixedClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
