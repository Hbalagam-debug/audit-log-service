# Scenario B Design Analysis — Retention, Structured Redaction, and Verifiable Bulk Export

**Status:** Proposed  
**Scope:** Design only for Step 4 / Scenario B  
**Implementation status:** Not implemented in this checkpoint  
**Human sign-off:** Required before any code, schema, or test changes

---

## 1. Current Scenario A baseline

This design is constrained by the approved Scenario A implementation already in the repository:

- `contentHash` is computed from:
  - `eventType`
  - `actorId`
  - `resourceType`
  - `resourceId`
  - full `payload`
  - normalized event timestamp
- `chainHash` is computed from:
  - `chainPosition`
  - `previousHash`
  - `contentHash`
- Verification recalculates hashes from the stored `payload_json` and fails on any content mutation.
- The global chain is append-only and ordered by `chain_position`.
- Current query APIs return the original stored payload.
- Current schema has no archival, redaction, export manifest, or signing-key metadata.

**Design consequence:** any Scenario B feature that rewrites `payload_json`, `content_hash`, `previous_hash`, or `chain_hash` for an existing Scenario A record will break verification or require a chain redesign. Scenario B must therefore preserve existing hashed bytes for already-written records unless a future migration explicitly introduces a new hash model.

---

## 2. Retention

### 2.1 Comparison

| Approach | How it works | Advantages | Major problems |
|---|---|---|---|
| Physical deletion | Remove rows from `audit_events` after retention window | Lowest storage usage | Breaks `previousHash` linkage, creates chain gaps, destroys later verifiability |
| Soft deletion | Keep row, mark logically deleted | Preserves storage location and chain continuity | “Deleted” data still exists; semantics can be confusing if delete is treated as destruction |
| Soft archival | Keep row, mark archived and exclude from normal operational queries | Preserves verification, is operationally reversible, explicit about storage reality | Storage keeps growing; not equivalent to erasure |

### 2.2 Recommendation

**Recommend soft archival for the prototype.**

Archived records should:

- remain in `audit_events`
- remain part of the global hash chain
- remain included in verification
- be excluded from normal query responses by default
- be retrievable only when the caller explicitly requests archived records

### 2.3 Verification behavior

Archival metadata is **operational metadata**, not historical event content. Therefore:

- `isArchived` / `archiveState`
- `archivedAt`
- `archivedBy`
- `archivalReason`
- `retentionRunId`

must **not** alter existing `contentHash` or `chainHash`.

Verification should continue to:

1. walk the entire chain ordered by `chain_position`
2. recalculate `contentHash` from the original stored payload
3. validate every `previousHash`
4. validate every `chainHash`
5. include archived records in the chain walk without treating archival as a break

### 2.4 Query behavior

For Scenario B, normal event queries should default to:

- `includeArchived=false`

Rationale:

- Scenario A query semantics are operationally oriented
- archived data is still available for regulated review and verification
- default exclusion reduces accidental use of retired records in normal workflows

### 2.5 Physical-deletion and storage limitations

Physical deletion is incompatible with the current Scenario A chain because removing any row:

- creates a chain-position gap
- destroys the evidence needed to recompute the deleted record’s `contentHash`
- prevents subsequent `previousHash` checks from being completed

Soft archival avoids those failures, but it does **not** solve storage growth. Under this model:

- the primary audit store grows indefinitely
- verification cost grows with chain length
- backups, exports, and recovery time also grow

This prototype should therefore document:

- **physical-deletion limitation:** cannot safely delete from the chain without redesign
- **storage limitation:** archival is logical retention control, not capacity management

### 2.6 Retention operations

Retention should be:

- **configurable** via a retention window
- **dry-run capable**
- **approval-gated**
- **idempotent**

Recommended controls:

- `retentionWindowDays` configuration
- explicit `dryRun` mode
- explicit `approvalRef` or change-ticket reference
- optional `requestedBy` and `approvedBy` administrative metadata
- a deterministic archive cutoff basis, with engineer sign-off required

**Preferred prototype cutoff basis:** `ingestedAt`, not caller-supplied event timestamp.  
Reason: `ingestedAt` is server-controlled and better suited to operational retention execution.

---

## 3. Structured redaction

### 3.1 Why direct payload replacement breaks verification

Current Scenario A hashing is based on the original payload bytes after canonicalization:

```text
contentHash = SHA-256(canonical(eventType, actorId, resourceType, resourceId, payload, timestamp))
```

