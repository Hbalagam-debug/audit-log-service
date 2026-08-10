# Implementation Plan — Audit Log Service

**Document Status:** Step 1 — High-Level Sequencing and Decomposition  
**Last Updated:** TODO (set by engineer)  
**Approved By:** TODO (pending engineer sign-off)

---

## Overview

This document breaks the audit log service assignment into 10 sequential stages with clear dependencies, acceptance criteria, and Git commit sequencing. The plan balances greenfield development with iterative validation and tamper testing.

---

## Stage 1: Requirement Analysis & Repository Setup

**Intent:** Normalize requirements and establish development foundation.

**Dependencies:** None (initial stage)

**Tasks:**
1. Complete requirement analysis (scope all three scenarios)
2. Identify ambiguities and document assumptions
3. Create project repository structure
4. Initialize Git with clean history
5. Create project documentation skeleton
6. Document AI usage log and governance

**Acceptance Criteria:**
- Requirement analysis is complete and approved
- Git repository initialized with initial commit
- Documentation structure in place (README, ATTESTATION, requirements, architecture, planning, AI log)
- All TODO placeholders identified and listed

**Risks:**
- Unclear requirements delay later stages
- Wrong architectural assumptions discovered late

**Mitigations:**
- Approve requirements before moving forward
- Design review before coding begins
- Early prototyping of core hash logic

**Human-Review Checkpoint:** Engineer approves requirement analysis and architecture decisions before stage 2.

**Proposed Git Commits:**

```
1. Initial commit: README.md, ATTESTATION.md, .gitignore
2. docs/requirements: requirement-analysis.md
3. docs/architecture: architecture-decisions.md
4. docs/planning: implementation-plan.md
5. docs/ai: usage-log.md with template
```

---

## Stage 2: Spring Boot & Database Scaffold

**Intent:** Establish application skeleton with Spring Boot, Maven configuration, and database layer.

**Dependencies:** Stage 1 complete

**Tasks:**
1. Initialize Spring Boot Maven project (Java 17)
   - Spring Data JDBC (for direct SQL control)
   - Spring Test framework
   - Spring Web (REST support)

2. Create pom.xml with dependencies:
   - Spring Boot starters (web, test, data)
   - SQLite driver (org.xerial:sqlite-jdbc)
   - PostgreSQL driver (org.postgresql:postgresql)
   - JSON serialization (Jackson)
   - SHA-256 hashing (Java built-in)
   - Utilities (Lombok, Apache Commons Lang)

3. Application configuration:
   - application.yml (SQLite for local, PostgreSQL-compatible config)
   - Profile separation (dev, test, prod)

4. Database schema:
   - `audit_events` table (recordId, eventType, actorId, resourceType, resourceId, payload, timestamp, ingestedAt, chainPosition, contentHash, previousHash, isArchived, archivedAt)
   - Indexes on: chainPosition (unique, primary), resourceId, actorId, eventType, timestamp
   - Constraints: NOT NULL on immutable fields, unique on chainPosition

5. Entity/DTO classes:
   - `AuditEvent` (JPA/JDBC entity)
   - `WriteEventRequest` (input DTO)
   - `QueryResponse` (pagination DTO)
   - `VerificationResult` (chain verification response)

6. Repository layer (Spring Data JDBC):
   - `AuditEventRepository` with custom SQL queries
   - Transaction management

7. Exception handling:
   - Custom exceptions (ValidationException, PayloadTooLargeException)
   - Global error handler

8. Logging framework:
   - SLF4J + Logback
   - Request/response logging (masked sensitive data)

**Acceptance Criteria:**
- Spring Boot application starts cleanly
- Database schema is created and tested
- Maven build succeeds with no compiler errors
- All dependencies are licensed compatibly (document in LICENSE.md)

**Risks:**
- Dependency version conflicts
- Database schema mistakes (requires migration later)
- Spring configuration complexity

**Mitigations:**
- Use Spring Boot parent version (2.7.x or 3.x LTS)
- Test schema creation with both SQLite and PostgreSQL
- Simple configuration first, complexity added as needed

**Human-Review Checkpoint:** Code review of pom.xml, application configuration, and schema before stage 3.

**Proposed Git Commits:**

```
6. pom.xml: Maven project configuration
7. src/main/resources: application.yml configuration
8. src/main/java: Spring Boot application class, config beans
9. src/main/java: Database schema migration / initial setup
10. src/main/java: Entity classes and DTOs
11. src/main/java: Repository layer with Spring Data JDBC
12. src/main/java: Exception handling and global error handler
```

