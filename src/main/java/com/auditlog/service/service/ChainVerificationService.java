package com.auditlog.service.service;

import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.domain.ChainVerificationResult;
import com.auditlog.service.domain.ViolationType;
import com.auditlog.service.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ChainVerificationService {
    private final AuditEventRepository repository;
    private final CanonicalHashService hashService;

    public ChainVerificationService(AuditEventRepository repository, CanonicalHashService hashService) {
        this.repository = repository;
        this.hashService = hashService;
    }

    public ChainVerificationResult verify() {
        List<AuditEvent> allEvents = repository.findAllOrderedByPosition();

        if (allEvents.isEmpty()) {
            return new ChainVerificationResult(
                true,
                0,
                null,
                null,
                null,
                "No records in the chain"
            );
        }

        int recordsChecked = 0;

        for (int i = 0; i < allEvents.size(); i++) {
            AuditEvent event = allEvents.get(i);
            recordsChecked++;

            if (i == 0) {
                if (event.getChainPosition() != 1) {
                    return new ChainVerificationResult(
                        false,
                        recordsChecked,
                        event.getId(),
                        event.getChainPosition(),
                        ViolationType.POSITION_GAP,
                        "First record chain position is not 1"
                    );
                }
                if (!event.getPreviousHash().equals(hashService.getGenesisHash())) {
                    return new ChainVerificationResult(
                        false,
                        recordsChecked,
                        event.getId(),
                        event.getChainPosition(),
                        ViolationType.INVALID_GENESIS_REFERENCE,
                        "Genesis reference mismatch"
                    );
                }
            } else {
                AuditEvent previousEvent = allEvents.get(i - 1);
                
                if (event.getChainPosition() != previousEvent.getChainPosition() + 1) {
                    return new ChainVerificationResult(
                        false,
                        recordsChecked,
                        event.getId(),
                        event.getChainPosition(),
                        ViolationType.POSITION_GAP,
                        "Position gap detected"
                    );
                }

                if (!event.getPreviousHash().equals(previousEvent.getChainHash())) {
                    return new ChainVerificationResult(
                        false,
                        recordsChecked,
                        event.getId(),
                        event.getChainPosition(),
                        ViolationType.PREVIOUS_HASH_MISMATCH,
                        "Previous hash mismatch"
                    );
                }
            }

            String recalculatedContentHash = hashService.computeContentHash(
                event.getEventType(),
                event.getActorId(),
                event.getResourceType(),
                event.getResourceId(),
                event.getPayload(),
                event.getTimestamp()
            );

            if (!recalculatedContentHash.equals(event.getContentHash())) {
                return new ChainVerificationResult(
                    false,
                    recordsChecked,
                    event.getId(),
                    event.getChainPosition(),
                    ViolationType.CONTENT_HASH_MISMATCH,
                    "Content hash mismatch"
                );
            }

            String recalculatedChainHash = hashService.computeChainHash(
                event.getChainPosition(),
                event.getPreviousHash(),
                event.getContentHash()
            );

            if (!recalculatedChainHash.equals(event.getChainHash())) {
                return new ChainVerificationResult(
                    false,
                    recordsChecked,
                    event.getId(),
                    event.getChainPosition(),
                    ViolationType.CHAIN_HASH_MISMATCH,
                    "Chain hash mismatch"
                );
            }
        }

        return new ChainVerificationResult(
            true,
            recordsChecked,
            null,
            null,
            null,
            "Audit chain is intact"
        );
    }
}
