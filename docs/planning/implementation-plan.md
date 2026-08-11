# Implementation Plan — Audit Log Service

Status: Partially implemented
Human sign-off required: Yes
Scope: Checkpoints A-E are implemented in the local prototype; Checkpoint F remains the review baseline.

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

## Notes

- The implementation order above still reflects the approved sequence for the prototype.
- Current code now covers Checkpoints A-E without authentication and without historical row rewrites.
