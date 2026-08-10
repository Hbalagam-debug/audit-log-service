# Architecture Decision Records — Audit Log Service

**Document Status:** Step 1 — Technical Decisions  
**Last Updated:** TODO (set by engineer)  
**Approved By:** TODO (pending engineer sign-off)

---

## ADR-001: Java 17 & Spring Boot Framework Selection

**Status:** Proposed (pending engineer approval)

**Context:**

The assignment requires a tamper-evident audit log service with requirements for reliability, maintainability, and future migration to production infrastructure. Java and Spring Boot are industry-standard choices for enterprise systems.

**Decision:**

Use Java 17 (LTS) and Spring Boot 3.x as the application framework and runtime.

**Alternatives Considered:**

1. **Go** (lightweight, fast, concurrent)
   - Pros: Simpler concurrency, smaller binary, faster startup
   - Cons: Smaller ecosystem, fewer enterprise auditing libraries, less familiar in legacy org contexts

2. **Node.js / TypeScript** (event-driven, JavaScript ecosystem)
   - Pros: Fast development, rich npm ecosystem
   - Cons: Less suitable for transactional consistency, smaller production footprint in banking

3. **C# / .NET** (enterprise, mature)
   - Pros: Excellent framework, strong typing, Windows-friendly
   - Cons: Primary audience (Charles Schwab) is Java-heavy, licensing concerns

4. **Rust** (memory safety, performance)
   - Pros: Exceptional performance, memory safety
   - Cons: Steep learning curve, build times, overkill for prototype

**Benefits:**

- **Ecosystem:** Spring ecosystem provides database, web, testing, and security libraries out of the box
- **Enterprise Maturity:** Proven in production systems, well-understood failure modes
- **Type Safety:** Java's strong typing catches errors at compile time
- **LTS:** Java 17 is an LTS release with extended support
- **Familiarity:** Assessment context assumes Java/JVM knowledge

**Trade-offs:**

- **Startup Time:** Spring Boot startup slower than Go or Node.js (acceptable for this scale)
- **Memory:** JVM overhead vs. lighter runtimes (acceptable for this scale)
- **Complexity:** Framework complexity vs. simpler alternatives (mitigated by Spring Boot)

**Limitations:**

- Requires JVM installation and configuration
- Larger artifact size and memory footprint
- Slower startup time (not critical for single-instance prototype)

**Implementation Notes:**

- Use Spring Boot 3.x (supports Java 17+, includes Spring Data, Spring Test, Spring Web)
- Maven for build and dependency management
- SLF4J + Logback for logging

---

## ADR-002: SQLite-First with PostgreSQL Migration Readiness

**Status:** Proposed (pending engineer approval)

**Context:**

The assignment requires a prototype suitable for initial local development and testing, but production deployment must support PostgreSQL. The system must be designed to migrate between SQLite and PostgreSQL without application changes.

**Decision:**

Use SQLite as the database for prototype development and testing. Architect all database access to be database-agnostic, using standard SQL where possible and documenting any database-specific behavior.

**Alternatives Considered:**

1. **PostgreSQL Only** (full production-target from start)
   - Pros: No migration needed, production-ready features
   - Cons: Overhead for local development, slower test setup

2. **In-Memory H2 for Testing** (separate local/prod DB)
   - Pros: Fast tests, clean test isolation
   - Cons: H2 and SQLite/PostgreSQL behavior may diverge, harder to test real persistence

3. **No Database / Mock** (test-driven mock data)
   - Pros: Fastest tests, no DB setup
   - Cons: Misses real DB behavior, redaction and archival logic requires real persistence

**Benefits:**

- **Fast Local Development:** SQLite requires no server setup, embedded in application
- **Repeatable Testing:** Each test can have its own database file
- **Production Migration Path:** PostgreSQL switch requires only configuration change
- **Minimal Dependencies:** SQLite is file-based, no external services needed

**Trade-offs:**

