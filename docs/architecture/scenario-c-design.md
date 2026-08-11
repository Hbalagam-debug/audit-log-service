# Scenario C Design — Regulatory Access Reporting

Status: PROPOSED — DESIGN ONLY  
Purpose: Single hybrid architecture for compliance access reporting without implementation changes

## 1. Design context and goals

Scenario A/B already provide:
- immutable, chain-ordered audit events,
- verification (`GET /audit/verify`),
- archival and retention metadata,
- redaction overlay + cryptographic masking behavior,
- signed export bundle with offline verification.

Scenario C adds regulator-oriented access reporting while preserving those guarantees and avoiding completeness overclaims.

## 2. Scope boundary for this design checkpoint

This document defines the selected architecture, API contract, implementation scope, and required flow.  
No production code/schema/tests are changed in this checkpoint.

## 3. Final selected architecture

### 3.1 Hybrid approach: Direct queries + Scenario B signed export reuse

The prototype uses a single cohesive hybrid architecture:

1. **Direct, read-only queries** over existing immutable `audit_events` table for compliance report generation.
2. **Reuse of Scenario B Ed25519 signed bulk-export infrastructure** for downloadable regulatory bundles.
3. **No separate compliance read model, materialized view, or external warehouse** in the prototype.

This hybrid minimizes complexity, maximizes reuse, and provides clear integrity/authenticity guarantees without overstating completeness.

### 3.2 Source of truth

- `audit_events` remains the only source of truth for regulatory reporting.
- No historical records are modified or rehashed.
- No additional event-store duplication.
- Existing chain verification semantics are preserved.

### 3.3 Interactive reporting (GET endpoint)

- Query `audit_events` directly for bounded time ranges.
- Return unsigned JSON with cursor pagination.
- Enforce exactly one selector: `accountId` or `resourceId`.
- Require bounded UTC `from` and `to` timestamps.
- Support optional filters: `actorId`, `action`, `outcome`, `includeArchived`, `cursor`, `limit`.
- **Exclude `COMPLIANCE_REPORT_GENERATED` certificate events from report results** to prevent circular dependencies.
- Reuse Scenario B archival filtering, presentation masking, and destroyed-key redaction.

### 3.4 Regulatory bundle (POST endpoint)

- Accept same filtered source data as the GET endpoint.
- Require `approvalRef` (compliance approval metadata).
- Produce a canonical JSON bundle over filtered results.
- Reuse Scenario B Ed25519 signing and offline verification.
- Signature proves bundle integrity and signer authenticity, **not absolute completeness**.
- Independently anchored checkpoints or inclusion/non-inclusion proofs are deferred to production.

### 3.5 Certificate-event flow

When a signed regulatory report is generated successfully:

1. Query and filter `audit_events` for the bounded report snapshot.
2. Build a canonical JSON bundle and compute deterministic SHA-256 digest (reuse Scenario B `CanonicalHashService`).
3. Sign the digest with Ed25519 (reuse Scenario B signing keys).
4. Append `COMPLIANCE_REPORT_GENERATED` certificate event **before** returning success to the caller.
5. Store only safe metadata in the certificate:
   - `approvalRef` (caller-supplied compliance context)
   - `criteriaDigest` (deterministic hash of query filters)
   - `bundleDigest` (the signed digest)
   - `recordCount` (number of rows in the report)
   - `generatedAt` (timestamp)
   - `signingKeyId` (signing key used)
6. Do **not** include the certificate event in the report that it certifies.
7. Do **not** store account data, plaintext sensitive values, or private keys in the certificate.
8. If signing or certificate persistence fails, do **not** return a successful report.

### 3.6 Event taxonomy

Keep the normalized access-event types for in-scope access actions:

- `CLIENT_ACCOUNT_DATA_VIEWED` — data was successfully retrieved for read/view
- `CLIENT_ACCOUNT_DATA_SEARCHED` — data was successfully filtered or searched
- `CLIENT_ACCOUNT_DATA_EXPORTED` — data was successfully extracted or exported
- `CLIENT_ACCOUNT_DATA_UPDATED` — data was successfully modified
- `CLIENT_ACCOUNT_ACCESS_DENIED` — access attempt was rejected

