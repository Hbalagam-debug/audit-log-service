# Requirement Analysis — Audit Log Service

**Document Status:** Step 1 — Requirement Normalization and Analysis  
**Last Updated:** TODO (set by engineer)  
**Approved By:** TODO (pending engineer sign-off)

---

## 1. Overview

The audit log service is a tamper-evident event recording system that guarantees an append-only history and detects any modification or deletion of past records. The assignment spans three progressive scenarios with increasing complexity and ambiguity.

This document normalizes high-level requirements into clear engineering specifications, identifies assumptions, documents trade-offs, and establishes acceptance criteria.

---

## 2. Scenario A — Core Audit Log Service

### 2.1 Write API

**Intent:** Accept and durably store immutable event records.

#### Request Schema

The API must accept a JSON request body containing at minimum:

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `eventType` | string | yes | Identifier for the event class (e.g., `USER_LOGIN`, `RECORD_UPDATED`, `PERMISSION_GRANTED`) |
| `actorId` | string | yes | Identifier of the actor or system that caused the event |
| `resourceType` | string | yes | Type classification of the affected resource (e.g., `ACCOUNT`, `USER`, `DOCUMENT`) |
| `resourceId` | string | yes | Unique identifier of the specific resource affected |
| `payload` | JSON object | yes | Event-specific structured data (variable schema per eventType) |
| `timestamp` | ISO 8601 datetime | no | Caller-supplied event time; optional, server assigns if omitted |

#### Timestamp Behavior

**Decision:** The write API accepts a caller-supplied `timestamp` field representing when the event occurred (event time). The server assigns a separate `ingestedAt` timestamp and a `chainPosition` counter.

**Rationale:**
- Event time (caller timestamp) is used for filtering and querying (e.g., "find events between 2 AM and 3 AM")
- Ingestion time and chain position are used for chain ordering and verification
- This separation prevents callers from controlling the chain order by manipulating timestamps
- Protects against: backdating events, future-dating events to disrupt ordering, or using timestamps to inject records at arbitrary positions

**Implementation Constraints:**
- If caller omits `timestamp`, server assigns the event time to current time
- `ingestedAt` is always server-assigned and immutable
- `chainPosition` is a monotonically increasing counter assigned at write time

#### Record Storage & Chain Metadata

Each stored record must include:

| Field | Type | Source | Immutable |
|-------|------|--------|-----------|
| `recordId` | UUID | server | yes |
| `eventType` | string | caller | yes |
| `actorId` | string | caller | yes |
| `resourceType` | string | caller | yes |
| `resourceId` | string | caller | yes |
| `payload` | JSON | caller | yes |
| `timestamp` | ISO 8601 | caller | yes |
| `ingestedAt` | ISO 8601 | server | yes |
| `chainPosition` | integer | server | yes |
| `contentHash` | hex string (SHA-256) | server | yes |
| `previousHash` | hex string (SHA-256) | server | yes |

**Hash Chain Definition:**
- `contentHash` = SHA-256(canonical JSON representation of record content)
- `previousHash` = SHA-256 of the immediately preceding record's contentHash, OR genesis value for the first record
- Genesis hash value = SHA-256("GENESIS") (well-known constant)

#### Append-Only Constraints

The write API must:
- ✅ Accept new records
- ✅ Accept retries (idempotency by recordId or request-id deduplication)
- ❌ NOT expose an update operation
- ❌ NOT expose a delete operation
- ❌ NOT expose any operation that modifies past records

**Acceptance Criteria:**
- POST /audit/write successfully persists records
- Records include all fields listed above
- Hashes are computed correctly
- Monotonic chain position increments on each write
- Concurrent writes are ordered by ingestion time and chain position (verified in testing)

#### Input Validation & Payload Size

**Validation Rules:**
- `eventType`: non-empty string, max 128 characters, alphanumeric + underscore
- `actorId`: non-empty string, max 256 characters
- `resourceType`: non-empty string, max 128 characters, alphanumeric + underscore
- `resourceId`: non-empty string, max 512 characters
- `payload`: valid JSON object, max 1 MB
- `timestamp`: if provided, must be valid ISO 8601 datetime and not in the future (within reason, ±1 hour clock skew tolerance)

