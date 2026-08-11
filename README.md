# Audit Log Service — AI-Assisted Engineering Assignment

## Project Status

**Current Phase:** Step 2 — Spring Boot & Database Scaffold

This repository now includes a Maven-based Spring Boot scaffold with a SQLite-backed data layer, schema initialization, repository/service/controller layers, and a basic test suite. The implementation remains a prototype and is intended to satisfy the initial scaffold requirements before later scenario work.

## Objective

Build a tamper-evident audit log service that records an append-only history of events and guarantees that past records cannot be modified or deleted without detection. The service demonstrates requirement understanding, task decomposition, and AI-assisted engineering execution across three progressive scenarios.

## Technology Stack

- **Language:** Java 17
- **Framework:** Spring Boot
- **Build Tool:** Maven
- **Database (Initial):** SQLite (local prototype)
- **Target Database:** PostgreSQL (migration-ready architecture)
- **Hashing:** SHA-256
- **Architecture:** One globally ordered hash chain, server-controlled ordering
- **Pagination:** Cursor-based
- **AI Assistance:** GitHub Copilot / Claude (used with engineering judgment and traceability)

## Scenarios

1. **Scenario A — Core Audit Log:**
   - Write API for append-only event records
   - Query API with filtering and pagination
   - Hash chain for tamper evidence
   - Chain verification endpoint

2. **Scenario B — Retention & Redaction:**
   - Retention policies and archival
   - Structured redaction of sensitive fields
   - Bulk export with independent verification

3. **Scenario C — Compliance Reporting:**
   - Ambiguity clarification and requirement normalization
   - Design of access audit capability
   - Scoped implementation with documented trade-offs

## Documentation

- **[Requirement Analysis](./docs/requirements/requirement-analysis.md)** — Detailed requirements for all scenarios, acceptance criteria, assumptions, non-goals
- **[Implementation Plan](./docs/planning/implementation-plan.md)** — 10-stage breakdown with dependencies, tasks, and Git commit sequence
- **[Architecture Decisions](./docs/architecture/decisions.md)** — Lightweight Architecture Decision Records (ADRs) for key technical choices
- **[AI Usage Log](./docs/ai/usage-log.md)** — Traceability of AI-assisted work: prompted, accepted, modified, rejected, with engineering rationale
- **[Attestation](./ATTESTATION.md)** — Individual integrity attestation

## AI Usage & Engineering Ownership

- AI is used to assist with individual engineering tasks: code generation, debugging, testing, documentation, and review preparation.
- **All AI-generated output must be reviewed and approved by the engineer** before acceptance.
- Dependencies and security logic are validated manually.
- A traceable AI usage log is maintained in the repository showing what was accepted, modified, or rejected, with engineering rationale.
- The engineer retains full ownership of correctness, maintainability, security, and production readiness.

## Development History

This repository preserves genuine development history:
- Work is committed to Git as it progresses
- Commits are made under the engineer's own GitHub identity
- The development history reflects the actual process: planning → design → implementation → testing → validation
- AI usage is logged and traceable within the repository

**Do not submit a snapshot or zip file. The repository itself—with its full commit history—is the submission.**

## Security & Limitations

- Hash chain stored in the same database detects tampering when the verifier has access to a trusted chain state
- An attacker with unrestricted database access could rewrite the complete chain undetected
- Production system should externally anchor or digitally sign trusted checkpoints (out of scope for this prototype)
- Single-instance deployment uses application-level locking; PostgreSQL-targeted design uses database-controlled serialization

## Scenario B prototype behavior

- **Legacy records** still use presentation masking overlays. Redaction for those rows does **not** rewrite `payload_json`, delete plaintext, or claim cryptographic erasure.
- **New records** encrypt configured payload pointers before `payload_json` is stored. The persisted hash chain covers the stored encrypted representation.
- **Key destruction** for encrypted pointers appends a `REDACTION_APPLIED` certificate event and makes future API reads return `[REDACTED]` without modifying stored ciphertext or historical hashes.
- `GET /audit/verify` always verifies the original stored representation, including encrypted payloads, and never depends on response-time decryption.
- This local prototype does **not** implement authorization, external KMS/HSM management, backup purging, or claims of complete erasure from privileged database access, backups, logs, caches, or replicas.

