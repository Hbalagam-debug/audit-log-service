# Scenario C Requirement Analysis — Regulatory Access Reporting

Status: PROPOSED — DESIGN ONLY  
Scope: Requirement clarification and prototype normalization only

## 1. Source requirement and ambiguity

Original Scenario C requirement:

> “Regulators need to be able to audit access to client account data.”

This statement is materially under-specified. It does not define who consumes the report, what actions count as "access," what data categories are in scope, what proof properties are required, or how to balance privacy with regulator visibility.

## 2. Questions that must be answered by Product/Compliance

### 2.1 Consumer and operating model
- Which regulator or internal compliance function is the primary report consumer?
- Is reporting on-demand only, scheduled only, or both?
- Is there a legally mandated reporting template, cadence, or export format?

### 2.2 Access semantics
- Which actions must count as "access": view/read, search, export/download, update, administrative override, bulk operation, service-to-service reads?
- Must failed and denied attempts be included?
- Must attempts blocked before reaching application data still be reported?

### 2.3 Data scope
- What exactly qualifies as "client account data" (account profile, balances, statements, transaction history, KYC documents, credentials)?
- Are derived or aggregated data views in-scope?
- Are test/sandbox accounts excluded?

### 2.4 Identity and attribution
- Which actor classes are in scope: employee, contractor, service account, application, client?
- What identity minimum is required: actorId, actorType, sourceApplication, request/session ID, correlation ID, approval reference?
- Must human supervisor/approval metadata be present for privileged access?

### 2.5 Time and filtering semantics
- What report timezone is authoritative (UTC vs local regulator timezone)?
- Is time range bounded by event timestamp, ingest timestamp, or both?
- What are required filters (account/resource, actor, action, outcome, source application)?

### 2.6 Output and proof requirements
- Must reports include successful, failed, and denied attempts in one view?
- Is raw account data allowed in report payloads, or must all sensitive values be masked?
- Is integrity proof alone sufficient, or are authenticity and completeness proofs required?
- If completeness is required, what independent anchoring mechanism is acceptable?

### 2.7 Retention and legal controls
- What retention period applies specifically to access-report rows?
- Must archived records be included by default?
- How should legacy redaction overlays and cryptographic erasure be represented to regulators?

### 2.8 Operational constraints
- What max report range and max row count are acceptable?
- Are duplicate/retried attempts expected to appear as separate rows or deduplicated summaries?
- What SLAs exist for report generation latency?

## 3. Prototype assumptions accepted for design

These assumptions are accepted for the interview prototype and require human confirmation before implementation:

1. Report consumer is an authorized internal compliance operator responding to regulator inquiries.
2. Report generation is on-demand API driven (no scheduler in prototype).
3. **Normalized client-account access-event taxonomy** with five canonical action types: `CLIENT_ACCOUNT_DATA_VIEWED`, `CLIENT_ACCOUNT_DATA_SEARCHED`, `CLIENT_ACCOUNT_DATA_EXPORTED`, `CLIENT_ACCOUNT_DATA_UPDATED`, `CLIENT_ACCOUNT_ACCESS_DENIED`.
4. `COMPLIANCE_REPORT_GENERATED` is a certificate event only; must be excluded from client-data-access report results.
5. Both successful and denied outcomes are included; hard infrastructure failures may be partially represented.
6. Reporting is bounded to UTC event timestamp range.
7. Exactly one primary account/resource selector is required per request.
8. **Single hybrid architecture:** Direct queries over existing immutable `audit_events` for interactive reporting; reuse of Scenario B Ed25519 signed export for downloadable regulatory bundles.
9. **No separate compliance read model, materialized view, or external warehouse** in the prototype.
10. **Reuse Scenario B archival, masking, and encryption behaviors:** archived events excluded by default, legacy masked fields remain masked, destroyed encrypted fields render as `[REDACTED]`.
11. **Interactive unsigned JSON** for cursor-paginated queries; **signed regulatory bundle** using Ed25519 for downloadable reports.
12. **Signatures prove bundle integrity and authenticity only**; they do not independently prove absolute completeness of filtered results.
13. Independently anchored checkpoints or inclusion/non-inclusion proofs are deferred to production.
14. Prototype authorization remains limited; authorization requirements are documented but not fully enforced.