**Payload Size Limits:**
- Individual record: ≤1 MB
- Rationale: SQLite/PostgreSQL string field limits; prevents DoS via huge payloads

**Error Responses:**
- 400 Bad Request: invalid schema, missing required fields, validation failure
- 413 Payload Too Large: record exceeds 1 MB
- 500 Internal Server Error: server-side failure (include error ID for logging)

---

### 2.2 Query API

**Intent:** Retrieve and filter stored records with pagination support.

#### Query Filters

The API must support filtering by any combination of:

| Filter | Type | Operator | Notes |
|--------|------|----------|-------|
| `actorId` | string | exact match | single value |
| `resourceType` | string | exact match | single value |
| `resourceId` | string | exact match | single value |
| `eventType` | string | exact match | single value |
| `fromTimestamp` | ISO 8601 | ≥ | inclusive; event time, not ingestion time |
| `toTimestamp` | ISO 8601 | ≤ | inclusive; event time, not ingestion time |

**Filtering Semantics:**
- Multiple filters are combined with AND logic
- Filtering is performed on caller-supplied `timestamp` (event time), NOT server-assigned `ingestedAt`
- Results are ordered by `chainPosition` (chain order), not by caller timestamp
- Example: "find all events by actorId=alice where timestamp is between 2 AM and 3 AM, ordered by chain position"

**Acceptance Criteria:**
- GET /audit/query?actorId=X&resourceType=Y&eventType=Z&fromTimestamp=T1&toTimestamp=T2 works correctly
- Filters can be combined in any valid combination
- Results are ordered by chain position
- Filtering is accurate for timestamp ranges and exact matches

#### Pagination

**Cursor-Based Pagination:**

The API must use cursor-based pagination (not offset-based) to support large result sets and handle concurrent updates correctly.

**Query Parameters:**
- `cursor` (optional): opaque token representing the position to resume from; omitted for first request
- `limit` (optional): maximum number of records to return; default 100, max 1000

**Response Schema:**

```json
{
  "records": [
    {
      "recordId": "uuid-1",
      "eventType": "USER_LOGIN",
      "actorId": "alice",
      "resourceType": "ACCOUNT",
      "resourceId": "account-123",
      "payload": { ... },
      "timestamp": "2026-08-10T10:30:00Z",
      "ingestedAt": "2026-08-10T10:30:01Z",
      "chainPosition": 42,
      "contentHash": "abc123...",
      "previousHash": "def456..."
    },
    ...
  ],
  "nextCursor": "opaque-cursor-token-or-null",
  "hasMore": true
}
```

**Cursor Implementation:**
- Cursor is an opaque string (e.g., base64-encoded JSON: `{"chainPosition": 42}`)
- If `hasMore` is false, `nextCursor` is null
- Requesting with the same cursor returns the same page (idempotent)
- Cursor represents a position in the chain, not a timestamp or offset

**Acceptance Criteria:**
- GET /audit/query?limit=10 returns first 10 records with nextCursor
- GET /audit/query?cursor=X&limit=10 returns next 10 records from cursor position
- Pagination is consistent even with concurrent writes
- No records are skipped or duplicated when paginating

---

### 2.3 Tamper-Evidence — Hash Chain

**Intent:** Provide cryptographic proof that all records are unchanged and in order.

#### Chain Structure

```
Record 1:
  contentHash(R1)
  previousHash = SHA256("GENESIS")

Record 2:
  contentHash(R2)
  previousHash = contentHash(R1)

Record 3:
  contentHash(R3)
  previousHash = contentHash(R2)

...

Record N:
  contentHash(RN)
  previousHash = contentHash(R(N-1))
```

#### Content Hash Computation

**Canonical JSON:**

The content hash is computed over a canonical (deterministic) JSON representation of the record to ensure the same record always produces the same hash, regardless of field order or whitespace.

