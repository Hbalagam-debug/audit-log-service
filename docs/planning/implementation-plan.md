# Implementation Plan — Audit Log Service

Status: Partially implemented
Human sign-off required: Yes
Scope: Scenario B Checkpoints A-E are implemented in the local prototype; Scenario B Checkpoint F and Scenario C implementation remain pending.

## Overview

The next implementation work is divided into independent checkpoints so that retention, legacy redaction, new-record encryption, and export logic can be reviewed and tested in small, verifiable increments.

## Checkpoint A — Retention implementation and tests

- Implement same-table soft archival.
- Add archive metadata and retention certificate events.
- Validate default query behavior and includeArchived handling.

## Checkpoint B — Legacy redaction overlay and certificate tests

- Implement read-time masking for legacy records.
- Append REDACTION_APPLIED certificate events.
- Verify that historical rows remain unchanged and that the design is documented as presentation masking rather than cryptographic erasure.

## Checkpoint C — New-record sensitive-field encryption and key-destruction tests

- Implement JSON-pointer-based encryption-at-write for new records.
- Add envelope-encryption metadata and a configurable key-provider abstraction.
- Validate key destruction or disablement behavior and [REDACTED] rendering.

## Checkpoint D — Export manifest/digest implementation

- Implement canonical JSON bundle generation.
- Compute deterministic SHA-256 digests.
- Include manifest metadata for positions, previousHash, chainHash, and sorted record digests.
- Status: Implemented in code on 2026-08-11.

## Checkpoint E — Export signature implementation

- Implement Ed25519 signing and verification.
- Add signingKeyId and signature metadata.
- Validate public-key-based verification.
- Status: Implemented in code on 2026-08-11 using Ed25519 signatures over the raw bytes decoded from `bundleDigest`.

## Checkpoint F — Complete integration tests and documentation

- Run end-to-end integration tests across retention, redaction, and export behavior.
- Update the architecture documentation and implementation notes to reflect the approved prototype design.
- Validate automated tests, manual validation, commit and push.
- Status: **COMPLETED on 2026-08-11** — All integration tests passing, documentation updated, changes committed and pushed to repository.

## Checkpoint G — Scenario C single hybrid architecture design

- Normalize the ambiguous compliance requirement for regulatory access reporting.
- Define and select a single cohesive hybrid architecture: direct queries over immutable `audit_events` + reuse of Scenario B Ed25519 signed export.
- Document normalized client-account access-event taxonomy and mandatory event fields.
- Explicitly document that signatures prove integrity/authenticity, not completeness.
- Document `COMPLIANCE_REPORT_GENERATED` certificate event and its exclusion from client-data-access results.
- Define GET vs POST endpoint distinction (unsigned interactive queries vs. signed regulatory bundles).
- Define certificate-event flow with safe metadata only.
- Document unresolved Product/Compliance questions separate from prototype assumptions.
- Replace multi-approach comparison matrix with "Alternatives considered but rejected" rationale.
- Status: **DESIGN-ONLY checkpoint completed on 2026-08-11** — no production code/schema/test changes.

## Checkpoint H — Scenario C prototype implementation and tests

- Implement normalized access-report query behavior over immutable audit events.
- Add bounded UTC range validation, account/resource selector constraints, and cursor pagination.
- Reuse Scenario B masking and optional signed bundle export for compliance delivery.
- Append report-generation certificate events and add integration/unit test coverage.

### Checkpoint H1 — Interactive compliance report (COMPLETED 2026-08-11)

- Implemented `GET /audit/compliance/access-report`.
- Returns unsigned cursor-paginated JSON; requires exactly one selector (accountId or resourceId).
- Enforces bounded UTC from/to with configurable 90-day maximum window and 200-event page limit.
- Filters to five canonical access-event types; always excludes `COMPLIANCE_REPORT_GENERATED`.
- Excludes archived events by default; `includeArchived=true` to include.
- Applies Scenario B masking and destroyed-key redaction.
- 11 integration tests + 13 unit tests — all passing.

### Checkpoint H2 — Signed regulatory bundle (COMPLETED 2026-08-11)

- Implemented `POST /audit/compliance/access-report/bundle`.
- Accepts same selection criteria as H1 plus mandatory `approvalRef` and optional `reasonCode`.
- Applies identical taxonomy filter, archival rules, masking, and redaction as H1.
- Reuses Scenario B `ExportDigestSupport` and `ExportSignatureSupport` for canonical digest + Ed25519 signing.
- Signature proves integrity and signer authenticity; does NOT claim absolute completeness.
- Bundle format compatible with `ExportVerifier` for offline verification.
- Appends one `COMPLIANCE_REPORT_GENERATED` certificate event after successful bundle creation; cert event is excluded from the bundle it certifies.
- Certificate payload contains only safe metadata: `approvalRef`, `reasonCode`, `recordCount`, `bundleDigest`, `criteriaDigest`, `generatedAt`, `signingKeyId` — no raw account numbers or PII.
- Atomicity: cert event is never appended if bundle signing fails.
- 11 integration tests + 11 unit tests — all passing.

## Notes

- The implementation order reflects the approved sequence for the prototype.
- Current code covers Scenario B Checkpoints A–F and Scenario C Checkpoints G–H, all completed and committed.
- Scenario C implementation uses one single cohesive hybrid architecture: direct queries over `audit_events` + Scenario B Ed25519 signed export reuse.
- Rejected alternatives explicitly documented: dedicated compliance read model (duplication/drift risk), external warehouse (operational complexity), signed-export-only (lacks interactive queries).
- Unresolved Product/Compliance questions clearly separated from prototype assumptions.