(Note: `COMPLIANCE_REPORT_GENERATED` is a certificate event only; excluded from client-data-access results.)

## 4. Alternatives considered but rejected

### 4.1 Dedicated compliance read model

**Approach:** Maintain a report-oriented projection table from incoming audit events.

**Rejected because:**
- Data duplication and projection drift risk for a prototype.
- Additional consistency semantics and reconciliation overhead with no proportional interview-value gain.
- More migration/indexing complexity for SQLite prototype.

### 4.2 External compliance warehouse

**Approach:** Stream audit events to an external platform (SIEM/warehouse) and build regulatory reports there.

**Rejected because:**
- Highest operational complexity and integration overhead for a prototype.
- Cross-system consistency/latency and trust-boundary challenges.
- Hard to justify for interview scope.

### 4.3 Signed-export-only approach

**Approach:** Deliver all reports only as signed regulatory bundles; no interactive queries.

**Rejected because:**
- Compliance operators need lightweight, cursor-paginated interactive queries for review and iteration.
- Requiring signing for every query is operationally heavy and delays response time.

## 5. Final API contracts

### 5.1 Interactive query endpoint: GET /audit/compliance/access-report

**Purpose:** Unsigned, cursor-paginated queries for compliance operator review and iteration.

**Query parameters:**
- **required:** exactly one of `accountId` or `resourceId`
- **required:** `from`, `to` (UTC timestamps; `from < to`)
- **optional:** `actorId`, `action`, `outcome`, `includeArchived`, `cursor`, `limit`

**Validation rules:**
- Reject both/neither account selector → 400
- Reject invalid or excessive UTC range → 400
- Reject unsupported enum values → 400
- Reject row-count or time-range exceedance → 413

**Response shape:**
- `selection`: query criteria used
- `recordCount`: number of rows
- `items`: cursor-paginated rows
- `nextCursor`: for pagination
- `hasMore`: pagination indicator
- Unsigned JSON (no signature block)

**Behavior:**
- Return unbounded query results within max limits.
- Exclude `COMPLIANCE_REPORT_GENERATED` certificate events from results.
- Include both successful and denied access outcomes.
- Apply Scenario B archival filtering, masking, and redaction behavior.

### 5.2 Signed regulatory bundle endpoint: POST /audit/compliance/access-report/bundle

**Purpose:** Generate a signed, verifiable regulatory report for archival and regulator submission.

**Request body:**
- **required:** exactly one of `accountId` or `resourceId`
- **required:** `from`, `to` (UTC timestamps)
- **required:** `approvalRef` (compliance approval metadata)
- **optional:** `actorId`, `action`, `outcome`, `includeArchived`, `reasonCode`

**Response shape:**
- Canonical JSON bundle (Scenario B format reuse):
  - `manifest`: first/last chain positions, previousHash, chainHash, recordsDigest
  - `records`: filtered access events
  - `recordsDigest`: SHA-256 over sorted record digests
  - `bundleDigest`: SHA-256 over entire bundle (excludes signature block)
  - `signature`: algorithm, keyId, value (Base64), publicKey (X.509, Base64)

**Behavior:**
- Use identical filtering logic to GET endpoint.
- Exclude `COMPLIANCE_REPORT_GENERATED` certificate events from bundle data.
- Compute canonical bundle digest (reuse Scenario B `CanonicalHashService`).
- Sign digest with Ed25519 (reuse Scenario B signing infrastructure).
- Append `COMPLIANCE_REPORT_GENERATED` certificate event before returning success.
- Certificate stores: approvalRef, criteriaDigest, bundleDigest, recordCount, generatedAt, signingKeyId (no account data or plaintext sensitive values).
- If signing or certificate persistence fails, return an error; do not return a partial response.

**Certificate-event flow:**
1. Query and filter `audit_events` for bounded report snapshot.
2. Build canonical JSON bundle and compute deterministic digest.
3. Sign the digest with Ed25519.
4. Append `COMPLIANCE_REPORT_GENERATED` certificate event.
5. Return bundle with signature to caller.
6. Certificate is **excluded** from the bundle it certifies.