**Canonical Format Rules:**
1. Field order: canonical order (e.g., alphabetical or fixed schema order)
2. No extraneous whitespace
3. Unicode escape sequences standardized (e.g., \u-style)
4. Numbers represented without unnecessary decimals or exponents
5. No trailing commas or comments

**Fields Included in Content Hash:**
- `eventType`
- `actorId`
- `resourceType`
- `resourceId`
- `payload`
- `timestamp`

**Fields NOT Included in Content Hash:**
- `recordId`, `ingestedAt`, `chainPosition`, `contentHash`, `previousHash` (added by server)

**Example:**

Original caller input:
```json
{
  "eventType": "USER_LOGIN",
  "actorId": "alice",
  "resourceType": "ACCOUNT",
  "resourceId": "account-123",
  "payload": { "ipAddress": "192.168.1.1", "userAgent": "Chrome" },
  "timestamp": "2026-08-10T10:30:00Z"
}
```

Canonical form for hashing:
```json
{"actorId":"alice","eventType":"USER_LOGIN","payload":{"ipAddress":"192.168.1.1","userAgent":"Chrome"},"resourceId":"account-123","resourceType":"ACCOUNT","timestamp":"2026-08-10T10:30:00Z"}
```

SHA-256(canonical form) = `abc123...` (the contentHash)

**Acceptance Criteria:**
- Canonical JSON is deterministic: same input always produces same hash
- Hash computation is documented and verifiable
- Hashes are stored with each record
- Chain is verifiable without external state

---

### 2.4 Chain Verification Endpoint

**Intent:** Detect tampering by walking the chain and reporting inconsistencies.

#### Verification Endpoint

**GET /audit/verify**

**Response Schema:**

```json
{
  "chainIntact": true,
  "totalRecords": 5000,
  "lastChainPosition": 5000,
  "violations": [],
  "verificationTimestamp": "2026-08-10T11:00:00Z"
}
```

or (if tampering is detected):

```json
{
  "chainIntact": false,
  "totalRecords": 5000,
  "lastChainPosition": 5000,
  "violations": [
    {
      "chainPosition": 2500,
      "recordId": "abc-123",
      "violationType": "HASH_MISMATCH",
      "description": "contentHash does not match recomputed hash",
      "expectedHash": "abc...",
      "actualHash": "def...",
      "chainBreakPosition": 2500
    }
  ],
  "verificationTimestamp": "2026-08-10T11:00:00Z"
}
```

#### Violation Types

| Type | Description |
|------|-------------|
| `HASH_MISMATCH` | Record's stored contentHash does not match recomputed hash of its content |
| `CHAIN_BREAK` | Record's previousHash does not match the previous record's contentHash |
| `MISSING_RECORD` | Gap in chainPosition sequence (e.g., position 99 followed by position 101) |
| `INVALID_GENESIS` | First record's previousHash is not the genesis value |
| `GENESIS_POSITION_WRONG` | First record does not have chainPosition = 1 |

#### Verification Algorithm

```
1. Load all records ordered by chainPosition
2. Verify first record:
   - chainPosition == 1
   - previousHash == SHA256("GENESIS")
   - recompute contentHash, compare to stored contentHash
3. For each subsequent record:
   - chainPosition == previous.chainPosition + 1
   - previousHash == previous.contentHash
   - recompute contentHash, compare to stored contentHash
4. If any violation found, record it and continue (report all violations, not just first)
5. Return violations list
```

**Acceptance Criteria:**
- Verification detects modifications to any record content
- Verification detects modifications to any hash field
- Verification detects deleted records (gap in chainPosition)
- Verification detects injected records (out-of-order or duplicate chainPosition)
- Verification walks complete chain and reports all violations found

#### Concurrency Considerations

- Verification may take time for large chains
- New records may be written during verification
- Verification snapshot represents state at time of query
- Verification is read-only and does not lock writes

---

### 2.5 Scenario A Acceptance Criteria Summary