---

## Stage 3: Scenario A — Core Implementation

**Intent:** Implement core audit log functionality: write API, query API, hash chain, and verification.

**Dependencies:** Stage 2 complete (database scaffold)

**Tasks:**

### 3.1 Hash Chain Logic (Core)
1. Canonical JSON serialization:
   - Implement deterministic JSON ordering
   - Create test vectors (known input → expected hash)
   - SHA-256 computation
   
2. Chain position counter:
   - Monotonic counter with application-level lock (SQLite) or sequence (PostgreSQL)
   - Thread-safe counter implementation

3. Genesis hash constant:
   - SHA-256("GENESIS") = well-known constant
   - Document in code

### 3.2 Write API Controller & Service
1. POST /audit/write endpoint
   - Input validation (field types, lengths, payload size)
   - Timestamp handling (caller-supplied or server-assigned)
   - Compute contentHash and previousHash
   - Persist record with chainPosition
   
2. WriteEventService:
   - Orchestrate validation, hashing, persistence
   - Error handling and clear error messages
   - Idempotency (detect duplicate submissions)

3. Input validators:
   - eventType: non-empty, max 128 chars, alphanumeric + underscore
   - actorId, resourceType, resourceId: length constraints
   - payload: valid JSON, max 1 MB
   - timestamp: valid ISO 8601, reasonable bounds

### 3.3 Query API Controller & Service
1. GET /audit/query endpoint
   - Query parameters: actorId, resourceType, resourceId, eventType, fromTimestamp, toTimestamp, cursor, limit
   - Filtering by all combinations (AND logic)
   - Cursor-based pagination (opaque token)
   
2. QueryService:
   - Construct SQL WHERE clauses from filters
   - Order by chainPosition
   - Implement cursor pagination
   - Return response with nextCursor and hasMore

3. Cursor implementation:
   - Opaque token (base64 JSON)
   - Stateless (cursor encodes position)
   - Idempotent (same cursor returns same page)

### 3.4 Chain Verification Endpoint
1. GET /audit/verify endpoint
   - Walk complete chain from position 1 to end
   - Check: first record genesis, chainPosition sequence, previousHash chain, contentHash recomputation
   - Collect all violations
   
2. VerificationService:
   - Load all records ordered by chainPosition
   - Compute violations (HASH_MISMATCH, CHAIN_BREAK, MISSING_RECORD, etc.)
   - Return detailed violation report

**Acceptance Criteria:**
- Write API persists records with correct hashes and chainPosition
- Query API returns correctly filtered, paginated results
- Verification endpoint detects all documented violation types
- Input validation rejects invalid data with clear error messages
- Hash computation is deterministic (same input → same hash)

**Risks:**
- Hashing implementation incorrectness (breaks chain verification)
- Concurrency bugs in chain position assignment
- Query filtering bugs (incorrect SQL or cursor logic)

**Mitigations:**
- Create hash computation test vectors (canonical JSON examples)
- Test concurrent writes verify monotonic ordering
- Unit test all query filter combinations
- Manual verification of small chains (print and verify by hand)

**Human-Review Checkpoint:** Code review of hash logic, write/query/verify APIs before stage 4.

**Proposed Git Commits:**

```
13. src/main/java: Canonical JSON serialization (HashChainService)
14. src/main/java: Hash computation and genesis constant
15. src/main/java: Write API controller and validation
16. src/main/java: Write API service (WriteEventService)
17. src/main/java: Query API controller and pagination logic
18. src/main/java: Query API service (QueryService)
19. src/main/java: Verification API controller and service
20. src/test: Unit tests for hash computation
21. src/test: Integration tests for write/query/verify APIs
```

---

## Stage 4: Scenario A Testing & Tamper Demonstration

**Intent:** Validate Scenario A functionality and demonstrate tamper detection.

**Dependencies:** Stage 3 complete (core implementation)

**Tasks:**

1. Unit tests:
   - Hash computation test vectors
   - Canonical JSON determinism
   - Validation logic (reject invalid inputs)
   - Cursor pagination logic

2. Integration tests:
   - Write multiple events
   - Query with various filter combinations
   - Verify chain integrity (should pass)