**Error responses:**
- `400` — validation failure (selector, range, enum)
- `401/403` — authentication/authorization (future implementation)
- `413` — size limit exceeded
- `500` — server error; no partial response

### 5.3 Signature semantics (explicit)

The Ed25519 signature proves:
- **Integrity:** Bundle has not been tampered with (any field change detected).
- **Authenticity:** Bundle was signed by the holder of the corresponding private key.

The signature does **NOT** prove:
- **Absolute completeness:** Signature does not guarantee that all possible matching records in the global chain are included; it only proves integrity of the returned subset matching the specified filters.
- **Independent anchoring:** No blockchain or external timestamp authority is involved; completeness guarantees are deferred to production evolution.

## 6. Integrity and compliance behavior design

- Chain verification remains unchanged and source of truth.
- Archived event handling via explicit `includeArchived` semantics; defaults to false.
- Legacy overlay and destroyed-key masking are preserved in report output.
- Signed bundle verifies returned content integrity and origin (Scenario B reuse).
- **`COMPLIANCE_REPORT_GENERATED` certificate events are appended before returning success** and serve as the audit trail of report generation.
- **`COMPLIANCE_REPORT_GENERATED` events are excluded from client-data-access report results** to prevent circular dependencies and maintain focus on actual access events.
- Unauthorized report attempts should become auditable once full authorization is implemented.
- Idempotency is based on deterministic filters and cursor position.
- Concurrency semantics follow existing append-only chain ordering.
- UTC normalization is mandatory for all timestamp ranges.
- Duplicate/retried events remain distinct unless a formal dedupe policy is approved.
- Malformed legacy access events are surfaced with explicit compatibility flags, not silently discarded.

## 7. Authorization and privacy model (explicit prototype limitation)

Required production controls (not fully implemented in prototype):
- Role-based access for compliance operators.
- Least privilege and separation of duties.
- Approval references and audit trails for sensitive report scopes.
- Full authentication/authorization integration.

Prototype limitation:
- AuthN/authZ stack is incomplete; this design documents requirements but does not claim production-grade security.

## 8. Implementation scope

### 8.1 Prototype scope

- Access-event taxonomy validation conventions.
- GET endpoint: compliance report query over immutable `audit_events`.
- POST endpoint: signed regulatory bundle generation with Scenario B reuse.
- Bounded UTC range and selector enforcement.
- Success/denied outcome inclusion.
- Cursor pagination and includeArchived switch.
- Scenario B masking reuse.
- Ed25519 signing reuse.
- Certificate-event flow and exclusion from reports.
- Unit/integration test coverage.

### 8.2 Deferred production features

- Full authentication/authorization platform integration.
- Scheduled report jobs and templates.
- Regulator-specific output templates (CSV packs, XBRL, etc.).
- External warehouse/read-model scaling.
- Independent completeness anchoring (blockchain, timestamp authority).
- Multi-tenant key separation and HSM/KMS hardening.
- High-volume streaming/report pipelines.
- Async report-job scheduling with approval workflows.

## 9. Unresolved Product/Compliance questions (separate from prototype assumptions)

These questions must be answered by Product/Compliance before production implementation:

1. **Legal scope:** What exactly qualifies as "client account data" across business domains (account profile, balances, statements, transaction history, KYC documents)?
2. **Regulatory format:** Is reporting on-demand, scheduled, or both? Are there mandatory CSV/XBRL/template requirements?
3. **Authorization matrix:** What is the formal role/approval matrix for compliance report generation?
4. **Retries and deduplication:** How should retried/duplicate attempts be counted and reported?
5. **Completeness evidence:** Do regulators require anchored completeness proofs beyond signed filtered subsets?
6. **Retention scope:** What retention period applies specifically to access-report rows? Are archived records included by default?
7. **Archive treatment:** How should legacy redaction overlays and cryptographic erasure be represented to regulators?
8. **Time semantics:** What timezone is authoritative (UTC vs. regulator local)? Should filtering use event or ingest timestamp?

---

Status: PROPOSED — DESIGN ONLY  
Production code changed: NO  
Schema changed: NO  
Tests changed: NO  
Human approval required before implementation: YES