| Capability | Success Criteria |
|------------|------------------|
| Write API | Persists records, assigns hashes and chain position, enforces append-only |
| Query API | Filters by any combination, returns paginated results ordered by chain position |
| Hash Chain | Records include content hash and previous hash, genesis value correct |
| Verification | Detects content modification, hash tampering, missing records, injection |
| Immutability | No update or delete operations exposed; records are append-only |
| Input Validation | Validates all fields, rejects oversized payloads, provides clear error messages |

---

## 3. Scenario B — Retention & Redaction

### 3.1 Retention Policy & Archival

**Intent:** Support compliance retention requirements while maintaining chain integrity.

#### Archival Problem

Simply deleting old records breaks the chain:
- If record at position 100 is deleted, position 101's `previousHash` no longer points to a valid record
- Verification fails with CHAIN_BREAK violation
- Breaks both tamper detection and legitimate archive compliance

#### Design Approach

**Soft-Delete / Archive Flag:**

Instead of deleting, mark records as archived:

```
Record at position 100:
  ...record fields...
  archivedAt: "2026-08-10T10:00:00Z"
  isArchived: true
```

**Verification Behavior:**

Verification must:
1. Include archived records in chain walk (they remain part of the chain)
2. Verify archived records' hashes and previousHashes normally
3. NOT report a CHAIN_BREAK violation for archived records
4. Report archived records in verification output for transparency

**Rationale:**
- Archived records are logically deleted from queries but cryptographically present in the chain
- Verification remains sound
- Future verifiers can see the archive decision point
- Regulators can confirm retention compliance

#### Configurable Retention Policy

**Retention Window Configuration:**
- Parameter: `retentionDays` (configurable, e.g., 7, 30, 90, 365)
- Records older than `retentionDays` are eligible for archival
- Archival is manual or scheduled, not automatic (to preserve audit trail of retention decisions)

**Query Behavior:**

Queries must:
- ✅ Include archived records by default (searching the full audit trail)
- ✅ Support a filter parameter `includeArchived: false` to exclude archived records
- ✅ Mark archived records in response (flag or metadata)

**Acceptance Criteria:**
- Archived records remain in chain (do not break verification)
- Archived records can be queried (with `includeArchived: true`)
- Archived records can be excluded (with `includeArchived: false`)
- Verification walks complete chain, does not falsely report breaks for archived records

---

### 3.2 Structured Redaction

**Intent:** Remove sensitive fields from query results without breaking hash verification.

#### The Redaction Problem

Original record's contentHash is computed from the full payload:

```
contentHash = SHA256(canonical JSON of original record)
```

Simply removing a sensitive field changes the payload:

```
Original payload: {"name": "Alice", "ssn": "123-45-6789"}
After deletion: {"name": "Alice"}

Hash of original payload ≠ Hash of deleted payload
→ Verification fails
```

#### Design Approach: Cryptographic Commitments (Redaction Metadata)

**Concept:**

Store redaction metadata separately; the original hash remains valid, but the query response shows the redacted view.

**Schema Addition:**

Each record may have a `redactions` array:

```json
{
  "recordId": "abc-123",
  "payload": {"name": "Alice", "ssn": "REDACTED"},
  "redactions": [
    {
      "field": "ssn",
      "redactedAt": "2026-08-10T11:00:00Z",
      "redactedBy": "admin-user",
      "reason": "PII removal per GDPR request",
      "originalHash": "sha256(original-ssn-value)"
    }
  ],
  "contentHash": "abc123...",  // Hash of ORIGINAL, unredacted payload
  "previousHash": "def456..."
}
```

**Verification Behavior:**

- `contentHash` is computed and verified against the ORIGINAL payload (including redacted fields)
- During query response, the `payload` field is replaced with the redacted version
- The `redactions` metadata is visible, allowing transparency: "field X was redacted at time Y by user Z"
- Verification always uses the original hash; redaction is a query-time presentation layer

**Alternative Approach: Field-Level Encryption**

Store sensitive fields encrypted and include the encryption key commitment in the original hash. Redaction is decryption control. This approach is more complex but allows later decryption if retention policy permits.