If a stored payload field is changed from:

```json
{"ssn":"123-45-6789","status":"ACTIVE"}
```

to:

```json
{"ssn":"REDACTED","status":"ACTIVE"}
```

then the canonical bytes change, so:

- recalculated `contentHash` changes
- stored `contentHash` no longer matches
- `chainHash` becomes invalid for that record
- all later records still point to the pre-redaction `chainHash`

So replacing or deleting a hashed payload value in-place is incompatible with the existing Scenario A verification model.

### 3.2 Compared approaches

| Approach | Summary | Pros | Cons |
|---|---|---|---|
| Encryption with key destruction | Store sensitive values encrypted from the start; “redaction” means destroying access to decryption key material | Stronger confidentiality model for future records; closer to crypto-erasure | Requires write-path redesign, key management, new hash model decisions, and does not retroactively fix already-written Scenario A plaintext |
| Field-level salted commitments | Hash each redactable field separately with a salt and persist commitments | Can prove some value existed without storing it in clear in secondary structures | Requires preplanned schema/canonicalization changes, does not remove existing plaintext, and is difficult to apply safely to existing Scenario A rows |
| Redaction overlay + append-only redaction certificate | Keep original stored payload unchanged; store redaction instructions separately; return masked view; append a chained redaction event | Compatible with current Scenario A records and hashes; auditable; small migration surface | Does **not** erase the original plaintext from the database; provides presentation-layer redaction, not cryptographic erasure |

### 3.3 Recommendation

**Recommend redaction overlay plus an append-only `REDACTION_APPLIED` audit event for the prototype.**

This is the most defensible design that is compatible with the current Scenario A implementation because it does **not** rewrite the hashed source record.

### 3.4 What is hashed before and after redaction

#### Before redaction

The original record remains hashed exactly as in Scenario A:

```text
contentHash(original record) = hash of original payload
chainHash(original record) = hash of chainPosition + previousHash + contentHash
```

#### After redaction

The original record’s stored fields remain unchanged:

- `payload_json` remains the original payload
- `contentHash` remains unchanged
- `chainHash` remains unchanged

The system adds two new artifacts instead:

1. **redaction overlay metadata**  
   operational data that tells the response/export layer which JSON pointers are masked

2. **append-only `REDACTION_APPLIED` event**  
   a new normal audit event in the main chain whose payload contains non-sensitive certificate metadata, such as:
   - target record id
   - target record chain position
   - redacted JSON pointers
   - reason code
   - approval reference
   - redaction version
   - digest of the redaction instruction set

The `REDACTION_APPLIED` event is hashed and chained like any other Scenario A event.

### 3.5 Recommended redaction semantics

The masked value shown to API/export consumers should be derived at read time from:

- original stored payload
- active redaction overlay entries for that record

Only allow-listed JSON Pointer targets should be redactable, for example:

- `/payload/ssn`
- `/payload/email`
- `/payload/address/line1`

Do **not** allow redaction of:

- chain metadata
- ids
- timestamps
- `eventType`
- `actorId`
- `resourceType`
- `resourceId`
- `contentHash`
- `previousHash`
- `chainHash`

### 3.6 Unauthorized redaction detection

Unauthorized actions are detected differently depending on what was tampered with:

1. **Unauthorized mutation of the original audit row**
   - existing chain verification detects it immediately through `contentHash` / `chainHash` mismatch

2. **Unauthorized insertion, removal, or modification of redaction overlay metadata**
   - this is **not** detectable by current Scenario A verification alone, because overlay metadata is outside the original hashed event
   - detection depends on:
     - an append-only `REDACTION_APPLIED` event
     - reconciliation logic that ensures overlay rows and redaction certificate events match exactly

3. **Privileged attacker rewriting both business tables and the chain**
   - this remains out of scope under the current architecture
   - as with Scenario A, a fully privileged database attacker could rewrite data unless external trust anchors or signing are added

**Important limitation:** the recommended prototype design provides auditable masking, not tamper-proof erasure of already-stored plaintext.

### 3.7 Can existing Scenario A records be redacted safely?

**Partially, yes — but only as masked presentation, not as irreversible source-data removal.**

Existing Scenario A records can be redacted safely **for API and export views** because:

- their stored payload stays unchanged
- their hashes stay valid
- redaction is applied at response/export time