## 4. Explicitly unresolved items

- Legal definition of "client account data" across business domains.
- Whether regulators require mandatory CSV template output in addition to JSON/signed JSON bundle.
- Exact role/approval matrix for compliance report generation.
- Formal treatment of retries/duplicates for "attempt" counting.
- Whether completeness must be externally anchored (currently unavailable in prototype).

## 5. Risks if assumptions are incorrect

1. **Regulatory misalignment risk:** wrong access taxonomy may omit reportable actions.
2. **Privacy risk:** insufficient masking policy could expose sensitive account data in compliance outputs.
3. **Audit sufficiency risk:** integrity+signature may be rejected if regulator expects anchored completeness guarantees.
4. **Operational risk:** unbounded ranges could cause performance failures or partial responses.
5. **Attribution risk:** missing identity metadata could make rows non-actionable during investigations.

## 6. Proposed normalized prototype requirement and selected architecture

### 6.1 Single selected hybrid architecture

The prototype uses a **single cohesive hybrid approach** that combines:

1. **Direct, read-only queries** over the existing immutable `audit_events` table for interactive compliance reporting.
2. **Reuse of Scenario B Ed25519 signed bulk-export infrastructure** for downloadable regulatory bundles.

No separate compliance read model, materialized view, or external warehouse is introduced in the prototype.

### 6.2 Architecture rationale

- **Maximizes reuse:** Leverages proven Scenario A/B chain, masking, encryption, and signing logic.
- **Minimizes complexity:** No additional schema, projection drift, or external integration overhead.
- **Clear semantics:** Interactive queries are unsigned and fast; regulatory reports are signed and verifiable.
- **Preserves integrity:** Maintains existing immutability and chain verification without modification.
- **Defers completeness:** Explicitly documents that signatures prove integrity/authenticity, not absolute completeness; anchored proofs deferred to production.

### 6.3 Interactive unsigned JSON (GET endpoint)

An authorized compliance operator can request bounded, cursor-paginated access reports:

- Require exactly one selector: `accountId` or `resourceId`.
- Require bounded UTC `from` and `to` timestamps (`from < to`).
- Support optional filters: `actorId`, `action`, `outcome`, `includeArchived`, `cursor`, `limit`.
- Return unsigned JSON with pagination metadata.
- Exclude `COMPLIANCE_REPORT_GENERATED` certificate events from results.
- Apply Scenario B archival filtering, masking, and redaction behavior.
- Include both successful and denied access outcomes.

### 6.4 Signed regulatory bundle (POST endpoint)

An authorized compliance operator can request a signed, verifiable regulatory report:

- Accept same filters as GET endpoint.
- Require `approvalRef` (compliance approval metadata).
- Produce a canonical JSON bundle (reuse Scenario B format).
- Compute deterministic SHA-256 digest.
- Sign the digest with Ed25519 (reuse Scenario B keys and verification).
- Append `COMPLIANCE_REPORT_GENERATED` certificate event with safe metadata only (approvalRef, criteriaDigest, bundleDigest, recordCount, generatedAt, signingKeyId).
- Exclude certificate event from the bundle it certifies.
- Do not store account data, plaintext values, or private keys in the certificate.
- If signing or certificate persistence fails, return an error; do not return a partial response.

**Signature semantics (explicit):**
- Proves bundle integrity (tamper detection) and signer authenticity.
- Does **not** prove absolute completeness of the filtered global-chain subset.
- Independently anchored checkpoints or inclusion/non-inclusion proofs are deferred to production.

### 6.5 Measurable acceptance criteria (design target)