- **Concurrency:** SQLite single-writer, multiple-reader (adequate for prototype)
- **Production Concerns:** SQLite not suitable for production (limited concurrency, no replication)
- **Migration Risk:** Application logic might rely on SQLite-specific behavior

**Limitations:**

- Single-instance only (no distributed consistency for prototype)
- Locking is application-level (SQLite) vs. database-level (PostgreSQL)
- Performance characteristics differ (adequate for prototype scale)

**Implementation Notes:**

- Use JDBC (Spring Data JDBC) for direct SQL control
- Avoid ORM (Hibernate) to keep database-specific concerns explicit
- Schema must be PostgreSQL-compatible:
  - Use standard SQL features
  - Avoid SQLite-specific functions
  - Use sequences or identity columns for auto-increment (PostgreSQL: SERIAL, SQLite: AUTOINCREMENT)
- Configuration profiles: `application-dev.yml` (SQLite), `application-prod.yml` (PostgreSQL)
- Document all database-specific code with comments

---

## ADR-003: Spring Data JDBC vs. ORM (Hibernate/JPA)

**Status:** Proposed (pending engineer approval)

**Context:**

The application requires direct control over SQL for complex queries (cursor-based pagination, hash chain verification) and needs to explicitly manage immutability and concurrency. ORM frameworks add abstraction layers that obscure database behavior.

**Decision:**

Use Spring Data JDBC with direct SQL queries via `JdbcTemplate` or Spring Data JDBC repositories with `@Query` annotations. Avoid ORM frameworks (Hibernate/JPA).

**Alternatives Considered:**

1. **Hibernate / Spring Data JPA** (full ORM)
   - Pros: Automatic relationship management, lazy loading, query optimization
   - Cons: Hides SQL complexity, harder to debug, adds transaction complexity

2. **MyBatis** (SQL mapper, explicit queries)
   - Pros: Fine-grained SQL control, explicit mappings
   - Cons: More boilerplate, separate XML or annotations

3. **No Framework / Raw JDBC** (manual everything)
   - Pros: Complete control, no overhead
   - Cons: Repetitive code, error-prone resource management

**Benefits:**

- **Explicit Control:** SQL is visible and debuggable
- **Performance:** No query generation overhead, no N+1 problems
- **Immutability:** Application-level constraints are explicit
- **Pagination:** Cursor-based pagination is easier to implement with direct SQL
- **Concurrency:** Locking and transaction management are explicit

**Trade-offs:**

- **Verbosity:** More SQL code vs. ORM annotations
- **Manual Mapping:** Entity ↔ Row mapping is manual (Spring Data JDBC minimizes this)
- **Relationship Management:** No automatic join generation (not needed for this schema)

**Limitations:**