Existing Scenario A records **cannot** be safely transformed into true cryptographic erasure within the current model because:

- the original payload bytes are already persisted
- those bytes are already the basis of the stored `contentHash`

Therefore:

- **safe now:** masked outputs, auditable redaction certificates
- **not safe now:** in-place source-value removal while preserving existing verification semantics

### 3.8 Logging and disclosure constraints

The system must never place original sensitive values into:

- application logs
- error responses
- redaction certificate payloads
- export manifests

If field evidence is needed, store at most:

- JSON pointer
- reason code
- actor references
- approval reference
- optional value digest if explicitly approved

Even a digest can leak some information for low-entropy fields, so digesting original values should be treated as a reviewed, optional design choice rather than a blanket guarantee.

---

## 4. Bulk export

### 4.1 Scope

Exports should be created for **exactly one** of:

- `actorId`
- `resourceId`

Requests supplying both or neither should be rejected.

### 4.2 Required bundle contents

Export output should include:

- filtered records
- each record’s:
  - `id`
  - `chainPosition`
  - `eventType`
  - `actorId`
  - `resourceType`
  - `resourceId`
  - masked payload view
  - `timestamp`
  - `ingestedAt`
  - `contentHash`
  - `previousHash`
  - `chainHash`
  - `hashVersion`
- canonicalization version
- hash algorithm / chain algorithm version
- query boundaries:
  - export criteria
  - first exported chain position
  - last exported chain position
  - first record `previousHash`
  - last record `chainHash`
- generation time
- manifest
- signature metadata
- redaction certificates related to exported records

### 4.3 Integrity options

| Approach | Pros | Cons |
|---|---|---|
| Unsigned digest | Simple; no key management | No origin authentication; anyone can recompute after tampering |
| HMAC | Strong integrity if all verifiers share a secret | Poor fit for external verification because verifiers need the secret; secret distribution becomes the problem |
| Digital signature | Public verification without sharing the private key; strong provenance story | Requires key lifecycle management and signed-manifest design |

### 4.4 Recommendation

**Recommend digital signature over the export manifest and record set, using a persisted signing key supplied through configuration.**

Preferred prototype choice:

- **signature algorithm:** Ed25519
- **private key:** loaded from configuration / external file / keystore
- **public verification key:** distributed out of band
- **key id:** included in the export manifest

No private signing key, seed, or keystore material should ever be committed to the repository.

### 4.5 Integrity vs completeness

The export can make two different claims:

1. **Integrity claim**
   - the exported bytes were produced by the service
   - the manifest and included records were not changed after signing

2. **Completeness claim**
   - every record matching the export criteria at export time is included

The signature helps with **integrity and provenance**, but completeness still depends on what the service chose to include.

The design should therefore be explicit:

- the signed manifest can assert the exact filter used
- the signed manifest can assert the exact list or count of returned records
- but an external verifier still trusts the service’s completeness statement unless additional external anchoring exists

### 4.6 Non-contiguous exports from a global chain

Exports filtered by `actorId` or `resourceId` will usually be **non-contiguous** within the global chain.

That means:

- exported record at position 900 may have `previousHash` pointing to position 899
- position 899 may belong to another actor/resource and therefore not be exportable in this bundle

As a result:

- an external verifier can recompute each exported record’s `contentHash`
- an external verifier can recompute each exported record’s `chainHash`
- an external verifier can verify direct linkage only when both neighboring records are included
- an external verifier **cannot** prove full chain continuity across omitted global-chain records from the subset alone

The manifest should therefore describe:

- that the export is a **subset view of a global chain**
- that omitted chain positions may exist between exported records
- that unrelated payloads are intentionally **not** included merely to bridge chain gaps

### 4.7 Recommended manifest fields

```json
{
  "manifestVersion": "scenario-b-export-v1",
  "generatedAt": "2026-08-10T22:00:00Z",
  "criteria": {
    "actorId": "user-123",
    "resourceId": null,
    "from": "2026-08-01T00:00:00Z",
    "to": "2026-08-10T00:00:00Z",
    "includeArchived": true
  },
  "recordCount": 2,
  "firstExportedChainPosition": 101,
  "lastExportedChainPosition": 140,
  "firstExportedPreviousHash": "....",
  "lastExportedChainHash": "....",
  "hashVersion": "v1",
  "canonicalizationVersion": "canonical-json-v1",
  "bundleDigestAlgorithm": "SHA-256",
  "signatureAlgorithm": "Ed25519",
  "signingKeyId": "export-key-2026-01",
  "completenessStatement": "Subset export signed by service for the stated criteria; omitted global-chain positions may exist."
}
```

