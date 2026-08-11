# Scenario B Design (Step 4) — Retention, Structured Redaction, Verifiable Bulk Export

Status: Partially implemented
Human sign-off required: Yes
Implementation scope: Checkpoints A-E are now implemented in the prototype codebase; final integration/documentation cleanup remains pending.

## Final recommendation for the prototype

- Retention: same-table soft archival.
- Redaction: hybrid legacy overlay plus encryption-at-write/key destruction for new records.
- Export: canonical JSON bundle plus Ed25519 signature.

## Alternatives considered but rejected

1. Physical deletion for retention
   - Rejected because the prototype must preserve the historical audit chain and allow explicit archival semantics without destructive deletion.

2. Overlay-only redaction for all records
   - Rejected because it does not protect new records written after the design freeze and it does not meet the requirement for structured, write-time confidentiality.

3. Rewriting historical rows or rehashing the existing chain for redaction
   - Rejected because it would break tamper-evident history and violate the requirement that historical audit rows remain immutable.

4. Exporting unsigned or non-canonical bundles
   - Rejected because the prototype must provide verifiable integrity and signer authenticity for exported bundles.

## Selected approaches to implement

- Retention uses same-table soft archival with explicit archive metadata and a certificate event.
- Legacy Scenario A records use a read-time masking overlay plus a REDACTION_APPLIED certificate event.
- New Scenario B records encrypt configured sensitive JSON-pointer fields before storage and destroy the applicable data key when redaction occurs.
- Export uses a canonical JSON bundle, deterministic SHA-256 digest, and an Ed25519 signature with public-key verification.

## 1. Retention

### 1.1 Prototype behavior

- Retain all audit chain records in the same table.
- Never physically delete audit chain records in this prototype.
- Default queries exclude archived records.
- An explicit includeArchived=true option includes archived records.
- GET /audit/verify always includes archived records.
- Archival state is tracked as metadata outside the historical content hash.
- RETENTION_RUN_EXECUTED must be appended as an audit event.
- The archive metadata must be reconciled with the retention certificate event so the archival decision is traceable.

### 1.2 Retention design note

The same-table soft archival model keeps the chain continuous while allowing the service to present a non-archived view by default. This preserves audit history and avoids the ambiguity that would arise from deleting rows or rewriting the chain.

## 2. Redaction

### 2.1 Legacy Scenario A records

Legacy Scenario A records use a presentation masking overlay at read time.

- The service applies a masking overlay to configured sensitive fields before presentation.
- A REDACTION_APPLIED certificate event is appended for the affected event.
- The historical audit row is never rewritten and never rehashed.
- The original plaintext remains recoverable from direct database access, backups, replicas, and similar operational copies.
- The design explicitly calls this presentation masking rather than cryptographic erasure.

### 2.2 New Scenario B records

New Scenario B records use encryption-at-write rather than overlay-only redaction.

- Configured sensitive fields are identified by JSON pointers and encrypted before payload_json is stored.
- Encryption uses envelope encryption with a unique data-encryption key per event or sensitive-field group.
- The stored representation includes ciphertext and non-secret encryption metadata.
- The contentHash is calculated over the stored ciphertext representation, not over plaintext values.
- The service uses a configurable key-provider abstraction for protecting data keys.
- Redaction destroys or disables the applicable data key.
- Ciphertext, contentHash, previousHash, and chainHash remain unchanged when redaction is applied.
- A REDACTION_APPLIED event is appended with the target event ID, JSON pointers, reasonCode, approvalRef, and non-sensitive key-destruction evidence.
- Plaintext values, encryption keys, and unsalted sensitive-value hashes are never placed in the certificate event.
- Query and export responses render inaccessible values as [REDACTED].

Implementation status for this checkpoint:

- Checkpoint C is implemented for the local prototype.
- Configured payload pointers are encrypted before `payload_json` is stored.
- The stored `contentHash` and `chainHash` continue to cover the encrypted representation.
- Redaction for encrypted pointers destroys local wrapped-DEK access and appends a `REDACTION_APPLIED` certificate event without rewriting historical payload rows.
- Legacy plaintext records still rely on presentation masking from Checkpoint B and are not migrated in this checkpoint.
- Checkpoint D is now implemented for deterministic export bundles and offline digest verification.
- Checkpoint E is now implemented for Ed25519 signing, trusted keyId/public-key verification, and offline signature verification.

### 2.3 Local prototype key management