- Requires SQL knowledge and careful query review
- Database-specific SQL might be needed (though we'll minimize it)
- Larger codebase for database logic

**Implementation Notes:**

- Use `JdbcTemplate` for complex queries (verification, pagination)
- Use Spring Data JDBC repositories with `@Query` annotations for simpler CRUD
- Entity classes are simple POJOs, not proxies
- No lazy loading or session management complexity

---

## ADR-004: One Globally Ordered Hash Chain with Server-Controlled Ordering

**Status:** Proposed (pending engineer approval)

**Context:**

The assignment requires a tamper-evident log where records cannot be reordered or hidden. Ordering must be deterministic and controlled by the server, not by caller-supplied data.

**Decision:**

Implement a single, globally ordered chain of all audit events. Order is determined by:
1. **Primary:** `chainPosition` — monotonically increasing integer counter
2. **Secondary:** `ingestedAt` — server-assigned timestamp (for chronological reference)

Caller-supplied `timestamp` represents event time and is used for filtering, not chain ordering.

**Alternatives Considered:**

1. **Multiple Independent Chains** (per resource or actor)
   - Pros: Smaller chains, faster verification
   - Cons: Harder to detect cross-resource tampering, complex verification logic

2. **Timestamp-Ordered Chain** (order by caller timestamp)
   - Pros: Matches intuitive event time
   - Cons: Caller can manipulate order by backdating events, injection attacks

3. **Append-Only Log Without Chain** (hashes only, no chain)
   - Pros: Simpler, no chain position management
   - Cons: No detection of missing events (gaps)

**Benefits:**

- **Total Order:** All events have a definitive, unchangeable position
- **Injection Detection:** Missing positions (gaps) are obvious
- **Caller-Proof:** Caller cannot manipulate ordering via timestamp
- **Verification:** Walking the chain verifies both integrity and completeness

**Trade-offs:**

- **Global Sequence:** Requires atomic counter (application-level lock for SQLite, database sequence for PostgreSQL)
- **Single Chain:** Larger chains as volume grows (acceptable for prototype)
- **Concurrency:** Writers must serialize for chain position assignment

**Limitations:**

- Single-instance application (PostgreSQL will need database-level locking)
- Chain grows unbounded (archival is soft-delete, not physical)
- Chain position counter is not distributed (not suitable for clustered deployment)

**Implementation Notes:**

- `chainPosition` is a monotonically increasing integer, starting at 1
- Genesis record has `chainPosition = 1` and `previousHash = SHA256("GENESIS")`
- Each write transaction:
  1. Acquire lock (application-level or database-level)
  2. Read current maximum `chainPosition`
  3. Assign `chainPosition = max + 1`
  4. Compute content hash and previous hash
  5. Persist record
  6. Release lock

---

## ADR-005: SHA-256 for Hashing and Verification

**Status:** Proposed (pending engineer approval)

**Context:**

The hash chain requires a cryptographic hash function that is collision-resistant, deterministic, and widely available.

**Decision:**

Use SHA-256 (Secure Hash Algorithm 256-bit) for all hash computations in the chain.

**Alternatives Considered:**

1. **MD5** (legacy)
   - Pros: Fast, widely available
   - Cons: Cryptographically broken, collision attacks known

2. **SHA-1** (older standard)
   - Pros: Widely available
   - Cons: Collision attacks known, deprecated for cryptographic use

3. **SHA-3 / Keccak** (newer standard)
   - Pros: Newer, theoretically stronger
   - Cons: Slower, less widely implemented

4. **BLAKE2 / BLAKE3** (modern)
   - Pros: Very fast, cryptographically strong
   - Cons: Less standard, Java support varies

**Benefits:**

- **Industry Standard:** NIST-approved, widely used in production systems
- **Collision Resistant:** No known practical attacks
- **Deterministic:** Same input always produces same hash
- **Java Built-in:** No external dependencies (java.security.MessageDigest)
- **Fixed Output:** 256 bits (64 hex characters), compact and standard

**Trade-offs:**

- **Speed:** Slower than MD5 or SHA-1 (negligible for this scale)
- **Output Size:** 256 bits is larger than MD5 (negligible)
- **Future:** If SHA-256 is broken, all past hashes are affected (mitigation: external checkpoint anchoring)

**Limitations:**

- Cannot verify hash chain without recomputing all hashes (acceptable for prototype)
- Hash algorithm is fixed per record (migration to SHA-3 would require schema change)
- No way to prove "pre-image" (original value) without storing it

**Implementation Notes:**

- Use `java.security.MessageDigest.getInstance("SHA-256")`
- Hashes stored as hexadecimal strings (64 characters)
- All hash computations use identical canonicalization rules
- Document hash versioning for future algorithm changes (if needed)

---

## ADR-006: Canonical JSON Serialization for Deterministic Hashing

**Status:** Proposed (pending engineer approval)

**Context:**

JSON serialization can vary (field order, whitespace, unicode escaping), leading to different hashes for identical logical content. The hash chain requires deterministic computation.

**Decision:**

Implement canonical JSON serialization: fixed field order, no whitespace, standardized unicode escaping. Hash computation always uses canonical form.

**Canonical Format Rules:**

1. **Field Order:** Alphabetical by key name (or fixed schema order per record type)
2. **Whitespace:** No extraneous whitespace (no spaces after `:` or `,`)
3. **Unicode Escaping:** Standardized (e.g., `\u` format for non-ASCII)
4. **Numbers:** No unnecessary decimals or exponents (e.g., `1` not `1.0` or `1e0`)
5. **Null Values:** `null` (not omitted or empty string)
6. **Empty Collections:** `[]` for arrays, `{}` for objects

**Alternatives Considered:**

1. **Standard JSON Serialization** (Jackson/Gson defaults)
   - Pros: Built-in libraries
   - Cons: Field order varies, difficult to ensure determinism

2. **Sort Keys Before Hashing** (ad-hoc approach)
   - Pros: Simple
   - Cons: Fragile, easy to miss edge cases

3. **Use Canonical JSON Library** (e.g., RFC 7159 compliant)
   - Pros: Standards-based
   - Cons: Extra dependency, may have Java library gaps

**Benefits:**

- **Deterministic:** Identical content always produces identical hash
- **Verifiable:** Recipients can recompute hash independently
- **Standard:** Canonical JSON is defined and documented
- **Debuggable:** Canonical form is human-readable

**Trade-offs:**

- **Custom Logic:** Must implement canonicalization (not built-in)
- **Testing:** Requires comprehensive test vectors to verify correctness
- **Maintenance:** Any schema changes require canonicalization review

**Limitations:**

- Field order is fixed (schema changes require careful handling)
- Must be carefully maintained across code updates
- Any deviation breaks all downstream hashes

**Implementation Notes:**

- Create utility class: `CanonicalJsonSerializer`
- Test with comprehensive examples (various data types, edge cases)
- Field inclusion rules: Include all record content fields (eventType, actorId, etc.)
- Field exclusion rules: Exclude server-assigned fields (recordId, chainPosition, contentHash, previousHash)
- Document exact canonicalization rules in code and README

---

## ADR-007: Cursor-Based Pagination (Not Offset-Based)

**Status:** Proposed (pending engineer approval)

**Context:**

Offset-based pagination (LIMIT/OFFSET) has problems with concurrent data modification: if records are inserted/deleted between requests, the offset becomes stale and records can be skipped or duplicated. Cursor-based pagination is more robust.

**Decision:**

Implement cursor-based pagination using chain position as the cursor. Cursor is an opaque token (base64-encoded JSON) representing the continuation point.

**Alternatives Considered:**

1. **Offset-Based Pagination** (LIMIT N OFFSET M)
   - Pros: Simple to implement
   - Cons: Skips/duplicates with concurrent modifications

2. **Keyset Pagination** (WHERE id > lastId)
   - Pros: Efficient, handles concurrent changes
   - Cons: Requires stable sort key, less flexible for complex filters

3. **Timestamp-Based Pagination** (WHERE timestamp > lastTimestamp)
   - Pros: Matches business logic (events over time)
   - Cons: Caller timestamp is not chain-ordering (would give wrong results)

**Benefits:**

- **Concurrency-Safe:** Additions/deletions don't affect pagination consistency
- **Stable:** Resuming with same cursor always returns same page
- **Idempotent:** Repeated pagination is deterministic
- **Efficiency:** No OFFSET query (which scans skipped rows)

**Trade-offs:**

- **Opaque Token:** Clients cannot modify cursor (by design)
- **Stateless:** Server does not track pagination state (simpler, scalable)
- **Client Support:** Clients must understand opaque cursor concept

**Limitations:**

- Cursor design is tied to chain position (changes to ordering require cursor redesign)
- Cannot jump to arbitrary page number (must page sequentially)
- Deleted/archived records may affect cursor positioning

**Implementation Notes:**

- Cursor format: base64(`{"chainPosition": 42}`)
- Cursor is stateless (contains query position, not state)
- Each request with cursor returns same page (idempotent)
- Response includes `nextCursor` and `hasMore` for client navigation
- Cursor representation is implementation detail (document as opaque)

---

## ADR-008: API Immutability (No Update/Delete Operations)

**Status:** Proposed (pending engineer approval)

**Context:**

The audit log service is append-only by design: records should never be modified or deleted after creation. The API must enforce this.

**Decision:**

Expose only write (POST /audit/write) and read (GET /audit/query, GET /audit/verify) operations. Do NOT expose any update, delete, or modify operations in the API.

**Alternatives Considered:**

1. **Expose Update/Delete APIs** (with audit trail)
   - Pros: More flexible
   - Cons: Violates append-only principle, complicates chain verification

2. **Soft-Delete with Flags** (logical deletion)
   - Pros: Preserves chain, allows "deletion"
   - Cons: Scenario B feature, implemented separately for archival

3. **Time-Based Retention** (automatic cleanup)
   - Pros: Reduces storage
   - Cons: Scenario B feature, must not break chain

**Benefits:**

- **Simplicity:** No complex update semantics
- **Immutability Guarantee:** Once written, records are permanent (within same DB)
- **Chain Integrity:** No accidental chain breaks
- **Security:** No accidental data corruption
- **Compliance:** Audit trail is truly immutable

**Trade-offs:**

- **Limited Flexibility:** Cannot correct errors in live records (only in schema/code)
- **Storage:** Records cannot be physical deleted (only archived)
- **Retrieval:** No update/patch semantics (must read+write new event if change needed)

**Limitations:**

- Requires careful initial data validation (garbage in, garbage forever)
- Errors require new events to contradict/amend (no direct record updates)

**Implementation Notes:**

- API explicitly rejects PUT, PATCH, DELETE methods
- No update endpoints (not even for admins)
- Archival (Scenario B) uses soft-delete flag, not deletion
- Document immutability guarantee in API documentation

---

## ADR-009: Transaction & Concurrency Control (Application-Level Lock for SQLite, Database Serialization for PostgreSQL)

**Status:** Proposed (pending engineer approval)

**Context:**

Multiple concurrent write requests must be ordered deterministically (chain position must increment atomically). SQLite has limited concurrency, PostgreSQL supports better serialization.

**Decision:**

- **For SQLite (local development):** Use application-level `ReentrantLock` to serialize chain position assignment
- **For PostgreSQL (production):** Use database-level locking (row lock on chain-head row inside transaction)

**Alternatives Considered:**

1. **No Locking** (optimistic concurrency)
   - Pros: High throughput
   - Cons: Chain position collisions, broken chain

2. **Database Transactions Only** (SERIALIZABLE isolation)
   - Pros: Database-enforced consistency
   - Cons: Performance impact, not all databases support well

3. **Distributed Consensus** (Raft, Paxos)
   - Pros: Highly available
   - Cons: Overkill for prototype, complex implementation

**Benefits:**

- **Simplicity:** Application lock is straightforward for single instance
- **Consistency:** Chain position is guaranteed unique and ordered
- **Isolation:** Each write sees consistent chain state
- **Debuggability:** Lock acquisition is visible in code

**Trade-offs:**

- **Throughput:** Lock serializes writes (acceptable for prototype scale)
- **Scalability:** Lock does not scale to multiple instances (documented limitation)
- **PostgreSQL Future:** Database locking is more complex but more scalable

**Limitations:**

- Single instance only (no distributed deployment)
- Lock contention under high write load (acceptable for prototype)
- Clock skew can affect `ingestedAt` timestamp (not chain order)

**Implementation Notes:**

- Create `ChainPositionService` with synchronized `getNextPosition()` method
- SQLite: Application-level `ReentrantLock` in service
- PostgreSQL (future): Database-level row lock with SELECT FOR UPDATE on chain-head row
- Write transaction:
  1. Begin transaction
  2. Acquire lock / SELECT FOR UPDATE
  3. Determine next chain position
  4. Insert record
  5. Commit transaction
  6. Release lock

---

## ADR-010: Hash Algorithm & Canonicalization Versioning

**Status:** Proposed (pending engineer approval)

**Context:**

The hash chain is immutable: changing the hash algorithm or canonicalization rules breaks all past hashes. Future maintenance might require algorithm updates (if SHA-256 is broken) or canonicalization changes.

**Decision:**

Include version metadata in records to support future algorithm changes. Initially, all records use "VERSION=1" (SHA-256 + canonical JSON as defined in ADR-006).

**Versioning Scheme:**

```
VERSION=1: SHA-256 + Canonical JSON (v2024-08)
VERSION=2: (future, if SHA-256 is compromised or canonicalization improves)
```

**Verification Algorithm Behavior:**

- Verification walks chain using record's version
- Recomputes hash using the specified version's algorithm
- Compares against stored hash
- If version is unknown, verification fails with "UNKNOWN_VERSION" violation

**Alternatives Considered:**

1. **No Versioning** (hardcoded algorithm)
   - Pros: Simpler
   - Cons: Cannot evolve; if broken, no recovery path

2. **Global Version** (all records same version)
   - Pros: Simpler enforcement
   - Cons: Cannot gradually migrate old records

3. **Per-Record Version** (each record specifies its version)
   - Pros: Flexible, can mix versions during migration
   - Cons: More complex, larger storage

**Benefits:**

- **Future-Proof:** If SHA-256 is broken, new records can use new algorithm
- **Gradual Migration:** Old records maintain their version, new records use new version
- **Verification Clarity:** Verifier knows exactly which algorithm to use
- **Compliance:** Auditors can see which version was used for each record

**Trade-offs:**

- **Complexity:** Requires versioning logic in verification
- **Storage:** Extra version field per record
- **Migration:** Must handle mixed-version chains during algorithm transitions

**Limitations:**

- Still cannot recover old records if algorithm is broken (version just makes it explicit)
- Versioning doesn't provide long-term cryptographic security (external anchoring required)
- Chain is still vulnerable to complete replacement if attacker controls database

**Implementation Notes:**

- Add `hashVersion` field to audit_events table (default "1")
- Add `hashAlgorithm` field to audit_events table (default "SHA256")
- Add `canonicalizationVersion` field (default "RFC7159-CANONICAL-v1")
- Verification logic:
  ```
  switch(record.hashVersion) {
    case "1": verifyUsingSHA256(record); break;
    case "2": verifyUsingFutureAlgorithm(record); break;
    default: reportViolation("UNKNOWN_HASH_VERSION");
  }
  ```
- Document versioning policy in ADR and code

---

## Summary: Decision Status

| ADR | Topic | Status | Engineer Approval Required |
|-----|-------|--------|---------------------------|
| 001 | Java 17 & Spring Boot | Proposed | Yes |
| 002 | SQLite-First & PostgreSQL Migration | Proposed | Yes |
| 003 | Spring Data JDBC vs. ORM | Proposed | Yes |
| 004 | Global Ordered Chain | Proposed | Yes |
| 005 | SHA-256 Selection | Proposed | Yes |
| 006 | Canonical JSON | Proposed | Yes |
| 007 | Cursor Pagination | Proposed | Yes |
| 008 | API Immutability | Proposed | Yes |
| 009 | Transaction & Concurrency Control | Proposed | Yes |
| 010 | Hash Algorithm Versioning | Proposed | Yes |

---

**Approved Decisions:** None yet (awaiting engineer review)

**Proposed Decisions:** ADR-001 through ADR-010 (require engineer approval)

---

**Document History:**

| Date | Status | Notes |
|------|--------|-------|
| TODO | DRAFT | Initial ADR creation |
| TODO | APPROVED | Pending engineer review |

**Prepared by:** AI-Assisted Architecture  
**Reviewed by:** TODO (engineer sign-off required)  
**Next Step:** Engineer approval before Stage 2 implementation