---

## 5. Proposed API contracts

These are proposed contracts only for review.

### 5.1 `POST /audit/retention/run`

**Purpose:** evaluate and optionally apply archival to records older than the approved retention window.

**Request**

```json
{
  "retentionWindowDays": 90,
  "cutoffBasis": "INGESTED_AT",
  "dryRun": true,
  "approvalRef": "CHG-2026-0810-01",
  "requestedBy": "ops-user",
  "approvedBy": "manager-user",
  "reason": "Quarterly retention review"
}
```

**Response**

```json
{
  "status": "DRY_RUN",
  "retentionWindowDays": 90,
  "cutoffBasis": "INGESTED_AT",
  "evaluatedAt": "2026-08-10T22:00:00Z",
  "candidateCount": 120,
  "alreadyArchivedCount": 80,
  "wouldArchiveCount": 40,
  "approvalRef": "CHG-2026-0810-01",
  "requiresHumanApproval": true
}
```

**Notes**

- `dryRun=false` should still require an explicit human approval reference.
- Re-running the same approved operation should be idempotent.

### 5.2 `POST /audit/events/{id}/redactions`

**Purpose:** apply a structured masking overlay to an existing record and append a redaction certificate event.

**Request**

```json
{
  "jsonPointers": [
    "/payload/ssn",
    "/payload/contact/email"
  ],
  "reasonCode": "PRIVACY_REQUEST",
  "justification": "Case-88421",
  "approvalRef": "CHG-2026-0810-02",
  "requestedBy": "privacy-ops",
  "approvedBy": "privacy-manager",
  "dryRun": false
}
```

**Response**

```json
{
  "recordId": "a1b2c3",
  "recordChainPosition": 144,
  "status": "APPLIED",
  "redactedPointers": [
    "/payload/ssn",
    "/payload/contact/email"
  ],
  "alreadyRedactedPointers": [],
  "redactionCertificateEventId": "d4e5f6",
  "appliedAt": "2026-08-10T22:00:00Z",
  "approvalRef": "CHG-2026-0810-02"
}
```

**Notes**

- invalid or non-allow-listed pointers should return HTTP 400
- repeated requests for the same active redactions should return a no-op / idempotent success result

### 5.3 `GET /audit/exports?actorId=...`

### 5.4 `GET /audit/exports?resourceId=...`

**Purpose:** generate a signed, verifiable subset export for exactly one actor or one resource.

**Query parameters**

- exactly one of `actorId` or `resourceId`
- optional `from`
- optional `to`
- optional `includeArchived` (recommended default: `true`)

**Response**

```json
{
  "manifest": {
    "manifestVersion": "scenario-b-export-v1",
    "generatedAt": "2026-08-10T22:00:00Z",
    "criteria": {
      "actorId": "user-123",
      "resourceId": null,
      "from": null,
      "to": null,
      "includeArchived": true
    },
    "recordCount": 2,
    "firstExportedChainPosition": 10,
    "lastExportedChainPosition": 25,
    "firstExportedPreviousHash": "....",
    "lastExportedChainHash": "....",
    "hashVersion": "v1",
    "canonicalizationVersion": "canonical-json-v1",
    "bundleDigestAlgorithm": "SHA-256",
    "signatureAlgorithm": "Ed25519",
    "signingKeyId": "export-key-2026-01"
  },
  "records": [
    {
      "id": "....",
      "chainPosition": 10,
      "eventType": "USER_LOGIN",
      "actorId": "user-123",
      "resourceType": "ACCOUNT",
      "resourceId": "acc-1",
      "payload": {
        "email": "REDACTED"
      },
      "timestamp": "2026-08-10T20:00:00Z",
      "ingestedAt": "2026-08-10T20:00:01Z",
      "contentHash": "....",
      "previousHash": "....",
      "chainHash": "....",
      "hashVersion": "v1",
      "archived": false
    }
  ],
  "redactionCertificates": [
    {
      "targetRecordId": "....",
      "targetChainPosition": 10,
      "jsonPointers": [
        "/payload/email"
      ],
      "reasonCode": "PRIVACY_REQUEST",
      "appliedAt": "2026-08-10T21:00:00Z"
    }
  ],
  "bundleDigest": "....",
  "signature": "base64url-signature"
}
```