**Acceptance Criteria (Redaction Metadata):**
- Sensitive fields can be marked for redaction
- Redactions are recorded with timestamp, actor, and reason
- Original hash remains valid and verifiable
- Redacted view is returned in queries
- Redaction metadata is auditable

---

### 3.3 Bulk Export

**Intent:** Export records for external verification without requiring access to the running service.

#### Export Endpoint

**GET /audit/export?resourceId=X** or **GET /audit/export?actorId=Y**

**Response:**

A self-contained bundle (JSON or ZIP) containing:

```json
{
  "exportMetadata": {
    "exportTimestamp": "2026-08-10T11:30:00Z",
    "exportedBy": "system",
    "recordCount": 500,
    "startChainPosition": 4500,
    "endChainPosition": 5000,
    "exportFormat": "1.0"
  },
  "records": [
    { ...record 1... },
    { ...record 2... },
    ...
  ],
  "bundleChainCheckpoint": {
    "lastRecordId": "abc-123",
    "lastChainPosition": 5000,
    "lastContentHash": "def456...",
    "bundleHash": "sha256(hash of all exported records)"
  }
}
```

#### Independent Verification

A recipient with this bundle can:

1. **Verify internal integrity:** Walk the exported records and verify hashes chain correctly within the bundle
2. **Verify boundary consistency:** The first record's `previousHash` matches the known record before this export window (requires external checkpoints)
3. **Verify completeness:** All records for `resourceId=X` between time T1 and T2 are included (requires external checkpoints or trusted query logs)

#### Limitations

- Bundle proves the records *in the bundle* were not tampered with since export
- Bundle does NOT prove:
  - No records were omitted from the export
  - No records were added after export
  - The export represents the true state at export time (without external verification)
  
- To fully verify a bundle, you need one or both:
  - A trusted checkpoint from the audit service (e.g., signed bundle hash)
  - Full chain state to verify records before/after the bundle

**Acceptance Criteria:**
- Export endpoint returns a verifiable bundle
- Bundle includes all records matching the filter
- Exported records' hashes chain correctly
- Bundle metadata enables independent verification (within limitations)

---

### 3.4 Scenario B Acceptance Criteria Summary

| Capability | Success Criteria |
|------------|------------------|
| Archival | Records marked archived, chain remains intact, verification does not falsely break |
| Redaction | Sensitive fields marked redacted, original hash valid, query returns redacted view |
| Retention Policy | Configurable retention window, archival is explicit and auditable |
| Bulk Export | Bundle includes all matching records, exported records verifiable, limitations documented |

---

## 4. Scenario C — Ambiguous Compliance Requirement

### 4.1 Requirement Clarification

**Original (Ambiguous) Requirement:**

> Regulators need to be able to audit access to client account data.

#### Ambiguities Identified

| Ambiguity | Examples | Impact |
|-----------|----------|--------|
| "Access" definition | Login? API call? Data fetch? Viewing a record? Download? Export? | Determines event scope |
| "Client account data" scope | Account-level data? User-level? Sub-account? All data types? | Determines resourceType/resourceId |
| "Regulators" identity | Internal audit role? External compliance authority? API key? | Determines access control |
| "Audit" capability | View audit log? Export? Verify? Generate reports? | Determines API surface |
| "Failed" access attempts | Log? Ignore? Special treatment? | Determines event capture |
| Privileged access | Admin access treated differently? Break-glass access? | Determines actor classification |
| Time zone handling | All timestamps UTC? Local? | Determines query semantics |
| Reporting format | JSON? CSV? PDF? Regulatory specific? | Determines export format |
| Retention period | Regulatory minimum (often 7 years)? Configurable? | Determines archival policy |
| Source-system completeness | Auditor assumes all access is logged? Or gaps known? | Determines verification limitations |
| Performance expectations | Real-time? T+24h? Batch? | Determines implementation constraints |

### 4.2 Proposed Clarified Requirement (Working Assumption)

**Status:** PROPOSED — requires stakeholder confirmation before implementation.

---

**Scenario C Compliance Audit Requirement:**

