# Implementation Plan — Audit Log Service

Status: Proposed
Human sign-off required: Yes
Scope: Documentation-first planning only; implementation remains deferred until review is complete.

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

## Checkpoint E — Export signature implementation

- Implement Ed25519 signing and verification.
- Add signingKeyId and signature metadata.
- Validate public-key-based verification.

## Checkpoint F — Complete integration tests and documentation

- Run end-to-end integration tests across retention, redaction, and export behavior.
- Update the architecture documentation and implementation notes to reflect the approved prototype design.

## Notes

- No Java code, SQL schema, configuration, or tests are created in this documentation-only revision.
- The implementation order above reflects the approved sequence for the prototype.