---

## 6. Security requirements for Scenario B

### 6.1 Required controls

- No destructive or privacy-impacting operation without explicit human approval.
- Production authorization is required for retention, redaction, and export, but **authorization implementation remains out of scope in this checkpoint**.
- No secrets, signing keys, or keystore material may be committed.
- JSON pointers must be syntactically validated and checked against an allow-list of redactable fields.
- Retention and redaction operations must be idempotent.
- Administrative operations must themselves be audit-logged.

### 6.2 Administrative audit logging

Recommended append-only administrative events:

- `RETENTION_RUN_EXECUTED`
- `REDACTION_APPLIED`
- `EXPORT_GENERATED`

Each should capture only non-sensitive operational metadata, for example:

- approval reference
- requested by / approved by identifiers
- target record ids or selection criteria
- counts
- timestamps
- export digest / signature key id

Do **not** include:

- original sensitive values
- plaintext signing material
- full masked/unmasked payload snapshots in admin logs

---

## 7. Testing plan

### 7.1 Unit tests

- retention cutoff calculation by `INGESTED_AT`
- dry-run candidate counting
- idempotent archival state transitions
- JSON Pointer validation
- allow-list enforcement for redactable fields
- masked view generation from overlay metadata
- redaction certificate payload construction
- export manifest canonicalization
- bundle digest generation
- signature generation and signature verification

### 7.2 Integration tests

- archive eligible records while preserving verification success
- default queries exclude archived records
- explicit query flag includes archived records
- verification includes archived rows
- apply redaction to allow-listed fields and return masked values
- redacted records still verify using original payload
- append `REDACTION_APPLIED` event after redaction
- export by `actorId`
- export by `resourceId`
- export with archived records included
- export with redacted records produces masked output and related redaction certificates

### 7.3 Failure tests

- reject retention run without approval reference
- reject redaction of unknown record id
- reject non-allow-listed JSON pointer
- reject malformed JSON pointer
- reject export request with both `actorId` and `resourceId`
- reject export request with neither `actorId` nor `resourceId`
- reject missing signing key configuration

### 7.4 Idempotency tests

- re-running the same retention command archives zero new records
- reapplying the same redaction request is a no-op
- repeated export with unchanged source data yields a different generation timestamp but stable record set and independently verifiable signature over the emitted manifest

### 7.5 Authorization-placeholder tests

- endpoints return placeholder forbidden/unauthorized behavior once authorization hooks are introduced
- approval metadata is required even before real auth is implemented

### 7.6 Archive-boundary tests

- archive exactly-at-threshold versus just-before-threshold records
- verify cursor/query behavior around archived and active neighbors
- verify non-archived newer records still paginate correctly when older archived records are excluded by default

### 7.7 Redaction tests

- redact nested field via JSON pointer
- redact multiple fields in one request
- ensure original sensitive values never appear in response or error payloads
- detect mismatch between overlay metadata and `REDACTION_APPLIED` certificate during reconciliation

### 7.8 Offline export verification tests

- verify signed export with public key only
- verify recomputed bundle digest matches manifest
- verify each included record’s `contentHash`
- verify each included record’s `chainHash`
- demonstrate that omitted global-chain neighbors prevent full continuity proof for subset exports

---

## 8. Decision records

Each proposed decision below includes the required review fields.

### Decision B1 — Retention model

**Context**  
Scenario A uses one immutable global chain. Physical deletion breaks that chain. Scenario B needs retention behavior without invalidating verification.

**Alternatives**  
1. Physical deletion from `audit_events`  
2. Soft deletion with “deleted” semantics  
3. Soft archival with explicit archive state

**Recommendation**  
Use **soft archival** for the prototype.

**Trade-offs**  
- preserves verification and reversibility
- keeps storage growth in the primary database
- is honest about operational rather than physical deletion

**Limitations**  
- archived records still physically exist
- does not solve long-term storage pressure
- does not satisfy true erasure requirements

**Migration impact**  
- requires operational metadata and query filtering updates
- does not require hash migration for existing records

**Human sign-off required**  
Yes — retention semantics affect compliance, privacy, and operational expectations.

### Decision B2 — Archive metadata excluded from hashes

**Context**  
Scenario A `contentHash` excludes operational metadata. Retention requires archive flags and timestamps after original write time.

