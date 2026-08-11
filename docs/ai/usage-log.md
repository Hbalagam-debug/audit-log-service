# AI Usage Log — Audit Log Service

## Overview

This log records design-analysis and documentation updates performed with AI assistance.

## Entry: Scenario B design review refresh (retention, redaction, export)

Date: 2026-08-10

Summary:
- Revised the Scenario B architecture documentation to reflect the approved human-review direction.
- Updated the final recommendation to state: retention = same-table soft archival; redaction = hybrid legacy overlay plus encryption-at-write/key destruction for new records; export = canonical JSON bundle plus Ed25519 signature.
- Added explicit sections for alternatives considered but rejected, selected approaches, prototype limitations, deferred production features, and human approval status.
- Updated the proposed database model to include encryption key reference, encryption algorithm/version, encrypted JSON-pointer metadata, key status or key-destruction evidence, and the requirement to avoid plaintext key material.
- Reframed the implementation plan into independent checkpoints A through F.
- Marked the status as Proposed and human sign-off required.

Scope note:
- This entry covers documentation revision only.
- No production code, SQL schema, configuration, or tests were implemented.

## Entry: Scenario B retention checkpoint failure diagnosis and repair

Date: 2026-08-11

Failing output captured before repair:
- `RetentionControllerIntegrationTest.testQueryExcludesArchivedByDefaultAndIncludesWhenRequested`: expected 1 item but received 2.
- `AuditLogServiceApplicationTests.contextLoads`: `ApplicationContext` failed while initializing JDBC/schema state.
- `RetentionControllerIntegrationTest.testAppliedRunArchivesEligibleRowsAndAppendsCertificateEvent`: expected one `RETENTION_RUN_EXECUTED` event but query returned zero rows.
- `RetentionControllerIntegrationTest.testApplyWithoutApprovalDataReturns400`: `IllegalArgumentException` escaped MockMvc instead of returning HTTP 400.

Diagnosed causes:
- The archived-query failure was a test expectation issue, not a repository filtering bug: default queries correctly excluded the archived historical row, but still returned the active `RECENT_EVENT` plus the legitimate `RETENTION_RUN_EXECUTED` certificate event.
- The context smoke test used a persistent SQLite file and stale startup exclusions; on an existing pre-retention database, `schema.sql` attempted to create retention indexes before the runtime initializer could add the new columns.
- The certificate-event failure was caused by production code not linking the appended certificate event back to `retention_run_id`, so the test query found zero matching rows even though the event existed.
- The HTTP 400 failure came from the standalone retention integration test not wiring the existing global `ApiExceptionHandler`, combined with retention request validation living only in service-layer exceptions.

Accepted fixes:
- Kept the repository query contract unchanged and updated the retention query test to assert the documented behavior: archived rows are excluded by default, while non-archived certificate events remain visible.
- Removed the controller-local ad hoc exception mapping, enabled `@Valid` on the retention endpoint, added bean validation to `RetentionRunRequest`, introduced a retention-specific validation exception, and routed standalone MockMvc through the existing global `ApiExceptionHandler`.
- Preserved append-only retention behavior and updated the certificate creation path to stamp the new `RETENTION_RUN_EXECUTED` row with `retention_run_id`.
- Made startup safer for existing SQLite databases by leaving retention-column indexes to `DatabaseSchemaInitializer` instead of creating them unconditionally in `schema.sql`.
- Updated the application context smoke test to use the real test SQLite datasource with an isolated temporary database and no `DataSourceAutoConfiguration` exclusion.

Rejected suggestions:
- Rejected hiding `RETENTION_RUN_EXECUTED` from default queries just to make the test pass, because certificate events are valid non-archived audit events and the API contract does not exclude them.
- Rejected weakening or removing the failing assertions, skipping tests, or inserting certificate rows directly from the test, because those would mask production behavior instead of verifying it.

## Entry: Scenario B checkpoint 3 encryption-at-write and key-destruction implementation

Date: 2026-08-11

Prompt summary:
- Implement only Scenario B Checkpoint 3.
- Encrypt configured sensitive payload pointers for new records before persistence.
- Preserve legacy overlay masking for historical plaintext rows.
- Destroy or disable access to wrapped DEKs on encrypted redaction, append `REDACTION_APPLIED`, and keep `payload_json` plus historical hashes immutable.
- Update tests and documentation, but do not add export, external KMS, authentication, or data rewriting.

Accepted changes:
- Added typed encryption configuration with fail-fast validation for version, algorithm, pointer syntax, overlapping pointers, and required master-key presence.
- Added a shared RFC 6901 pointer utility so event creation, response rendering, legacy masking, and encrypted-key destruction use the same canonical pointer behavior.
- Implemented a local prototype key-provider abstraction using AES-256-GCM field encryption and AES-GCM wrapped DEKs stored only as non-plaintext metadata in `audit_event_encryption_keys`.
- Modified event creation so configured payload pointers are encrypted before `payload_json` persistence and before `contentHash` calculation.
- Extended response rendering so active encrypted fields decrypt for API reads, while destroyed keys return `[REDACTED]` and tampered or unavailable encrypted payloads fail safely.
- Extended `POST /audit/events/{id}/redactions` to support hybrid requests containing both legacy overlay pointers and encrypted pointers, with one append-only certificate event and idempotent destroyed-key handling.
- Added unit and integration tests covering encryption round trips, nonce uniqueness, AAD binding, wrong-key failures, certificate hygiene, mixed redaction, and verification after ciphertext tampering.
- Updated README and architecture documentation to distinguish legacy presentation masking from new-record cryptographic redaction, and to document the local-provider and backup limitations.

Human modifications and engineering judgment:
- Chose one DEK per encrypted pointer instead of grouped pointers so the prototype can destroy a single sensitive field without silently redacting unrelated encrypted fields.
- Kept verification over the stored encrypted representation to preserve the existing canonical hashing and chain semantics instead of introducing any decrypt-then-hash branch.
- Supplied a test-only encryption key through `DynamicPropertySource` rather than source-controlled YAML so the repository still has no default usable runtime secret.

Rejected suggestions:
- Rejected rewriting historical legacy payloads or rehashing old rows, because Checkpoint 3 explicitly preserves Checkpoint 2 legacy overlay semantics.
- Rejected storing plaintext, sensitive-value hashes, ciphertext copies, or wrapped-key bytes inside certificate events, because the certificate must remain non-sensitive metadata only.
- Rejected adding a default operational master key to application configuration, because startup must fail fast when encryption is enabled without explicit key configuration.