## Local development quick start

The service now fails fast when export signing is enabled and valid Ed25519 signing keys are not configured.

**Safe local PowerShell setup**

```powershell
New-Item -ItemType Directory -Force .\secrets | Out-Null

$encBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($encBytes)
$env:AUDIT_ENCRYPTION_MASTER_KEY_BASE64 = [Convert]::ToBase64String($encBytes)
[Array]::Clear($encBytes, 0, $encBytes.Length)

$env:AUDIT_EXPORT_SIGNING_KEY_ID = "local-export-key-001"
$env:AUDIT_EXPORT_SIGNING_ENABLED = "true"
java -cp target\classes com.auditlog.service.service.ExportSigningKeyGeneratorCli |
  Set-Content -Path .\secrets\export-signing-env.txt

$envLines = Get-Content .\secrets\export-signing-env.txt
$privateLine = $envLines | Where-Object { $_ -like 'AUDIT_EXPORT_SIGNING_PRIVATE_KEY_BASE64=*' }
$publicLine = $envLines | Where-Object { $_ -like 'AUDIT_EXPORT_SIGNING_PUBLIC_KEY_BASE64=*' }
Set-Content -Path .\secrets\audit-export-signing-private.key -Value ($privateLine -replace '^AUDIT_EXPORT_SIGNING_PRIVATE_KEY_BASE64=', '')
Set-Content -Path .\secrets\audit-export-signing-public.key -Value ($publicLine -replace '^AUDIT_EXPORT_SIGNING_PUBLIC_KEY_BASE64=', '')
Remove-Item .\secrets\export-signing-env.txt

$env:AUDIT_EXPORT_SIGNING_PRIVATE_KEY_FILE = (Resolve-Path .\secrets\audit-export-signing-private.key).Path
$env:AUDIT_EXPORT_SIGNING_PUBLIC_KEY_FILE = (Resolve-Path .\secrets\audit-export-signing-public.key).Path

.\mvnw.cmd spring-boot:run
```

Local Base64 fallback is still supported when file paths are not set:

```powershell
$env:AUDIT_EXPORT_SIGNING_PRIVATE_KEY_BASE64 = "<Base64 PKCS#8 Ed25519 private key>"
$env:AUDIT_EXPORT_SIGNING_PUBLIC_KEY_BASE64 = "<Base64 X.509 Ed25519 public key>"
$env:AUDIT_EXPORT_SIGNING_ENABLED = "true"
.\mvnw.cmd spring-boot:run
```

PowerShell snippet to generate a fresh 32-byte encryption key:

```powershell
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$env:AUDIT_ENCRYPTION_MASTER_KEY_BASE64 = [Convert]::ToBase64String($bytes)
[Array]::Clear($bytes, 0, $bytes.Length)
```

> Never commit generated keys, Base64 values, `.db` files, `.env` files, or anything under `secrets/`.

## Run tests

```powershell
.\mvnw.cmd clean test
```

## Redaction examples

Legacy presentation masking:

```json
POST /audit/events/{id}/redactions
{
  "jsonPointers": ["/ipAddress"],
  "reasonCode": "PRIVACY_REQUEST",
  "approvalRef": "CHG-2026-08-10-02",
  "requestedBy": "privacy-user",
  "approvedBy": "privacy-manager"
}
```

Encrypted-pointer cryptographic redaction for new records:

```json
POST /audit/events/{id}/redactions
{
  "jsonPointers": ["/accountNumber"],
  "reasonCode": "PRIVACY_REQUEST",
  "approvalRef": "CHG-2026-08-11-01",
  "requestedBy": "privacy-user",
  "approvedBy": "privacy-manager"
}
```

For encrypted Scenario B records, successful reads return decrypted values while the key is active and `[REDACTED]` after key destruction. For legacy Scenario A rows, masking is still presentation-only and the original plaintext remains in the database.

## Quick Links

- Setup instructions: Run `./mvnw test` (or `mvnw.cmd test` on Windows)
- API documentation: See below
- Test results: See Maven Surefire reports under `target/surefire-reports/`
- Live defense notes: TBD (Post-submission)

## Compliance Reporting API (Scenario C)

### GET /audit/compliance/access-report