1. Interactive and signed endpoints enforce exactly one account/resource selector and valid bounded UTC range.
2. Output includes both successful and denied attempts when present.
3. Output rows include minimum identity/context: actorId, actorType, action, outcome, resource identifier, timestamp.
4. Rows ordered deterministically by chain position; cursor pagination stable.
5. `includeArchived` switch is explicit and testable.
6. Legacy-redacted and cryptographically erased fields remain masked.
7. Interactive JSON response is unsigned; signed bundle uses Ed25519.
8. Signature verification succeeds for untampered bundles; fails for tampered data.
9. `COMPLIANCE_REPORT_GENERATED` certificate events are appended and excluded from report results.
10. API/docs explicitly state signature proves integrity/authenticity, not completeness.
11. No account data, plaintext sensitive values, or encryption keys stored in certificates.

## 7. Proposed access-event taxonomy (normalized)

### 7.1 Exact prototype access-event types

Canonical event types for client-account access reporting:

- `CLIENT_ACCOUNT_DATA_VIEWED` — data was successfully retrieved for read/view
- `CLIENT_ACCOUNT_DATA_SEARCHED` — data was successfully filtered or searched
- `CLIENT_ACCOUNT_DATA_EXPORTED` — data was successfully extracted or exported
- `CLIENT_ACCOUNT_DATA_UPDATED` — data was successfully modified
- `CLIENT_ACCOUNT_ACCESS_DENIED` — access attempt was rejected

Note: `COMPLIANCE_REPORT_GENERATED` is a certificate event appended during report generation; it must **not** be included in client-data-access report results.

### 7.2 Mandatory event fields (per access event)

All access events must include the following required fields (design target):

- `actorId` — stable, non-sensitive unique identifier
- `actorType` — one of `EMPLOYEE`, `CONTRACTOR`, `SERVICE_ACCOUNT`, `APPLICATION`, `CLIENT`
- `resourceType` — e.g., `CLIENT_ACCOUNT`
- `resourceId` or account identifier — normalized surrogate, not raw account number
- `action` — one of: view, search, export, update (canonical values)
- `outcome` — one of: `SUCCESS`, `DENIED`, `FAILED`
- `purposeCode` — business justification code
- `sourceApplication` — originating system identifier
- `correlationId` — request correlation identifier
- `eventTimestamp` (UTC normalized)

Optional fields:
- network/device metadata (high-level, non-sensitive)
- requestId or sessionId

### 7.3 Data minimization constraints

Do **not** log or allow in payload/report:
- Raw account numbers, credentials, auth tokens
- Unnecessary PII or plaintext sensitive values
- Plaintext encryption keys, key materials, or wrapped-key bytes
- Unsalted sensitive-value hashes

Use stable non-sensitive identifiers and Scenario B masking/encryption controls.

### 7.4 Envelope vs payload placement

Keep immutable envelope fields in existing top-level `AuditEvent` columns:
- `eventType`, `actorId`, `resourceType`, `resourceId`, `timestamp`, chain/hash metadata.

Keep extended compliance semantics in `payload`:
- `actorType`, `action`, `outcome`, `purposeCode`, `sourceApplication`, `correlationId`, `requestId/sessionId`, optional network metadata.

Rationale: preserves Scenario A/B envelope compatibility while allowing taxonomy evolution in payload.

## 8. Proposed API contract (design only)

## 8.1 Endpoint decision (explicit)

**Proposed endpoints:**

1. **GET /audit/compliance/access-report** — Interactive, unbounded, cursor-paginated query
   - Use case: compliance operator retrieving bounded queries and iterating through pages
   - Response: unsigned JSON with cursor pagination
   - Validation: reject invalid selectors, ranges, and enums

2. **POST /audit/compliance/access-report/bundle** — Signed regulatory report generation
   - Use case: generating downloadable, verifiable regulatory bundles with approval metadata
   - Response: signed JSON bundle (Ed25519) suitable for offline verification and archival
   - Reuse: Scenario B signed export infrastructure, manifest format, digest computation, and verification
   - Validation: same as GET plus approval metadata extraction

### 8.2 GET for bounded, cursor-paginated interactive queries

**Query parameters (GET):**
- required: exactly one of `accountId` or `resourceId`
- required: `from`, `to` (UTC, bounded, `from < to`)
- optional: `actorId`, `action`, `outcome`, `includeArchived`, `cursor`, `limit`, `format`

