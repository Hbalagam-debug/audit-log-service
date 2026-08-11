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