The audit log service must provide a compliance audit view of all access to sensitive client account data, enabling internal audit and potential external regulator verification.

#### Scope

1. **Monitored Access Actions:**
   - API read operations (GET /account/{id}/data)
   - API write operations (POST/PUT/DELETE /account/{id}/data)
   - Admin access (privileged operations on behalf of client)
   - Break-glass access (emergency override access, logged with justification)
   - Failed authentication/authorization attempts (access denied)

2. **Monitored Resources:**
   - `resourceType` = "CLIENT_ACCOUNT"
   - `resourceId` = account identifier (e.g., "ACCOUNT-123")

3. **Actor Identity:**
   - User/system principal making the access request
   - Classification: `NORMAL_USER`, `ADMIN`, `SYSTEM`, `BREAK_GLASS`

4. **Event Capture:**
   - Successful access attempts (LOGGED)
   - Failed access attempts (LOGGED, flagged as failed)
   - Decision: Include both; filter by status if needed

5. **Privileged Access Handling:**
   - Admin access: marked with `eventType` = "ADMIN_DATA_ACCESS"
   - Break-glass access: marked with `eventType` = "BREAK_GLASS_ACCESS", include justification reason code

6. **Payload Fields (per event):**
   - `accessType` ("READ" | "WRITE" | "DELETE" | "ADMIN" | "BREAK_GLASS")
   - `dataClassification` (e.g., "PUBLIC" | "INTERNAL" | "CONFIDENTIAL" | "RESTRICTED")
   - `success` (true | false)
   - `reasonCode` (for failures or break-glass, e.g., "INSUFFICIENT_PRIVILEGE", "EMERGENCY_OVERRIDE_APPROVED")
   - `userId` or `system` (actor identity)
   - `ipAddress`, `userAgent` (connection metadata)
   - `dataFieldsAccessed` (optional array of field names for write operations)

7. **Reporting Capability:**
   - Query: filter by `resourceId` (account) and date range
   - Export: bulk export of all access events for an account over a retention period (7 years minimum)
   - Verification: all exported events must be independently verifiable

8. **Retention & Compliance:**
   - Retention: 7 years minimum (configurable per regulation)
   - Archival after retention period: soft-delete per Scenario B
   - Attestation: export bundle includes signatures/checksums for regulator verification

9. **Authorization:**
   - Only audit/compliance roles can query this view
   - API requires role-based access control (RBAC)
   - Decision: Out of scope for prototype, assume trusted caller

---

**Limitations & Assumptions:**
- Prototype assumes audit service is the single source of truth; assumes source systems log completeness
- Break-glass access assumes source system provides the reason code; audit service validates format
- Time zones: all timestamps are UTC; local timezone conversion is caller responsibility
- Source-system completeness: audit service assumes source systems are configured to log all monitored actions; audit service does not verify source-system logging completeness

---

### 4.3 Implementation Scope for Scenario C

**Implemented:**
- Scenario A write API modified to accept compliance event schema (eventType, payload with access fields)
- Scenario A query API enhanced with RBAC check (role verification)
- Scenario B archival and export extended to support compliance data

**Scoped Out (but documented for future work):**
- Break-glass access approval workflow (assumed pre-approved by source system)
- Multi-tenant segregation (assumed single-tenant prototype)
- Regulatory format (CSV/PDF export per specific regulator)
- Time-zone conversion utilities (clients handle timezone)
- Source-system integration and logging verification (assumed external)

**Acceptance Criteria:**
- Write API accepts compliance audit event schema
- Query API supports filtering by account/date range and enforces role-based access
- Export endpoint returns compliance bundle with all access events
- Documentation includes clarified requirement, assumptions, limitations, and scoped-out features

---

## 5. Cross-Cutting Acceptance Criteria

### Security Requirements

- **Confidentiality:** Audit log does not encrypt payload by default (assumption: deployment-level encryption or TLS in transit)
- **Integrity:** Hash chain detects tampering (with caveats: requires trusted checkpoint for production)
- **Accountability:** All events include actor identity and timestamp
- **Append-only enforcement:** No update/delete APIs; database constraints prevent modification
- **Input validation:** All inputs validated; oversized payloads rejected