3. Tamper test:
   - Write 10 events, verify chain intact
   - Manually modify database (e.g., change payload or contentHash of record #5)
   - Run verification, confirm tampering detected
   - Document detected violation type and message

4. Concurrent write test:
   - Multiple threads writing simultaneously
   - Verify chainPosition is monotonically increasing
   - Verify no gaps or duplicates

5. Pagination test:
   - Write 1000 events
   - Paginate through results with limit=100
   - Verify no skipped or duplicated records
   - Verify cursor idempotency

6. Edge cases:
   - Empty database (no records)
   - Single record verification
   - Maximum payload size (exactly 1 MB)
   - Oversized payload (rejected)

**Acceptance Criteria:**
- All unit tests pass
- All integration tests pass
- Tamper test detects modification
- Concurrent writes maintain chain integrity
- Pagination is correct and idempotent
- Edge cases handled gracefully

**Risks:**
- Tests reveal bugs in core logic (requires fixes to stage 3)
- Concurrent testing difficult on single-instance SQLite

**Mitigations:**
- Fix any bugs discovered and re-test
- Use embedded H2 or in-memory SQLite for local testing
- Separate PostgreSQL testing for production concurrency

**Human-Review Checkpoint:** Engineer approves test suite and demonstrates passing tamper test.

**Proposed Git Commits:**

```
22. src/test: Comprehensive unit test suite (hash, validation, pagination)
23. src/test: Integration test suite (write/query/verify end-to-end)
24. src/test: Tamper detection test (modify database, verify detection)
25. src/test: Concurrent write stress test
26. docs: Test results and tamper test evidence
27. docs: Known issues or workarounds discovered during testing
```

---

## Stage 5: Scenario B — Retention & Archival

**Intent:** Implement soft-delete archival that maintains chain integrity.

**Dependencies:** Stage 4 complete (Scenario A tested)

**Tasks:**

1. Database schema update:
   - Add `isArchived` (boolean, default false)
   - Add `archivedAt` (timestamp, nullable)

2. Archival logic:
   - Archive endpoint: PUT /audit/{recordId}/archive
   - Mark record as archived (set isArchived=true, archivedAt=now)
   - No modification to record content or hashes

3. Query updates:
   - Add filter: `includeArchived` (boolean, default true)
   - If false, exclude records where isArchived=true
   - Mark archived records in response

4. Verification updates:
   - Verification walks complete chain including archived records
   - Verification does NOT fail for archived records
   - Archived records remain part of chain (previousHash links remain intact)

5. Retention policy configuration:
   - Parameter: `retentionDays` (configurable, default 90)
   - Archival is manual (not automatic)
   - Document retention policy in config

**Acceptance Criteria:**
- Archive API sets isArchived and archivedAt
- Archived records remain in chain (do not break verification)
- Queries can filter archived records in or out
- Verification does not falsely report breaks for archived records
- Archived records are marked in query responses

**Risks:**
- Verification incorrectly reports archived records as chain breaks
- Query filtering logic broken by archival changes

**Mitigations:**
- Test verification with mixed archived/active records
- Test query filtering with various combinations

**Human-Review Checkpoint:** Code review of archival logic and verification updates.

**Proposed Git Commits:**

```
28. src/main/java: Archival schema update and migration
29. src/main/java: Archive API controller and service
30. src/main/java: Query API updated for archival filtering
31. src/main/java: Verification logic updated for archived records
32. src/test: Integration tests for archival and verification
33. docs: Archival policy and retention configuration documentation
```

---

## Stage 6: Scenario B — Structured Redaction

**Intent:** Implement field-level redaction without breaking hash verification.

**Dependencies:** Stage 5 complete (archival)

**Tasks:**

1. Redaction metadata schema:
   - New table: `audit_redactions` (recordId FK, field, redactedAt, redactedBy, reason, originalValueHash)
   - Composite primary key: (recordId, field)

2. Redaction API:
   - PUT /audit/{recordId}/redact
   - Request body: `{ "fields": ["ssn", "creditCard"], "reason": "GDPR request", "redactedBy": "admin-user" }`

3. Redaction service:
   - Compute SHA-256 hash of original value (before redaction)
   - Store redaction metadata
   - Do NOT modify record content or contentHash
   - Mark fields as redacted in persistence layer

4. Query response layer:
   - When returning records, check redaction table
   - Replace redacted field values with "REDACTED" (or null)
   - Include redaction metadata in response (optional field)

5. Verification logic:
   - Verification uses original payload (includes redacted values)
   - Redaction is query-time presentation layer
   - Verification ignores redaction table

6. Documentation:
   - Document design choice (redaction metadata vs. encryption)
   - Explain limitations (metadata stores hash, not value)
   - Explain verification behavior

**Acceptance Criteria:**
- Redaction API stores metadata without modifying content
- Original contentHash remains valid after redaction
- Query responses show redacted fields
- Verification walks complete chain including redacted records (without disclosure)
- Redaction metadata is transparent (auditors can see what was redacted)

**Risks:**
- Redaction metadata leaks information about original values
- Verification logic confused by redacted records
- Concurrent redaction and archival (edge case)

**Mitigations:**
- Document metadata limitations
- Test verification with mixed redacted records
- Test concurrent redaction/archival

**Human-Review Checkpoint:** Code review and approval of redaction design and implementation.

**Proposed Git Commits:**

```
34. src/main/java: Redaction metadata schema and migration
35. src/main/java: Redaction API controller and service
36. src/main/java: Query response layer with redaction masking
37. src/main/java: Verification logic verified with redacted records
38. src/test: Integration tests for redaction
39. docs: Redaction design documentation and trade-offs
```

---

## Stage 7: Scenario B — Verifiable Export

**Intent:** Export records as self-contained, verifiable bundle.

**Dependencies:** Stage 6 complete (redaction)

**Tasks:**

1. Export endpoint:
   - GET /audit/export?resourceId=X
   - GET /audit/export?actorId=Y
   - Query parameters: fromTimestamp, toTimestamp, includeArchived, includeRedactions

2. Export service:
   - Query records matching filter
   - Include all chain metadata (chainPosition, contentHash, previousHash)
   - Compute bundle hash (SHA-256 of all record hashes)
   - Generate export metadata (timestamp, recordCount, startChainPosition, endChainPosition)

3. Bundle format:
   - JSON structure with metadata + records + checkpoint
   - Document schema and versioning

4. Bundle verification (client-side, documented):
   - Recipient verifies internal hash chain integrity
   - Recipient checks boundary consistency (if external checkpoint available)
   - Limitations documented (cannot verify completeness without external state)

5. Testing:
   - Export various record sets (by resourceId, by actorId, filtered by date)
   - Verify exported records chain correctly
   - Verify bundle metadata is accurate

**Acceptance Criteria:**
- Export endpoint returns complete bundle
- Bundle includes all matching records with chain metadata
- Bundle hash is computable and consistent
- Exported records verify internally (hash chain within bundle is valid)
- Limitations documented

**Risks:**
- Export is incomplete (missing records)
- Bundle hash computation incorrect
- Boundary records are ambiguous (first record's previousHash)

**Mitigations:**
- Test export with known record sets
- Manually verify bundle hash for small exports
- Document boundary limitations explicitly

**Human-Review Checkpoint:** Code review and approval of export design and bundle format.

**Proposed Git Commits:**

```
40. src/main/java: Export API controller and service
41. src/main/java: Bundle format schema and serialization
42. src/main/java: Bundle verification helper (client-side docs)
43. src/test: Integration tests for export
44. docs: Bundle format specification and verification guide
45. docs: Export limitations and use cases
```

---

## Stage 8: Scenario C — Clarification & Scoped Implementation

**Intent:** Clarify ambiguous compliance requirement and implement access audit view.

**Dependencies:** Stage 7 complete (Scenario B)

**Tasks:**

1. Requirement clarification:
   - Review ambiguities document (from Stage 1)
   - Document working assumptions (see requirement-analysis.md)
   - Identify what is implemented vs. scoped out

2. Compliance audit schema:
   - New `eventType` values: "CLIENT_ACCOUNT_ACCESS", "ADMIN_DATA_ACCESS", "BREAK_GLASS_ACCESS"
   - Payload extensions: accessType, dataClassification, success, reasonCode, userId, ipAddress, userAgent, dataFieldsAccessed

3. Role-based access control:
   - API requires caller to provide role (e.g., via header or token claim)
   - Only "AUDIT" or "COMPLIANCE" roles can query compliance events
   - Log authorization decisions

4. Compliance query view:
   - GET /audit/compliance?resourceId=ACCOUNT-123&fromTimestamp=T1&toTimestamp=T2
   - Return filtered compliance events
   - Enforce role-based access

5. Compliance export:
   - Export compliance events for a given account
   - Include access summary metadata
   - Signed or checksummed for regulator verification (optional, out of scope if too complex)

6. Documentation:
   - Clarified requirement statement (with assumptions)
   - Scoped-out features (break-glass approval, multi-tenant, regulatory format)
   - Compliance schema and examples
   - RBAC policy

**Acceptance Criteria:**
- Compliance audit events can be written (using new eventType values)
- Compliance query view filters and enforces role-based access
- Compliance export returns complete bundle
- Clarified requirement is documented
- Assumptions are explicit and tracked
- Scoped-out features are listed and justified

**Risks:**
- Clarified requirement still has gaps
- RBAC enforcement incomplete or incorrect
- Scope creep (attempt to implement out-of-scope features)

**Mitigations:**
- Get engineer approval of clarified requirement
- Keep scope narrow (focus on documented assumptions)
- Document future work for scoped-out features

**Human-Review Checkpoint:** Engineer approves clarified requirement, design, and compliance implementation.

**Proposed Git Commits:**

```
46. docs: Clarified compliance requirement and assumptions
47. docs: Scenario C scope boundaries (implemented vs. out of scope)
48. src/main/java: Compliance event schema and validators
49. src/main/java: Compliance query controller and service (with RBAC)
50. src/main/java: Compliance export endpoint
51. src/test: Compliance event writing and querying tests
52. docs: Compliance audit guide and use cases
```

---

## Stage 9: Security & Quality Gates

**Intent:** Apply final security review, linting, and validation.

**Dependencies:** Stage 8 complete (all features)

**Tasks:**

1. Security review:
   - Input validation comprehensive (SQL injection, XSS, etc.)
   - Exception handling does not leak internal state
   - Hash logic manually verified
   - Redaction logic does not leak original values
   - RBAC enforcement correct
   - No hardcoded secrets or credentials

2. Code quality:
   - Linting: checkstyle, SpotBugs
   - Code coverage: target >80% for core logic (hash, chain, redaction)
   - No warnings or suppressed checks without justification

3. Documentation completeness:
   - API documentation (OpenAPI/Swagger, optional but helpful)
   - Configuration documentation (all environment variables)
   - Deployment guide (local SQLite, Docker, PostgreSQL)
   - Troubleshooting guide

4. Performance validation:
   - Verify query performance with 10k+ records
   - Verify verification endpoint completes in reasonable time
   - Document performance characteristics and limits

5. Dependency audit:
   - Check for known vulnerabilities (via Maven plugins)
   - License compliance check
   - Document all dependencies and licenses

**Acceptance Criteria:**
- Security review has no high-severity findings
- Code quality checks pass (linting, coverage, no warnings)
- Documentation is complete and accurate
- Performance is acceptable (documented)
- Dependencies are audited and licensed appropriately

**Risks:**
- Security vulnerabilities discovered (requires fixes)
- Performance issues discovered (requires optimization)
- Code quality gaps (requires refactoring)

**Mitigations:**
- Run security checks early and often
- Performance test throughout development
- Maintain high coding standards

**Human-Review Checkpoint:** Engineer approves security review, code quality assessment, and performance validation.

**Proposed Git Commits:**

```
53. pom.xml: Add checkstyle, SpotBugs, and dependency audit plugins
54. src: Apply linting fixes (if any)
55. docs: Security assessment and findings
56. docs: Code quality report and coverage metrics
57. docs: Performance validation results
58. docs: Dependency audit and license compliance report
```

---

## Stage 10: Final Documentation & Defense Preparation

**Intent:** Complete all documentation and prepare for live defense.

**Dependencies:** Stage 9 complete (security & quality gates)

**Tasks:**

1. Final documentation:
   - README: update with implementation status, setup instructions, test results
   - SETUP.md: step-by-step local development setup
   - TESTING.md: how to run tests, what is covered, known gaps
   - API.md: API endpoint documentation
   - ARCHITECTURE.md: high-level system design, components, data model

2. AI usage log finalization:
   - Document all AI interactions from all stages
   - Summarize what was accepted, modified, rejected
   - Provide rationale for engineering decisions
   - Mark as complete and approved by engineer

3. Live defense preparation:
   - Prepare demo environment (local SQLite setup)
   - Write demo script (write events, query, verify chain, tamper test)
   - Prepare for common questions:
     - Why this architecture?
     - How does hash chain work?
     - What are the limitations?
     - How would you scale to production?
   - Prepare to handle live requirement change (be ready to modify code)

4. Final git log verification:
   - Ensure commit history is clean and meaningful
   - Ensure all work is attributed to engineer (not falsely claimed AI-only)
   - Verify ATTESTATION.md is complete and signed

5. Final checklist:
   - [ ] All source code committed
   - [ ] All tests passing
   - [ ] All documentation complete
   - [ ] AI usage log complete and approved
   - [ ] ATTESTATION.md signed
   - [ ] README points to all artifacts
   - [ ] Setup instructions work on clean machine
   - [ ] Live demo rehearsed

**Acceptance Criteria:**
- Documentation is complete, accurate, and linked
- AI usage log is comprehensive and approved
- Live demo environment works end-to-end
- Git history is clean and verifiable
- ATTESTATION.md is filled in and signed
- All artifacts are in the repository

**Risks:**
- Documentation gaps discovered at last moment
- Demo environment broken
- Git history has issues (merge commits, false authorship)

**Mitigations:**
- Write documentation as you go (don't leave for last)
- Test demo environment on clean machine
- Review git history early and often

**Human-Review Checkpoint:** Engineer approves all documentation and confirms demo environment works.

**Proposed Git Commits:**

```
59. docs: README updated with implementation summary
60. docs: SETUP.md with local development setup
61. docs: TESTING.md with test coverage and known gaps
62. docs: API.md with endpoint documentation
63. docs: ARCHITECTURE.md with system design
64. docs/ai: usage-log.md finalized and approved
65. Final commit: "Step 1 complete: all documentation and planning ready for Stage 2"
```

---

## Summary: Proposed Git Commit Sequence

**Total Proposed Commits: 65**

| Commit Range | Stage | Topic |
|--------------|-------|-------|
| 1-5 | 1 | Repository setup and documentation skeleton |
| 6-12 | 2 | Spring Boot scaffold and database schema |
| 13-21 | 3 | Scenario A core implementation (write, query, verify) |
| 22-27 | 4 | Scenario A testing and tamper demonstration |
| 28-33 | 5 | Scenario B archival and retention |
| 34-39 | 6 | Scenario B redaction |
| 40-45 | 7 | Scenario B bulk export |
| 46-52 | 8 | Scenario C compliance implementation |
| 53-58 | 9 | Security and quality gates |
| 59-65 | 10 | Final documentation and defense prep |

---

## Rollback & Recovery Plan

If a stage fails to meet acceptance criteria:

1. **Identify root cause:** Which requirement or design assumption is wrong?
2. **Escalate to engineer:** Do not attempt to proceed blindly
3. **Options:**
   - Fix the stage: Return to that stage, make corrections, re-test
   - Adjust scope: Reduce scope of that stage, move dropped features to future work
   - Re-examine requirements: Earlier stages may need revision

4. **Document decision:** Add entry to implementation log

---

## Risk Summary

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| Hash chain implementation incorrect | Medium | High | Early hash logic review, test vectors |
| Concurrency bugs (SQLite) | Low | High | Concurrent write testing, PostgreSQL validation |
| Requirement ambiguity blocks progress | Medium | Medium | Explicit assumptions, early clarification |
| Scope creep (more features than planned) | Medium | Medium | Strict scope boundaries, defer to future work |
| Security vulnerabilities | Low | High | Security review in stage 9, manual verification |
| Performance issues | Low | Medium | Perf testing throughout, optimization late |

---

## Timeline Notes

**Estimated Duration:** 2-3 days

- Stage 1: 2-3 hours (planning, documentation)
- Stage 2: 3-4 hours (Spring Boot scaffold)
- Stage 3: 6-8 hours (core API implementation)
- Stage 4: 4-5 hours (testing and validation)
- Stage 5-7: 8-10 hours (Scenario B features)
- Stage 8: 4-6 hours (Scenario C compliance)
- Stage 9: 3-4 hours (security and quality gates)
- Stage 10: 3-4 hours (final documentation and demo prep)

**Total: 36-47 hours**

Actual timeline depends on:
- Complexity of bugs discovered during testing
- Difficulty of requirement clarification
- Availability of engineer for reviews/approvals

---

**Document History:**

| Date | Status | Notes |
|------|--------|-------|
| TODO | DRAFT | Initial plan creation |
| TODO | APPROVED | Pending engineer review |

**Prepared by:** AI-Assisted Planning  
**Approved by:** TODO (engineer sign-off required)