Returns an unsigned cursor-paginated JSON report of client account access events.

**Required (exactly one):**
- `accountId` — report for this account
- `resourceId` — report for this resource

**Required:**
- `from` — ISO-8601 UTC start timestamp (inclusive)
- `to` — ISO-8601 UTC end timestamp (exclusive); maximum window 90 days

**Optional:**
- `actorId`, `action`, `outcome` — additional filters
- `includeArchived` — boolean, default `false`
- `cursor`, `limit` — cursor pagination (default page size 50, max 200)

**Event types returned:** `CLIENT_ACCOUNT_DATA_VIEWED`, `CLIENT_ACCOUNT_DATA_SEARCHED`, `CLIENT_ACCOUNT_DATA_EXPORTED`, `CLIENT_ACCOUNT_DATA_UPDATED`, `CLIENT_ACCOUNT_ACCESS_DENIED`. `COMPLIANCE_REPORT_GENERATED` certificate events are always excluded.

**Error responses:** `400` — invalid selector, timestamp, or range.

---

### POST /audit/compliance/access-report/bundle

Generates a signed regulatory access-report bundle. Applies the same event taxonomy, filtering, masking, and archival rules as the GET endpoint.

**Request body (JSON):**

| Field | Required | Description |
|---|---|---|
| `accountId` / `resourceId` | Exactly one | Selector |
| `from` / `to` | Yes | UTC timestamps; max 90-day window |
| `approvalRef` | Yes | Regulatory approval reference |
| `actorId`, `action`, `outcome` | No | Optional filters |
| `includeArchived` | No | Default `false` |
| `reasonCode` | No | Reason code for audit trail |

**Response (201 Created):** Signed JSON bundle with the same structure as `GET /audit/exports` plus `reportType: "COMPLIANCE_ACCESS_REPORT"`, compliance-specific selection metadata, `approvalRef`, and `signatureIntegrityNote`.

**After successful bundle generation:** One `COMPLIANCE_REPORT_GENERATED` certificate event is appended to the audit chain. Its payload contains only safe metadata (`approvalRef`, `reasonCode`, `recordCount`, `bundleDigest`, `criteriaDigest`, `generatedAt`, `signingKeyId`). The certificate event is excluded from the bundle it certifies.

**Signature:** Proves bundle integrity and signer authenticity. Does **not** independently prove absolute completeness of the audit record set.

**Offline verification:** The returned bundle is compatible with `ExportVerifier` for offline signature verification.

**Error responses:** `400` — missing `approvalRef`, invalid selector, or excessive timestamp range.

---

### GET /audit/exports

Returns a deterministic, verifiable JSON bundle of audit events filtered by a single selector.

**Required (exactly one):**
- `actorId` — export all events for this actor
- `resourceId` — export all events for this resource

**Optional:**
- `from` — ISO-8601 UTC timestamp (inclusive lower bound)
- `to` — ISO-8601 UTC timestamp (exclusive upper bound)
- `includeArchived` — boolean, default `false`

**Response bundle fields:**

| Field | Description |
|---|---|
| `manifestVersion` | Always `"1"` |
| `generatedAt` | ISO-8601 UTC timestamp of bundle creation |
| `selection` | Object describing the query parameters used |
| `recordCount` | Number of records in the bundle |
| `firstChainPosition` / `lastChainPosition` | Chain anchors (null if empty) |
| `firstPreviousHash` / `lastChainHash` | Hash chain link anchors |
| `hashVersion` | Always `"v1"` |
| `canonicalizationVersion` | Always `"v1"` |
| `records` | Array of full event records |
| `records[].contentHashVerificationStatus` | `"REPRODUCIBLE_FROM_EXPORT"` unless the exported payload is masked or sanitized for privacy |
| `records[].contentHashVerificationNote` | Explanation when exported presentation prevents independent `contentHash` recomputation |
| `recordsDigest` | SHA-256 of the canonical JSON of the `records` array |
| `redactedRecordsPresent` | `true` if any record has masked fields |
| `redactedRecordsNote` | Explanation when redacted records are present, null otherwise |
| `bundleDigest` | SHA-256 of the canonical JSON of the unsigned bundle: the entire bundle excluding `bundleDigest` and any present or future top-level `signature*` / `signing*` fields |
| `signature` | Ed25519 signature block containing `algorithm`, `keyId`, `value`, and `publicKey` |