### Testing Expectations

**Scenario A Tests:**
- Unit tests: hash computation, canonical JSON, chain validation
- Integration tests: write/query/verify API end-to-end
- Tamper test: modify database record, verify detection

**Scenario B Tests:**
- Archival: soft-delete, chain remains intact, verification unaffected
- Redaction: metadata storage, query returns redacted view, hash unchanged
- Export: bundle completeness, internal verification

**Scenario C Tests:**
- Compliance schema: payload validation, role-based access
- Query filtering: by account/date range
- Export: compliance bundle format, metadata

### Explicit Non-Goals

- 🚫 Encrypted audit storage (handled at deployment level)
- 🚫 Multi-tenant segregation (single-tenant prototype)
- 🚫 External key management or HSM integration
- 🚫 Blockchain or distributed consensus (single-instance SQLite/PostgreSQL)
- 🚫 Zero-knowledge proofs or privacy-preserving verification
- 🚫 Regulatory compliance certification (prototype demonstrates capability)
- 🚫 Break-glass approval workflow (assumed pre-approved by source)
- 🚫 Multi-language support or localization

### Assumptions

1. **Single application instance:** Concurrency handled by application-level locks (SQLite) or database transactions (PostgreSQL)
2. **Trusted database:** Attacker with unrestricted database access can tamper undetected (production should externally anchor checkpoints)
3. **Caller identity trust:** API assumes callers are authenticated; no additional auth layer required (deployment-level concern)
4. **Network TLS:** Payloads include sensitive data; deployment must use HTTPS
5. **Clock synchronization:** Timestamp validation assumes reasonable clock skew tolerance (±1 hour)
6. **SQLite for prototype, PostgreSQL for production:** Architecture designed for both; initial dev/test uses SQLite
7. **Monotonic chain position:** `chainPosition` is a globally unique, monotonically increasing counter (NOT timestamp)

### Risks & Limitations

| Risk | Mitigation |
|------|-----------|
| Hash chain in same DB can be rewritten by attacker with DB access | Production: external checkpoint anchoring or digital signatures |
| Monotonic counter collision (e.g., clock rollback, server restart) | PostgreSQL: sequence-based counter; SQLite: application-level lock + in-memory counter |
| Timestamp spoofing (caller backdates events) | Caller timestamp is for event time, not chain control; chain order is server-driven |
| Redaction metadata leaks original values | Metadata stores hash of original, not the value itself |
| Archive soft-delete is logical, not physical | Physical deletion requires migration job; out of scope |
| Bulk export omits records if filtering is non-deterministic | Filtering algorithm is deterministic; export is snapshot at export time |
| SQLite concurrency: application-level lock is single-instance only | PostgreSQL uses database-level serialization; SQLite lock is local-process only |

**Explicit Limitation Statement:**

> A hash chain stored in the same database detects ordinary modification when the verifier has access to trusted chain state, but an attacker with unrestricted database access may rewrite the complete chain. A production system should externally anchor or digitally sign trusted checkpoints.

---

## 6. Engineering Quality Checkpoints

**Requirement Sign-Off:** Engineer must review and approve this analysis before implementation.

**Design Review:** Proposed architecture (decision records) requires approval before coding.

**Code Review Gates:**
- Hash computation: manual verification against test vectors
- Chain verification algorithm: step-through logic verification
- Redaction metadata: cryptographic correctness
- RBAC enforcement: authorization logic review

**Testing Gates:**
- Scenario A: tamper test must detect modification
- Scenario B: archival must not break chain, redaction must preserve original hash
- Scenario C: compliance schema must validate, export must be complete

---

## 7. Document Change History

| Date | Status | Notes |
|------|--------|-------|
| TODO | DRAFT | Initial requirement normalization |
| TODO | APPROVED | (pending engineer sign-off) |

---

**Prepared by:** AI-Assisted Requirement Analysis  
**Approved by:** TODO (engineer sign-off required)  
**Next Step:** Architecture Decisions review
