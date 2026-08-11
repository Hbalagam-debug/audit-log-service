# Architecture Decision Records — Audit Log Service

## ADR-001: Java 17 & Spring Boot Framework Selection

Status: Accepted

## ADR-002: SQLite-First with PostgreSQL Migration Readiness

Status: Accepted

## ADR-003: Spring Data JDBC vs. ORM (Hibernate/JPA)

Status: Accepted

## ADR-004: One Globally Ordered Hash Chain with Server-Controlled Ordering

Status: Accepted

## ADR-005: SHA-256 for Hashing and Verification

Status: Accepted

## ADR-006: Canonical JSON Serialization for Deterministic Hashing

Status: Accepted

## ADR-007: Cursor-Based Pagination (Not Offset-Based)

Status: Accepted

## ADR-008: API Immutability (No Update/Delete Operations)

Status: Accepted

## ADR-009: Transaction & Concurrency Control (Application-Level Lock for SQLite, Database Serialization for PostgreSQL)

Status: Accepted

## ADR-010: Hash Algorithm & Canonicalization Versioning

Status: Accepted

## ADR-011: Scenario B Retention, Redaction, and Export Design

Status: Proposed
Human sign-off required: Yes

### Context

Scenario B requires retention, structured redaction, and verifiable export behavior while preserving an immutable audit chain.

### Decision

The prototype will use the following design:

- Retention: same-table soft archival with no physical deletion.
- Redaction: hybrid legacy overlay plus encryption-at-write/key destruction for new records.
- Export: canonical JSON bundle plus Ed25519 signature.

### Consequences

- The prototype preserves tamper-evident history while supporting archival and redaction semantics.
- Legacy records remain accessible through presentation masking, while new records gain stronger confidentiality controls.
- Exports provide integrity and signer authenticity evidence, while explicitly avoiding a claim of absolute completeness for filtered global-chain subsets.

### Approval

This ADR remains Proposed until human review and sign-off are complete.

## ADR-012: Scenario C Compliance Access Reporting — Single Hybrid Architecture

Status: Proposed
Human sign-off required: Yes

### Context

Scenario C introduces an ambiguous requirement: regulators must be able to audit access to client account data. The existing system already provides immutable hash-chain events, retention/archival handling, redaction/encryption presentation controls, and signed bundle export/verification.

### Selected Decision

The prototype implements a **single cohesive hybrid architecture**:

1. **Direct, read-only queries** over existing immutable `audit_events` for interactive compliance reporting.
   - No separate compliance read model, materialized view, or external warehouse.
   - Reuse Scenario B archival filtering, masking, and redaction behavior.

2. **Reuse Scenario B Ed25519 signed bulk-export infrastructure** for downloadable regulatory bundles.
   - Same filtering logic as interactive queries.
   - Same signing, verification, and offline delivery capability.
   - Explicit certificate-event flow with safe metadata only.

### API Contracts

**GET /audit/compliance/access-report** (Interactive unsigned JSON)
- Exactly one selector: `accountId` or `resourceId`
- Required: `from`, `to` (UTC, bounded)
- Optional: `actorId`, `action`, `outcome`, `includeArchived`, `cursor`, `limit`
- Response: unsigned JSON with cursor pagination
- Excludes `COMPLIANCE_REPORT_GENERATED` certificate events

**POST /audit/compliance/access-report/bundle** (Signed regulatory report)
- Same selectors and filters as GET
- Required: `approvalRef` (compliance approval metadata)
- Response: canonical JSON bundle (Scenario B format) signed with Ed25519
- Appends `COMPLIANCE_REPORT_GENERATED` certificate with safe metadata only
- Signature proves integrity and signer authenticity; does not claim completeness

### Alternatives Rejected

1. **Dedicated compliance read model:** Data duplication and projection drift risk; insufficient interview-value gain.
2. **External compliance warehouse:** Operational complexity and cross-system consistency challenges; unjustified for prototype scope.
3. **Signed-export-only approach:** Compliance operators need lightweight, cursor-paginated interactive queries; requiring signing for every query is operationally heavy.

### Certificate-Event Flow

For a successful signed report request:

1. Query and filter `audit_events` using request parameters.
2. Build canonical JSON bundle and compute deterministic SHA-256 digest (reuse `CanonicalHashService`).
3. Sign the digest with Ed25519 (reuse signing infrastructure).
4. Append `COMPLIANCE_REPORT_GENERATED` certificate before returning success.
5. Store only safe metadata in the certificate: approvalRef, criteriaDigest, bundleDigest, recordCount, generatedAt, signingKeyId.
6. Exclude the certificate event from the bundle it certifies.
7. Do not store account data, plaintext sensitive values, or private keys in the certificate.
8. If signing or certificate persistence fails, return an error; do not return a partial response.

### Consequences

- Fastest path to a coherent prototype with minimal schema and operational complexity.
- Strong integrity/authenticity story for regulatory bundles through existing digest/signature tooling.
- Clear separation of concerns: interactive queries (fast, unsigned) vs. regulatory bundles (signed, verifiable).
- Completeness guarantees remain bounded by selected filters and available in-system data.
- Potential scale/performance limits for large compliance workloads remain a known deferred concern.
- Signatures explicitly do **not** claim absolute completeness; anchored proofs deferred to production.

### Approval

This ADR remains Proposed until human review and sign-off are complete.
