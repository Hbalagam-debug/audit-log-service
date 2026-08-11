package com.auditlog.service.service;

import com.auditlog.service.api.dto.AuditEventCreateRequest;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.domain.AuditEventEncryptionKey;
import com.auditlog.service.repository.AuditEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class AuditEventService {
    private static final long MAX_PAYLOAD_SIZE = 1_000_000L;
    private final AuditEventRepository repository;
    private final CanonicalHashService hashService;
    private final Clock clock;
    private final PayloadEncryptionService payloadEncryptionService;
    private final Object writeLock = new Object();

    public AuditEventService(AuditEventRepository repository, CanonicalHashService hashService) {
        this(repository, hashService, Clock.systemUTC(), PayloadEncryptionService.disabled());
    }

    public AuditEventService(AuditEventRepository repository, CanonicalHashService hashService, Clock clock) {
        this(repository, hashService, clock, PayloadEncryptionService.disabled());
    }

    @Autowired
    public AuditEventService(
        AuditEventRepository repository,
        CanonicalHashService hashService,
        Clock clock,
        PayloadEncryptionService payloadEncryptionService
    ) {
        this.repository = repository;
        this.hashService = hashService;
        this.clock = clock;
        this.payloadEncryptionService = payloadEncryptionService;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AuditEvent createEvent(AuditEventCreateRequest request) {
        synchronized (writeLock) {
            validateRequest(request);

            String id = UUID.randomUUID().toString();
            String normalizedTimestamp = TimestampNormalizer.normalizeToUtcString(request.getTimestamp(), clock);
            String ingestedAt = TimestampNormalizer.nowUtcString(clock);
            PayloadEncryptionService.EncryptionWriteResult encryptionWriteResult =
                payloadEncryptionService.encryptPayloadForStorage(id, request.getPayload());

            long chainPosition = repository.findMaxChainPosition().orElse(0L) + 1;
            String previousHash = chainPosition == 1 ? hashService.getGenesisHash() : 
                repository.findByChainPosition(chainPosition - 1)
                    .orElseThrow(() -> new RuntimeException("Previous record not found"))
                    .getChainHash();

            String contentHash = hashService.computeContentHash(
                request.getEventType(),
                request.getActorId(),
                request.getResourceType(),
                request.getResourceId(),
                encryptionWriteResult.payload(),
                normalizedTimestamp
            );

            String chainHash = hashService.computeChainHash(chainPosition, previousHash, contentHash);

            AuditEvent event = new AuditEvent(
                id,
                chainPosition,
                request.getEventType(),
                request.getActorId(),
                request.getResourceType(),
                request.getResourceId(),
                encryptionWriteResult.payload(),
                normalizedTimestamp,
                ingestedAt,
                contentHash,
                previousHash,
                chainHash,
                hashService.getHashVersion()
            );

            repository.insert(event);
            for (AuditEventEncryptionKey keyRecord : encryptionWriteResult.keyRecords()) {
                repository.insertEncryptionKey(keyRecord);
            }
            return event;
        }
    }

    private void validateRequest(AuditEventCreateRequest request) {
        if (request.getEventType() == null || request.getEventType().trim().isEmpty()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (request.getEventType().length() > 128) {
            throw new IllegalArgumentException("eventType must be 1-128 characters");
        }
        if (!request.getEventType().matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("eventType must contain only alphanumeric characters and underscores");
        }

        if (request.getActorId() == null || request.getActorId().trim().isEmpty()) {
            throw new IllegalArgumentException("actorId is required");
        }
        if (request.getActorId().length() > 256) {
            throw new IllegalArgumentException("actorId must be 1-256 characters");
        }

        if (request.getResourceType() == null || request.getResourceType().trim().isEmpty()) {
            throw new IllegalArgumentException("resourceType is required");
        }
        if (request.getResourceType().length() > 128) {
            throw new IllegalArgumentException("resourceType must be 1-128 characters");
        }
        if (!request.getResourceType().matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("resourceType must contain only alphanumeric characters and underscores");
        }

        if (request.getResourceId() == null || request.getResourceId().trim().isEmpty()) {
            throw new IllegalArgumentException("resourceId is required");
        }
        if (request.getResourceId().length() > 512) {
            throw new IllegalArgumentException("resourceId must be 1-512 characters");
        }

        if (request.getPayload() == null) {
            throw new IllegalArgumentException("payload is required");
        }
        if (!request.getPayload().isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }

        String payloadStr = request.getPayload().toString();
        if (payloadStr.getBytes().length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("payload exceeds maximum size of 1 MB");
        }
    }

    public AuditEvent getEventById(String id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Event not found: " + id));
    }
}