**Digest representation:**
- `recordsDigest` is computed from the canonical JSON of the ordered `records` array only.
- `bundleDigest` is computed from the canonical JSON of the full bundle after removing `bundleDigest` itself and any signature-related top-level fields, so signing can be added later without circular hashing.
- Both digests reuse the service's existing canonical JSON serializer, which sorts object keys deterministically.
- **Exactly signed bytes:** the service decodes the lowercase hexadecimal `bundleDigest` into its 32 raw SHA-256 bytes and signs those 32 bytes with Ed25519. It does **not** sign pretty-printed JSON or the hexadecimal text itself.

**Signature block:**

```json
"signature": {
  "algorithm": "Ed25519",
  "keyId": "<configured signing key id>",
  "value": "<Base64 signature>",
  "publicKey": "<Base64 X.509 encoded public key>"
}
```

**Error responses:**
- `400` — both or neither selectors provided; invalid timestamp range
- `413` — result set exceeds `audit.export.max-records` (default 10000)

**Example:**
```
GET /audit/exports?actorId=user-123&from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z
```

### Offline verification

The `ExportVerifier` service can be injected or instantiated independently to verify any exported bundle JSON:

```java
ExportVerifier.VerificationResult result = exportVerifier.verify(bundleJson);
// result.valid()              — true if digests, manifest, and signature all verify
// result.recordsDigestValid() — true if recordsDigest matches
// result.bundleDigestValid()  — true if bundleDigest matches
// result.signatureValid()     — true if the Ed25519 signature verifies
// result.keyId()              — signature keyId from the bundle
// result.errors()             — mismatch and compatibility detail messages
```

For offline CLI-style verification, run the utility main class with a bundle file path. To enforce a trusted key binding, also pass the expected `keyId` and Base64 X.509 public key:

```text
java -cp target\classes com.auditlog.service.service.ExportVerifierCli path\to\bundle.json
java -cp target\classes com.auditlog.service.service.ExportVerifierCli path\to\bundle.json <configured-key-id> <Base64-X509-public-key>
```

## Local signing-key setup

The application starts with export signing disabled by default. It fails fast only when `AUDIT_EXPORT_SIGNING_ENABLED=true` and valid Ed25519 signing keys are not configured.

Configure these environment variables before starting the service locally:

```text
AUDIT_EXPORT_SIGNING_ENABLED=true
AUDIT_EXPORT_SIGNING_KEY_ID=<stable key identifier>
AUDIT_EXPORT_SIGNING_PRIVATE_KEY_FILE=<path to Base64 PKCS#8 Ed25519 private key file>
AUDIT_EXPORT_SIGNING_PUBLIC_KEY_FILE=<path to Base64 X.509 Ed25519 public key file>
```

If file paths are not set, local development may fall back to:

```text
AUDIT_EXPORT_SIGNING_PRIVATE_KEY_BASE64=<Base64 PKCS#8 Ed25519 private key>
AUDIT_EXPORT_SIGNING_PUBLIC_KEY_BASE64=<Base64 X.509 Ed25519 public key>
```

Generate a local test-only key pair with:

```text
java -cp target\classes com.auditlog.service.service.ExportSigningKeyGeneratorCli
```

The generator prints a warning and emits shell-ready Base64 values for the fallback variables above. Prefer writing those values to ignored files under `secrets/` and pointing `AUDIT_EXPORT_SIGNING_PRIVATE_KEY_FILE` / `AUDIT_EXPORT_SIGNING_PUBLIC_KEY_FILE` at those files.

### Key rotation guidance

1. Generate a new Ed25519 key pair outside Git and store it under `secrets/` or your platform secret manager.
2. Assign a new `AUDIT_EXPORT_SIGNING_KEY_ID` value before restarting the service.
3. Update both private and public key references together.
4. Keep the retired public key available to offline verifiers for older bundles until their retention window expires.

Do not commit generated keys, Base64 values, database files, `.env` files, or mounted secret files.

---

**Assignment Source:** Charles Schwab & Co., Inc. — Confidential Interview Assessment