- Provide a test/local key-provider design configured outside source control.
- Never commit real encryption or signing private keys.
- Production would use KMS or HSM-backed key management.
- The prototype must document the risk that backups, logs, caches, replicas, and a compromised key-management system can still expose data if the key material is available there.

## 3. Bulk export

### 3.1 Export format

- Export uses a canonical JSON bundle.
- The bundle includes a deterministic SHA-256 digest.
- The bundle is signed with Ed25519 asymmetric signature material.
- The signature covers the 32 raw bytes obtained by decoding the hexadecimal `bundleDigest`.
- The signature block is excluded from `bundleDigest` input so digesting and signing are not circular.
- Export metadata includes `keyId`, the Base64 signature value, and the Base64 X.509 public key for the prototype bundle.
- Verification uses the trusted public key corresponding to the signing key and rejects unexpected `keyId` bindings.
- The export manifest includes first/last exported chain positions, the first previousHash, the last chainHash, and a sorted record-ID/position digest.

### 3.2 What the export proves

The export proves bundle integrity and signer authenticity. It does not prove absolute completeness of a filtered global-chain subset. This distinction is important for the prototype and must be documented explicitly.

## 4. Proposed database model (design only)

### 4.1 audit_events additions

- payload_json stores the stored representation for the event, including ciphertext for sensitive fields in new-record redaction cases.
- encryption_key_ref for the data-encryption key used by the event or field group.
- encryption_algorithm_version to record the encryption algorithm and version used for the stored representation.
- encrypted_json_pointer_metadata to record which JSON pointers were encrypted and how the encrypted fields were grouped.
- key_status or equivalent key-destruction evidence to show whether the key remains active or has been destroyed/disabled.
- key_destruction_evidence as non-sensitive evidence of key destruction or disablement.
- No plaintext key material is stored in the audit row or certificate event.

### 4.2 audit_event_redactions additions

- target_event_id
- json_pointers
- reason_code
- approval_ref
- key_destruction_evidence
- certificate_event_id or equivalent reference to the appended REDACTION_APPLIED event

### 4.3 retention_runs and export_audit additions

- retention_runs remains responsible for retention execution metadata and certificate-event linkage.
- export_audit remains responsible for export manifests, digests, signatures, and signer metadata.

## 5. Implementation order (independent checkpoints)

A. Retention implementation and tests
- Implement same-table archival behavior, archive metadata, default exclusion, includeArchived behavior, and retention certificate events.

B. Legacy redaction overlay and certificate tests
- Implement presentation masking for legacy records, append REDACTION_APPLIED certificate events, and verify that historical rows remain unchanged.

C. New-record sensitive-field encryption and key-destruction tests
- Implement JSON-pointer-based encryption-at-write, envelope encryption metadata, key-provider abstraction, redaction key destruction/disablement, and [REDACTED] rendering.

D. Export manifest/digest implementation
- Implement canonical JSON bundle generation, deterministic SHA-256 digest computation, offline verifier support, and manifest content for chain positions and hash evidence.

E. Export signature implementation
- Implement Ed25519 signing, signature metadata, signingKeyId, verification, and public-key validation.
- Status: Implemented in code on 2026-08-11 with PKCS#8 private-key loading, X.509 public-key loading, and trusted keyId verification.

F. Complete integration tests and documentation
- Validate the end-to-end retention, redaction, and export behavior and update documentation to reflect the approved prototype design.

## 6. Prototype limitations

- Backups, replicas, logs, caches, and operational snapshots may still contain plaintext data unless they are also protected or rotated.
- A compromised key-management system can undermine confidentiality for all records protected by the affected keys.
- Presentation masking is not cryptographic erasure.
- The export format proves integrity and authenticity, not absolute completeness for a filtered global-chain subset.
- Local testing uses a non-production key-provider outside source control; it is not a substitute for production key management.

## 7. Deferred production features

- HSM-backed key storage and key lifecycle management
- External KMS integration and key rotation policies
- Multi-region or multi-tenant key separation
- Full key-use auditing and access control
- Advanced backup/replica redaction and purge workflows

## 8. Human approval status

- Status: Partially implemented in code through Checkpoint E; broader Scenario B review still required
- Human sign-off remains required before treating the remaining export work as complete
- This document remains the design baseline for the unfinished Scenario B checkpoints

## 9. Summary for the human review

The approved prototype architecture is intentionally conservative:

- Retention uses same-table soft archival.
- Redaction uses a hybrid model: presentation masking for legacy records and encryption-at-write with key destruction for new records.
- Export uses a canonical JSON bundle plus Ed25519 signature.