**Validation rules:**
- reject both/neither account selector (`400`)
- reject invalid or excessive range (`400`)
- reject unsupported action/outcome/format (`400`)
- limit row count and/or time range (`413`)

**Response shape (JSON mode):**
- `selection`
- `recordCount`
- `items` (cursor page)
- `nextCursor`
- `hasMore`

### 8.3 POST for signed regulatory report generation

**Request body (POST):**
- required: exactly one of `accountId` or `resourceId`
- required: `from`, `to`
- required: `approvalRef` (compliance approval identifier)
- optional: `actorId`, `action`, `outcome`, `includeArchived`, `reasonCode`

**Response shape:**
- Reuse Scenario B bundle structure: manifest, records, digests, signature block
- Signature block includes: `algorithm`, `keyId`, `value` (Base64), `publicKey` (X.509, Base64)
- Bundle includes `bundleDigest`, `recordsDigest`, and signed integrity evidence

**Error responses:**
- `400` — validation failure
- `401/403` — authentication/authorization (future)
- `413` — size limit exceeded
- `500` — server error

### 8.4 Historical recommendation rationale

**Prototype recommendation:**
1. Start with **GET** for deterministic query behavior and cursor pagination.
2. Implement **POST** for signed bundle generation with approval metadata and complex filters.
3. Defer async report-job workflows and scheduled templates to production evolution.

## 9. Integrity, compliance, and behavior expectations

- Chain verification remains unchanged and authoritative.
- Archived events are optionally included via explicit switch.
- Redaction overlays and destroyed-key masking remain enforced in report output.
- Signed bundle verifies content integrity/origin only for returned subset.
- **Report generation must append a `COMPLIANCE_REPORT_GENERATED` certificate event** (audit trail).
- **`COMPLIANCE_REPORT_GENERATED` events must be excluded from client-data-access report results** (to prevent circular dependencies and focus on actual access events).
- Unauthorized report attempts should be audit-logged once auth exists.
- Use UTC normalization and deterministic ordering.
- Preserve duplicate attempt records unless a formal dedupe rule is approved.
- Handle malformed legacy access events with explicit reporting flags instead of silent omission.

## 10. Authorization and privacy model (prototype limitation documented)

Production requirements (not fully implemented in prototype):
- role-based access for compliance reporting,
- least privilege,
- separation of duties for high-sensitivity report generation,
- approval references for privileged report requests,
- full report-generation audit trail.

Prototype limitation:
- authorization stack is incomplete and must not be presented as production-grade security.

## 11. Proposed implementation scope split

### 11.1 Prototype implementation scope
- normalized access-event validation conventions,
- compliance report query surface,
- bounded UTC range + deterministic ordering,
- account/resource selector enforcement,
- success/denied outcome inclusion,
- cursor pagination and includeArchived switch,
- Scenario B masking reuse,
- optional signed report bundle reuse,
- report-generation certificate event,
- focused unit/integration tests.

### 11.2 Deferred production scope
- scheduled report jobs and templates,
- full authN/authZ platform integration,
- regulator-specific output templates,
- external warehouse/read-model scaling,
- independent completeness anchoring,
- multi-tenant/HSM/KMS hardening,
- high-volume streaming generation.

## 12. Design-time test plan for Scenario C

- allowed access included
- denied access included
- unrelated event types excluded
- `COMPLIANCE_REPORT_GENERATED` certificate events excluded from client-data-access results
- account/resource filter correctness
- actor filter correctness
- bounded UTC range behavior
- invalid range rejection
- excessive range rejection
- archived include/exclude behavior
- masked legacy values stay masked
- destroyed encrypted values stay masked
- ordering and cursor pagination stability
- signed report verification success/failure
- tamper detection for report payload/manifest/signature
- unsigned interactive JSON response
- signed regulatory bundle with Ed25519 signature
- unauthorized-access placeholder behavior
- sensitive data non-leakage assertions
- chain remains valid after report-generation certificate append
- report-generation certificate presence
- empty report behavior
- malformed legacy event handling

---

Status: PROPOSED — DESIGN ONLY  
Production code changed: NO  
Schema changed: NO  
Tests changed: NO  
Human approval required before implementation: YES