**Alternatives**  
1. Rehash records when archived  
2. Store archive metadata outside the chain entirely  
3. Store archive metadata operationally but exclude it from existing hashes

**Recommendation**  
Keep archive metadata **outside existing hash inputs**.

**Trade-offs**  
- preserves Scenario A verification
- requires clear documentation that archive state is operational, not historical event content

**Limitations**  
- archive flag changes are not intrinsically covered by the original record hash
- stronger detection would require chained admin events and reconciliation

**Migration impact**  
- no backfill of event hashes
- verification logic can remain based on original payload

**Human sign-off required**  
Yes — determines what the chain proves versus what admin metadata proves.

### Decision B3 — Retention execution model

**Context**  
Retention is privacy/compliance-sensitive and can affect discoverability of records in normal operations.

**Alternatives**  
1. Automatic scheduled archival without approval  
2. Manual archival one record at a time  
3. Retention run with configurable window, dry-run mode, and explicit approval

**Recommendation**  
Use an explicit **retention run** with:

- configurable window
- dry-run preview
- explicit approval reference
- idempotent apply step

**Trade-offs**  
- safer and more reviewable
- slower than automatic cleanup
- adds administrative workflow complexity

**Limitations**  
- no built-in authorization yet
- still depends on disciplined human approval process

**Migration impact**  
- requires retention-run metadata and admin-event design
- no change to existing record hashes

**Human sign-off required**  
Yes — this is an operational governance control.

### Decision B4 — Prototype redaction model

**Context**  
Existing Scenario A records already store plaintext payloads whose exact bytes are the basis of `contentHash`.

**Alternatives**  
1. Encryption with key destruction  
2. Field-level salted commitments  
3. Redaction overlay plus append-only redaction certificate

**Recommendation**  
Use **redaction overlay plus `REDACTION_APPLIED` certificate event** for the prototype.

**Trade-offs**  
- compatible with existing records and verification
- supports masked API/export views
- does not deliver true source-data erasure

**Limitations**  
- original plaintext remains in the primary database
- unauthorized overlay changes require reconciliation, not just existing chain verification

**Migration impact**  
- compatible with existing Scenario A records
- future stronger-redaction model can be introduced for newly written records without rewriting old chain entries

**Human sign-off required**  
Yes — redaction semantics are high-risk and easy to overstate.

### Decision B5 — Redaction scope and target controls

**Context**  
Redaction must be structured, safe, and non-destructive. Arbitrary field mutation could break business semantics or chain interpretation.

**Alternatives**  
1. Allow any JSON Pointer  
2. Allow only top-level payload keys  
3. Validate JSON Pointer syntax and restrict targets to an allow-list

**Recommendation**  
Use **validated JSON Pointers plus an allow-list of redactable fields**.

**Trade-offs**  
- safer and more auditable
- less flexible for ad hoc data shapes

**Limitations**  
- allow-list maintenance becomes an operational task
- nested arrays/complex payloads may need special-case handling

**Migration impact**  
- no hash migration
- requires response/export masking logic and admin review model

**Human sign-off required**  
Yes — determines what data the system will permit operators to conceal.

### Decision B6 — Export trust model

**Context**  
Subset exports need independent verification, but Scenario A uses a global chain and filtered exports are usually non-contiguous.

**Alternatives**  
1. Unsigned digest only  
2. HMAC  
3. Digital signature over manifest and record set

**Recommendation**  
Use a **digital signature**, preferably Ed25519, with a persisted private signing key supplied through configuration.

**Trade-offs**  
- best fit for third-party verification
- introduces key lifecycle work and key-rotation planning

**Limitations**  
- signature proves origin and integrity, not absolute completeness
- subset exports cannot independently prove continuity across omitted chain positions

**Migration impact**  
- requires export manifest versioning and key-id handling
- no need to rewrite existing audit events

**Human sign-off required**  
Yes — signing design creates external trust assertions.

---

## 9. Summary for approval

For Step 4 implementation, this design recommends:

1. **Retention:** soft archival only, excluded from normal queries by default, still included in verification
2. **Redaction:** overlay-based masked views plus append-only `REDACTION_APPLIED` event, with explicit limitation that existing Scenario A records cannot be irreversibly erased without redesign
3. **Export:** signed subset bundles for exactly one `actorId` or one `resourceId`, with explicit integrity/completeness boundaries

**This checkpoint intentionally stops at design analysis. No production code, schema, or tests were modified.**
