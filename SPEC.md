# Client360 — Technical Specification

**Version:** 1.0
**Status:** Draft for implementation
**Source of truth:** `PROJECT_IDEA.md`, `PROJECT_DESCRIPTION.md`, `README.md`
**Target metric:** reduce pre-contact preparation time from 15–20 min to 2–3 min.

---

## Table of Contents

1. [Scope & Non-Goals](#1-scope--non-goals)
2. [Glossary](#2-glossary)
3. [System Architecture](#3-system-architecture)
4. [Cross-Cutting Conventions](#4-cross-cutting-conventions)
5. [Module 1 — Client Profile](#5-module-1--client-profile)
6. [Module 2 — Interaction Log](#6-module-2--interaction-log)
7. [Module 3 — Task / Reminder](#7-module-3--task--reminder)
8. [Module 4 — Audit Trail](#8-module-4--audit-trail)
9. [Module 5 — RBAC](#9-module-5--rbac)
10. [Non-Functional Requirements](#10-non-functional-requirements)
11. [Delivery Traceability](#11-delivery-traceability)
12. [Assumptions & Open Questions](#12-assumptions--open-questions)

---

## 1. Scope & Non-Goals

### 1.1 In scope

| # | Module | Roadmap phase | One-line responsibility |
|---|---|---|---|
| 1 | Client Profile | MVP | Unified customer card: identity, KYC, products, ownership |
| 2 | Interaction Log | MVP | Chronological feed of calls, meetings, emails, notes, tickets |
| 3 | Task / Reminder | v3 | Follow-up actions with due dates, SLA and escalation |
| 4 | Audit Trail | v2 | Immutable, tamper-evident record of every mutation |
| 5 | RBAC | v2 | Roles, data scopes, break-glass access, session management |

### 1.2 Non-goals

- **No monetization / billing.** Internal B2B tool (per `PROJECT_IDEA.md` §6).
- **No production AI agents.** No LLM inference in the request path.
- **No core-banking write-back.** Product data is read-only, ingested from the core banking system; Client360 never mutates balances or opens accounts.
- **No customer-facing UI.** All screens are internal (bank staff only).
- **No full-text search over encrypted interaction bodies** — see [4.7](#47-encryption-at-rest) for the deliberate trade-off.

---

## 2. Glossary

| Term | Definition |
|---|---|
| **Client** | A bank customer record in Client360. Mirrors a core-banking CIF, keyed by `external_ref`. |
| **User** | A bank employee with a Client360 login (manager, supervisor, admin, auditor). |
| **Owner manager** | The single user accountable for a client relationship (`clients.owner_manager_id`). |
| **Team** | A supervisor plus their managers. The unit of the `TEAM` data scope. |
| **Interaction** | Any recorded touchpoint with a client: call, meeting, email, chat, note, ticket. |
| **Ticket** | An interaction of type `TICKET` — the only interaction subtype with a lifecycle and SLA. |
| **Scope** | The data-visibility qualifier attached to a permission: `OWN`, `TEAM`, or `ALL`. |
| **Break-glass** | A time-boxed, reason-required grant letting a user access a client outside their scope. |
| **Audit event** | An immutable fact emitted on every mutation, delivered via Kafka to `audit-service`. |
| **Outbox** | Per-service table written in the same DB transaction as the business change, relayed to Kafka. |
| **Minor units** | Integer currency representation (cents). No floating point is used for money anywhere. |

---

## 3. System Architecture

### 3.1 Service topology

```
                       +--------------------------+
   Browser (SPA)  ---> |  API Gateway / Spring    |
                       |  Security filter chain   |
                       +------------+-------------+
             +----------------------+----------------------+
             v                      v                      v
    +----------------+   +---------------------+   +----------------+
    | client-service |   | interaction-service |   |  audit-service |
    |  clients       |   |  interactions       |   |  audit_log     |
    |  products      |   |  attachments        |   |  (read + hash  |
    |  users / RBAC  |   |  tasks              |   |   verify only) |
    +-------+--------+   +----------+----------+   +-------^--------+
            | outbox                | outbox              | consume
            +-----------+-----------+                     |
                        v                                 |
                 +------------------------------------------+
                 |             Apache Kafka                 |
                 |  client.events / interaction.events /    |
                 |  task.events / auth.events               |
                 +------------------------------------------+
```

**Ownership of tables**

| Service | Owns |
|---|---|
| `client-service` | `clients`, `client_products`, `users`, `teams`, `roles`, `permissions`, `role_permissions`, `user_roles`, `access_grants`, `refresh_tokens`, `login_attempts`, `outbox_events` |
| `interaction-service` | `interactions`, `interaction_attachments`, `tasks`, `task_reminders`, `outbox_events` |
| `audit-service` | `audit_log` (partitioned), `audit_export_jobs`, `audit_consumer_state` |

Each service owns a separate PostgreSQL **schema** (one database instance in dev via `docker-compose`, separate instances in production). No service reads another service's tables directly — cross-service reads go through REST, or through denormalized fields carried on Kafka events.

### 3.2 Kafka topics

| Topic | Producer | Key | Partitions | Retention | Consumers |
|---|---|---|---|---|---|
| `client.events` | client-service | `client_id` | 6 | 7 days | audit-service |
| `interaction.events` | interaction-service | `client_id` | 6 | 7 days | audit-service |
| `task.events` | interaction-service | `client_id` | 6 | 7 days | audit-service, notifier (v3) |
| `auth.events` | client-service | `user_id` | 3 | 7 days | audit-service |
| `<topic>.DLT` | consumer-side | original key | 1 | 30 days | manual triage |

**Delivery semantics:** at-least-once (per `PROJECT_IDEA.md` §9). Consumers are idempotent via the unique constraint on `(occurred_at, event_id)` in `audit_log`.

### 3.3 Canonical event envelope

Every message on every topic uses the same envelope. `payload` is event-type specific.

```json
{
  "eventId": "0199a4c2-6f1e-7c3b-9a10-2f8c4d1e5b77",
  "eventType": "client.updated",
  "eventVersion": 1,
  "occurredAt": "2026-09-09T11:42:07.113Z",
  "service": "client-service",
  "actor": {
    "userId": "3f1c…",
    "email": "a.ivanov@bank.example",
    "role": "MANAGER",
    "ip": "10.4.11.87",
    "userAgent": "Mozilla/5.0 …"
  },
  "entity": { "type": "CLIENT", "id": "9b21…" },
  "clientId": "9b21…",
  "requestId": "c4d0…",
  "correlationId": "c4d0…",
  "payload": {
    "action": "UPDATE",
    "changedFields": {
      "segment": { "old": "RETAIL", "new": "PREMIUM" },
      "email":   { "old": "***MASKED***", "new": "***MASKED***" }
    }
  }
}
```

**Rule AR-01 — no plaintext PII on the bus.** Values of fields marked *sensitive* in the data model are replaced with the literal `***MASKED***` in `changedFields`. The audit record proves *that* the field changed, by whom and when — never *to what*. Recovering old plaintext values requires a DBA-level procedure outside the application.

### 3.4 Transactional outbox

Every mutating use case executes:

```
BEGIN;
  INSERT / UPDATE business table;
  INSERT INTO outbox_events (...);
COMMIT;
```

A relay poller (`@Scheduled` every 200 ms, `FOR UPDATE SKIP LOCKED`, batch of 100) publishes rows and stamps `published_at`. This guarantees "audited or not committed" without a distributed transaction, and directly mitigates the *"несогласованность данных при асинхронной записи аудита"* risk in `PROJECT_IDEA.md` §9.

```sql
CREATE TABLE outbox_events (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       UUID        NOT NULL UNIQUE,
    aggregate_type VARCHAR(48) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    topic          VARCHAR(64) NOT NULL,
    partition_key  VARCHAR(64) NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       SMALLINT    NOT NULL DEFAULT 0,
    last_error     TEXT,
    CONSTRAINT ck_outbox_attempts CHECK (attempts BETWEEN 0 AND 100)
);

CREATE INDEX ix_outbox_unpublished ON outbox_events (created_at)
    WHERE published_at IS NULL;
```

---

## 4. Cross-Cutting Conventions

### 4.1 API basics

- Base path: `/api/v1`. Breaking changes bump to `/api/v2`; both run in parallel for one release.
- Content type: `application/json; charset=utf-8`. Errors: `application/problem+json`.
- All timestamps are RFC 3339 **UTC** with milliseconds (`2026-09-09T11:42:07.113Z`). The server rejects local time without an offset.
- All identifiers are **UUID v7** — time-sortable, which makes them safe as a keyset-cursor tiebreaker.
- Money is always `{ "amountMinor": 125000, "currency": "EUR" }`. Never a float.
- `PATCH` bodies are partial: an absent field means unchanged, an explicit `null` means clear. Fields that cannot be cleared reject `null` with `VALIDATION_FAILED`.

### 4.2 Request headers

| Header | Required | Purpose |
|---|---|---|
| `Authorization: Bearer <jwt>` | all except `/auth/login`, `/health` | Authentication |
| `X-Request-Id` | optional | Echoed back; generated when absent; propagated into audit events |
| `Idempotency-Key` | required on every `POST` that creates a resource | Duplicate suppression, 24 h window |
| `If-Match: "<version>"` | required on `PATCH` / `DELETE` of versioned resources | Optimistic concurrency |

### 4.3 Error envelope

```json
{
  "timestamp": "2026-09-09T11:42:07.113Z",
  "status": 409,
  "error": "CONFLICT",
  "code": "CLIENT_DUPLICATE_EMAIL",
  "message": "A client with this email already exists.",
  "requestId": "0199a4c2-6f1e-7c3b-9a10-2f8c4d1e5b77",
  "details": [
    { "field": "email", "issue": "duplicate", "conflictingId": "9b21…" }
  ]
}
```

**Rule ER-01 — errors must not leak existence.** When a user lacks scope for a resource that does exist, the API returns **`404 <ENTITY>_NOT_FOUND`**, not `403`, so record existence cannot be probed by iterating IDs. `403` is returned only when the resource is already visible to the user but the *action* is not permitted (e.g. a manager attempting `DELETE` on their own client). Every suppressed `403 → 404` still emits a `PERMISSION_DENIED` audit event carrying the true reason, so the substitution is invisible to the attacker but fully visible to the auditor.

### 4.4 Global HTTP status contract

| Status | Code family | When |
|---|---|---|
| `200` | — | Successful read or update |
| `201` | — | Resource created (`Location` header set) |
| `202` | — | Accepted for asynchronous processing (audit export) |
| `204` | — | Successful delete / empty body |
| `400` | `VALIDATION_FAILED`, `MALFORMED_JSON`, `INVALID_CURSOR` | Input error |
| `401` | `TOKEN_MISSING`, `TOKEN_EXPIRED`, `TOKEN_INVALID` | Authentication failure |
| `403` | `PERMISSION_DENIED`, `SCOPE_VIOLATION`, `ROLE_REQUIRED` | Authorization failure on a visible resource |
| `404` | `<ENTITY>_NOT_FOUND` | Absent, soft-deleted, or out of scope |
| `409` | `VERSION_CONFLICT`, `<ENTITY>_DUPLICATE_<FIELD>`, `ILLEGAL_STATE_TRANSITION` | State conflict |
| `410` | `CLIENT_MERGED` | Superseded by a merge; `Location` points at the survivor |
| `412` | `PRECONDITION_REQUIRED` | `If-Match` missing on a versioned write |
| `413` | `PAYLOAD_TOO_LARGE` | Attachment above 10 MB |
| `422` | `BUSINESS_RULE_VIOLATED` | Syntactically valid but violates a domain rule |
| `429` | `RATE_LIMIT_EXCEEDED` | Throttled; `Retry-After` set |
| `500` | `INTERNAL_ERROR` | Unhandled; `requestId` is the only detail exposed |
| `503` | `DEPENDENCY_UNAVAILABLE` | Kafka or DB unreachable; `Retry-After` set |

### 4.5 Pagination

**Offset pagination** — bounded, sortable admin lists (users, teams, client search results).

`GET …?page=0&size=25&sort=createdAt,desc` — `size` max 100, default 25.

```json
{
  "content": [],
  "page": 0,
  "size": 25,
  "totalElements": 1342,
  "totalPages": 54,
  "hasNext": true
}
```

**Keyset (cursor) pagination** — mandatory for the interaction timeline and the audit log, where offsets drift under concurrent writes and `OFFSET 50000` degrades into a scan.

`GET …?cursor=<opaque>&limit=50` — the cursor is base64url of `{"ts":"…","id":"…"}` matching the sort key.

```json
{ "content": [], "nextCursor": "eyJ0cyI6…", "hasMore": true }
```

An expired or malformed cursor returns `400 INVALID_CURSOR`; the client restarts from the first page.

### 4.6 Idempotency

`POST` creates require an `Idempotency-Key` (client-generated UUID). The service stores `(user_id, endpoint, idempotency_key) → (status, response_body, payload_hash, created_at)` for 24 h.

| Situation | Result |
|---|---|
| Same key, identical payload hash | Replay the stored response, add header `Idempotency-Replayed: true` |
| Same key, different payload hash | `409 IDEMPOTENCY_KEY_REUSED` |
| Key absent | `400 VALIDATION_FAILED` |
| Key present, original request still in flight | `409 IDEMPOTENCY_IN_PROGRESS`, `Retry-After: 1` |

The claim is taken by inserting into `idempotency_keys` **inside the same transaction** as the business change, so a failed request rolls its claim back and a retry executes afresh — there is no "stuck in flight" state to clean up after a crash. One copy of this table lives in each service schema that has creates.

```sql
CREATE TABLE idempotency_keys (
    user_id           UUID         NOT NULL,
    endpoint          VARCHAR(512) NOT NULL,   -- "POST /api/v1/clients", never the query string
    idempotency_key   UUID         NOT NULL,
    payload_hash      BYTEA        NOT NULL,   -- HMAC of the canonical body under the pepper
    response_status   INTEGER,
    response_headers  JSONB,
    response_body_enc BYTEA,                   -- AES-256-GCM: a create response echoes PII
    key_version       SMALLINT     NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,

    CONSTRAINT pk_idempotency_keys PRIMARY KEY (user_id, endpoint, idempotency_key),
    CONSTRAINT ck_idempotency_completed_pair
        CHECK ((completed_at IS NULL) = (response_status IS NULL)),
    CONSTRAINT ck_idempotency_status_range
        CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599)
);

CREATE INDEX ix_idempotency_keys_created_at ON idempotency_keys (created_at);
```

Deliberately **no foreign key on `user_id`**. Every other user reference records something that happened and is `RESTRICT`; these rows are a 24-hour retry cache that records nothing, and a transient row must not be able to block the deactivation flow of RB-BR-07.

### 4.7 Encryption at rest

Per `PROJECT_IDEA.md` §9, sensitive fields are encrypted. Implementation is **application-level AES-256-GCM** with a data key held in the KMS/vault — not `pgcrypto` called from SQL, which would put keys into query logs and `pg_stat_statements`.

Each sensitive field becomes two columns:

| Column | Type | Purpose |
|---|---|---|
| `<field>_enc` | `BYTEA` | 96-bit nonce ‖ ciphertext ‖ 128-bit auth tag |
| `<field>_hash` | `BYTEA` | HMAC-SHA256 of the normalized value under a separate pepper — supports exact-match lookup and uniqueness |

**Normalization before hashing:** email → trimmed, lowercased; phone → E.164. This is precisely what makes "open the card by email or phone" work without decrypting the whole table.

**Consequences, accepted deliberately:**

- Exact-match lookup on sensitive fields: supported. Prefix, substring or `LIKE` search: **not supported**.
- Interaction `body` is encrypted, so there is no full-text search across note bodies. Search covers the plaintext `subject` only. Revisit in v4 if the need is proven.
- Key rotation: a `key_version SMALLINT` column on every encrypting table. Rotation is a background re-encryption job, one client at a time, audited as `entity_type = 'ENCRYPTION_KEY'`, `action = 'UPDATE'`.

### 4.8 Optimistic concurrency

Every mutable aggregate root carries `version INTEGER NOT NULL DEFAULT 0` (JPA `@Version`).

1. `GET` returns `ETag: "7"` and `"version": 7` in the body.
2. `PATCH` / `DELETE` must send `If-Match: "7"`.
3. Missing header → `412 PRECONDITION_REQUIRED`.
4. Stale version → `409 VERSION_CONFLICT`; the body carries the current server state under `details[0].current` so the UI can render a field-level diff instead of discarding the user's typing.

### 4.9 Soft delete

`deleted_at TIMESTAMPTZ`, `deleted_by UUID`, `deletion_reason TEXT`. Soft-deleted rows are excluded from every read path by a Hibernate filter plus partial indexes. Hard deletion happens only through the GDPR erasure procedure (CP-EC-12), which **anonymizes in place** rather than removing rows, so audit references stay resolvable.

### 4.10 Rate limits

| Scope | Limit |
|---|---|
| Per user, all endpoints | 300 req/min |
| `POST /auth/login` per IP | 10 req/min |
| `POST /auth/login` per account | 5 consecutive failures → 15 min lock |
| `GET /clients/lookup` per user | 60 req/min (PII-probing guard) |
| `POST /audit/exports` per user | 5 per hour |
| Attachment upload per user | 20 per hour, 10 MB each |

### 4.11 UI state vocabulary

Every screen in this spec declares its states using one shared vocabulary, so the frontend can implement them as a single generic wrapper component.

| State | Meaning | Baseline treatment |
|---|---|---|
| `loading` | First fetch in flight, nothing to show yet | Skeleton matching final layout; never a spinner on a full page |
| `loading-more` | Keyset page 2+ in flight | Inline row-level skeleton at list foot; existing rows stay interactive |
| `empty` | Request succeeded, zero results | Illustration + one-sentence cause + primary action |
| `empty-filtered` | Zero results **because of active filters** | Distinct from `empty`: offers "clear filters" instead of "create" |
| `success` | Data rendered | — |
| `partial` | Primary data loaded, a secondary panel failed | Render what loaded; failed panel shows inline retry. Never fail the whole screen |
| `error` | Primary data failed | Error card with `requestId`, retry button, back link |
| `forbidden` | `403` — action not allowed | Explain the missing permission; offer break-glass request where applicable |
| `not-found` | `404` — absent or out of scope | Neutral copy; must not hint that the record exists elsewhere |
| `saving` | Mutation in flight | Disable the submit control, keep the form readable, no layout shift |
| `conflict` | `409 VERSION_CONFLICT` | Side-by-side diff: "your value" vs "current value", per field |
| `offline` | Network unavailable | Banner; queued composer drafts persist in local storage |
| `stale` | Data older than its freshness budget | Amber "updated N min ago" chip + refresh affordance |

### 4.12 Temporal invariants are triggers, not CHECKs

PostgreSQL requires every function in a `CHECK` constraint or an index predicate to be
`IMMUTABLE`. `now()`, `CURRENT_DATE` and `CURRENT_TIMESTAMP` are `STABLE`, so any rule of the
form "not in the future" or "at least 18 years ago" **cannot** be a constraint — the
`CREATE TABLE` itself fails with `functions in check constraint must be marked IMMUTABLE`.

Three rules in this spec are of that shape. Each is enforced by a `BEFORE INSERT OR UPDATE`
trigger on its table, raising `SQLSTATE 23514` (`check_violation`) so the application maps it
to the same `422 BUSINESS_RULE_VIOLATED` it would map a constraint to:

| Rule | Table | Trigger |
|---|---|---|
| Client is at least 18 years old (CP-BR-14) | `clients` | `trg_clients_validate` |
| `opened_on` is not in the future | `client_products` | `trg_client_products_validate` |
| `occurred_at` at most 5 min ahead (IL-EC-02) | `interactions` | `trg_interactions_validate` |

Comparisons between two columns of the same row (`closed_on >= opened_on`,
`expires_at > requested_at`, `recorded_at >= occurred_at - INTERVAL '1 hour'`) involve no
function call and remain ordinary `CHECK` constraints.

The same restriction is why `is_overdue` is computed at query time rather than stored
(TR-BR-02), and why `user_roles` expiry is filtered in the query rather than in a partial
index.

---

## 5. Module 1 — Client Profile

**Service:** `client-service` · **Phase:** MVP · **Prefix:** `CP`

The unified customer card. This module carries the target metric: a manager types an email, phone or ID and has full context in under three minutes.

### 5.1 User stories

| ID | As a… | I want to… | So that… | Acceptance criteria |
|---|---|---|---|---|
| CP-US-01 | Manager | look a client up by email, phone or core-banking ID from one search box | I can open the card before the call connects | One input accepts all three formats; the format is auto-detected; an exact match navigates straight to the card; ≥2 matches render a disambiguation list; p95 < 300 ms |
| CP-US-02 | Manager | see identity, KYC status, products and recent activity on one screen without scrolling to a second page | I stop asking the client questions the bank already has answers to | Above the fold: name, segment, KYC badge, owner, phone/email, product count, last interaction date, open-task count |
| CP-US-03 | Manager | edit contact details on the card | corrections captured during a call are not lost in a spreadsheet | Inline edit for email, phone, address, preferred channel; save is optimistic-locked; every change produces an audit event |
| CP-US-04 | Manager | see a client's KYC status and expiry at a glance | I do not offer a product the client is not cleared for | Badge colour-codes `VERIFIED` / `PENDING` / `EXPIRED` / `REJECTED`; expiry within 30 days shows an amber warning with the exact date |
| CP-US-05 | Supervisor | reassign a client to another manager on my team | coverage survives holidays and departures | Reassign action visible to supervisors only; requires a reason; both managers are notified; open tasks follow or stay per the operator's choice |
| CP-US-06 | Admin | merge two client records that describe the same person | duplicates stop fragmenting the interaction history | Merge picks a survivor; interactions, tasks and products re-point to it; the loser becomes `410 CLIENT_MERGED` with a `Location` to the survivor |
| CP-US-07 | Manager | see which products the client holds with status and balance | I can have a useful conversation without opening the core banking terminal | Product list groups by type, shows masked number, status, balance, opened date, and the age of the last sync |

### 5.2 Data model

#### 5.2.1 Enumerated types

```sql
CREATE TYPE kyc_status     AS ENUM ('NOT_STARTED','PENDING','VERIFIED','REJECTED','EXPIRED');
CREATE TYPE client_status  AS ENUM ('ACTIVE','DORMANT','BLOCKED','CLOSED');
CREATE TYPE client_segment AS ENUM ('RETAIL','PREMIUM','SME','PRIVATE');
CREATE TYPE risk_rating    AS ENUM ('LOW','MEDIUM','HIGH');
CREATE TYPE contact_channel AS ENUM ('PHONE','EMAIL','SMS','IN_APP','BRANCH');
CREATE TYPE product_type   AS ENUM (
    'CURRENT_ACCOUNT','SAVINGS_ACCOUNT','DEBIT_CARD','CREDIT_CARD',
    'MORTGAGE','CONSUMER_LOAN','TERM_DEPOSIT','INVESTMENT_ACCOUNT','INSURANCE');
CREATE TYPE product_status AS ENUM ('PENDING','ACTIVE','SUSPENDED','CLOSED');
```

#### 5.2.2 `clients`

```sql
CREATE TABLE clients (
    id                   UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    external_ref         VARCHAR(32)    NOT NULL,          -- core-banking CIF
    first_name           VARCHAR(100)   NOT NULL,
    last_name            VARCHAR(100)   NOT NULL,
    middle_name          VARCHAR(100),
    date_of_birth        DATE           NOT NULL,
    -- sensitive: see 4.7
    email_enc            BYTEA,
    email_hash           BYTEA,
    phone_enc            BYTEA          NOT NULL,
    phone_hash           BYTEA          NOT NULL,
    tax_id_enc           BYTEA,
    tax_id_hash          BYTEA,
    address_enc          BYTEA,
    key_version          SMALLINT       NOT NULL DEFAULT 1,
    -- classification
    preferred_channel    contact_channel NOT NULL DEFAULT 'PHONE',
    segment              client_segment NOT NULL DEFAULT 'RETAIL',
    status               client_status  NOT NULL DEFAULT 'ACTIVE',
    risk                 risk_rating    NOT NULL DEFAULT 'LOW',
    -- KYC
    kyc_status           kyc_status     NOT NULL DEFAULT 'NOT_STARTED',
    kyc_verified_at      TIMESTAMPTZ,
    kyc_expires_at       TIMESTAMPTZ,
    kyc_rejection_reason TEXT,
    kyc_note_enc         BYTEA,          -- AES-256-GCM; evidence note of the latest decision (§5.3 kyc)
    -- ownership
    owner_manager_id     UUID           NOT NULL,
    team_id              UUID           NOT NULL,
    -- denormalized read-model counters (see CP-BR-11)
    last_interaction_at  TIMESTAMPTZ,
    open_task_count      INTEGER        NOT NULL DEFAULT 0,
    -- merge lineage
    merged_into_id       UUID,
    merged_at            TIMESTAMPTZ,
    -- housekeeping
    version              INTEGER        NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by           UUID           NOT NULL,
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by           UUID           NOT NULL,
    deleted_at           TIMESTAMPTZ,
    deleted_by           UUID,
    deletion_reason      TEXT,

    CONSTRAINT fk_clients_owner    FOREIGN KEY (owner_manager_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_clients_team     FOREIGN KEY (team_id)          REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT fk_clients_merged   FOREIGN KEY (merged_into_id)   REFERENCES clients(id) ON DELETE RESTRICT,
    CONSTRAINT fk_clients_creator  FOREIGN KEY (created_by)       REFERENCES users(id) ON DELETE RESTRICT,

    -- Minimum age 18 is NOT a CHECK: CURRENT_DATE is STABLE, not IMMUTABLE, and PostgreSQL
    -- rejects it in a constraint. Enforced by trigger trg_clients_validate (see below).
    CONSTRAINT ck_clients_dob_sane
        CHECK (date_of_birth >= DATE '1900-01-01'),
    CONSTRAINT ck_clients_kyc_verified_fields
        CHECK (kyc_status <> 'VERIFIED'
               OR (kyc_verified_at IS NOT NULL AND kyc_expires_at IS NOT NULL)),
    CONSTRAINT ck_clients_kyc_rejected_reason
        CHECK (kyc_status <> 'REJECTED' OR kyc_rejection_reason IS NOT NULL),
    CONSTRAINT ck_clients_kyc_expiry_after_verify
        CHECK (kyc_expires_at IS NULL OR kyc_verified_at IS NULL
               OR kyc_expires_at > kyc_verified_at),
    CONSTRAINT ck_clients_email_pair
        CHECK ((email_enc IS NULL) = (email_hash IS NULL)),
    CONSTRAINT ck_clients_tax_pair
        CHECK ((tax_id_enc IS NULL) = (tax_id_hash IS NULL)),
    CONSTRAINT ck_clients_merge_pair
        CHECK ((merged_into_id IS NULL) = (merged_at IS NULL)),
    CONSTRAINT ck_clients_no_self_merge
        CHECK (merged_into_id IS NULL OR merged_into_id <> id),
    CONSTRAINT ck_clients_delete_reason
        CHECK (deleted_at IS NULL OR deletion_reason IS NOT NULL),
    CONSTRAINT ck_clients_open_task_count
        CHECK (open_task_count >= 0)
);
```

**Indexes**

```sql
-- Identity lookup (CP-US-01). Partial: freed identifiers become reusable after erasure.
CREATE UNIQUE INDEX ux_clients_external_ref ON clients (external_ref)      WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_clients_email_hash   ON clients (email_hash)        WHERE deleted_at IS NULL AND email_hash IS NOT NULL;
CREATE UNIQUE INDEX ux_clients_tax_hash     ON clients (tax_id_hash)       WHERE deleted_at IS NULL AND tax_id_hash IS NOT NULL;
-- Phone is intentionally NON-unique: family members legitimately share a landline (CP-EC-03).
CREATE INDEX        ix_clients_phone_hash   ON clients (phone_hash)        WHERE deleted_at IS NULL;

-- Scope filters
CREATE INDEX ix_clients_owner ON clients (owner_manager_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_clients_team  ON clients (team_id)          WHERE deleted_at IS NULL;

-- KYC expiry sweep (CP-BR-06)
CREATE INDEX ix_clients_kyc_expiry ON clients (kyc_expires_at)
    WHERE kyc_status = 'VERIFIED' AND deleted_at IS NULL;

-- Name search: unaccented, trigram, tolerant of typos
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_clients_name_trgm ON clients
    USING gin ((lower(first_name || ' ' || last_name)) gin_trgm_ops)
    WHERE deleted_at IS NULL;
```

#### 5.2.3 `client_products`

```sql
CREATE TABLE client_products (
    id                  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id           UUID           NOT NULL,
    product_type        product_type   NOT NULL,
    external_product_id VARCHAR(64)    NOT NULL,
    masked_number       VARCHAR(32),                    -- '**** **** **** 4417'
    status              product_status NOT NULL,
    currency            CHAR(3)        NOT NULL,
    balance_minor       BIGINT,
    opened_on           DATE           NOT NULL,
    closed_on           DATE,
    synced_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version             INTEGER        NOT NULL DEFAULT 0,

    CONSTRAINT fk_products_client FOREIGN KEY (client_id)
        REFERENCES clients(id) ON DELETE CASCADE,
    CONSTRAINT uq_products_external UNIQUE (client_id, external_product_id),
    CONSTRAINT ck_products_currency  CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_products_close_order CHECK (closed_on IS NULL OR closed_on >= opened_on),
    CONSTRAINT ck_products_closed_has_date
        CHECK (status <> 'CLOSED' OR closed_on IS NOT NULL),
    CONSTRAINT ck_products_open_has_no_close
        CHECK (status = 'CLOSED' OR closed_on IS NULL)
    -- "opened_on not in the future" is enforced by trigger, not CHECK: CURRENT_DATE is STABLE.
);

CREATE INDEX ix_products_client_status ON client_products (client_id, status);
```

`ON DELETE CASCADE` is safe here because products are a **projection of core banking**, not a system of record. Losing them on client hard-delete loses nothing authoritative. Interactions and tasks use `RESTRICT` for the opposite reason.

#### 5.2.4 Relationship map

```
teams 1───* users
users 1───* clients            (owner_manager_id, RESTRICT)
teams 1───* clients            (team_id, RESTRICT)
clients 1──* client_products   (CASCADE)
clients 1──* interactions      (RESTRICT)
clients 1──* tasks             (RESTRICT)
clients 0..1─* clients         (merged_into_id — self-reference, RESTRICT)
```

### 5.3 API endpoints

Base: `/api/v1`. Every response carries `ETag` where the entity is versioned.

---

#### `POST /clients` — create a client

**Permission:** `client:write` scope `OWN` (manager, supervisor, admin)
**Headers:** `Idempotency-Key` (required)

```json
{
  "externalRef": "CIF-0092841",
  "firstName": "Anna",
  "lastName": "Kowalska",
  "middleName": null,
  "dateOfBirth": "1988-04-17",
  "email": "anna.kowalska@example.com",
  "phone": "+48511234567",
  "taxId": "PL8804170123",
  "address": "ul. Prosta 51, 00-838 Warszawa",
  "preferredChannel": "PHONE",
  "segment": "RETAIL",
  "ownerManagerId": "3f1c…"
}
```

**`201 Created`** · `Location: /api/v1/clients/{id}` · `ETag: "0"`

```json
{
  "id": "9b21…",
  "externalRef": "CIF-0092841",
  "firstName": "Anna", "lastName": "Kowalska", "displayName": "Anna Kowalska",
  "dateOfBirth": "1988-04-17",
  "email": "anna.kowalska@example.com",
  "phone": "+48511234567",
  "taxId": "PL88****0123",
  "preferredChannel": "PHONE",
  "segment": "RETAIL", "status": "ACTIVE", "risk": "LOW",
  "kyc": { "status": "NOT_STARTED", "verifiedAt": null, "expiresAt": null, "daysUntilExpiry": null },
  "owner": { "id": "3f1c…", "fullName": "Adam Nowak" },
  "teamId": "77aa…",
  "version": 0,
  "createdAt": "2026-09-09T11:42:07.113Z",
  "updatedAt": "2026-09-09T11:42:07.113Z"
}
```

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | Missing required field, malformed email, phone not E.164, DOB in the future |
| `400` | `VALIDATION_FAILED` | `Idempotency-Key` absent |
| `401` | `TOKEN_EXPIRED` | — |
| `403` | `PERMISSION_DENIED` | Auditor role, or manager setting `ownerManagerId` to someone else |
| `409` | `CLIENT_DUPLICATE_EXTERNAL_REF` | `externalRef` already exists |
| `409` | `CLIENT_DUPLICATE_EMAIL` | Email hash collides; `details[0].conflictingId` names the existing client |
| `409` | `CLIENT_DUPLICATE_TAX_ID` | Tax ID hash collides |
| `409` | `IDEMPOTENCY_KEY_REUSED` | Same key, different payload |
| `422` | `BUSINESS_RULE_VIOLATED` | Client under 18 (`ck_clients_adult`) |

---

#### `GET /clients/lookup` — single-box identity search (CP-US-01)

**Permission:** `client:read` (scope-filtered)
**Query:** exactly one of `email`, `phone`, `externalRef`, or `q` (free text over names)

```
GET /api/v1/clients/lookup?q=%2B48511234567
```

The server auto-detects the format of `q`: `@` → email, leading `+`/digits ≥ 9 → phone (normalized to E.164), `CIF-` prefix → external ref, otherwise a trigram name search.

**`200 OK`**

```json
{
  "matchType": "PHONE",
  "exact": true,
  "results": [
    {
      "id": "9b21…", "displayName": "Anna Kowalska", "externalRef": "CIF-0092841",
      "segment": "RETAIL", "kycStatus": "VERIFIED",
      "maskedEmail": "a****a@example.com", "maskedPhone": "+48 511 *** 567",
      "owner": { "id": "3f1c…", "fullName": "Adam Nowak" },
      "inScope": true,
      "lastInteractionAt": "2026-09-02T09:11:00.000Z"
    }
  ]
}
```

**Out-of-scope hits** are returned with `"inScope": false` and **all PII masked except the display name and owner**, so a manager learns "this client exists and belongs to Adam Nowak" without seeing the record. That is the entry point to a break-glass request (RB-US-05). This is a deliberate, narrow exception to rule ER-01: it applies only to `lookup`, only for an exact identifier match, and every such response emits a `READ_SENSITIVE` audit event.

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | Zero or more than one search parameter supplied |
| `400` | `VALIDATION_FAILED` | `q` shorter than 3 characters |
| `429` | `RATE_LIMIT_EXCEEDED` | Over 60 lookups/min — PII-enumeration guard |

Empty result is `200` with `"results": []`, never `404`.

---

#### `GET /clients/{id}` — full card

**Permission:** `client:read` in scope

**`200 OK`** · `ETag: "7"` — the create response shape, plus `products`, `stats`, and `permissions`:

```json
{
  "id": "9b21…",
  "…": "all fields from the create response",
  "products": [
    {
      "id": "aa10…", "type": "CURRENT_ACCOUNT", "maskedNumber": "PL** **** 4417",
      "status": "ACTIVE",
      "balance": { "amountMinor": 1250000, "currency": "PLN" },
      "openedOn": "2019-03-04", "closedOn": null,
      "syncedAt": "2026-09-09T06:00:00.000Z", "syncAgeMinutes": 342, "stale": false
    }
  ],
  "stats": {
    "interactionCount": 47,
    "lastInteractionAt": "2026-09-02T09:11:00.000Z",
    "openTaskCount": 2,
    "overdueTaskCount": 1
  },
  "permissions": { "canEdit": true, "canDelete": false, "canReassign": false, "canMerge": false },
  "version": 7
}
```

`permissions` is computed server-side from the caller's effective grants. **The UI must render affordances from this object, never from a client-side role check** — one source of truth, and the button set can never drift from what the API will actually allow.

| Status | Code | Cause |
|---|---|---|
| `404` | `CLIENT_NOT_FOUND` | Absent, soft-deleted, or out of scope (rule ER-01) |
| `410` | `CLIENT_MERGED` | Record was merged; `Location` header points at the survivor |

---

#### `GET /clients/{id}/summary` — the 2-minute view

**Permission:** `client:read` in scope

The single call that backs the card's first paint. Aggregates profile + last 10 interactions + open tasks + products in **one** round trip, so the screen does not wait on four sequential requests.

**`200 OK`**

```json
{
  "client": { "…": "as GET /clients/{id}" },
  "recentInteractions": [
    { "id": "e100…", "type": "CALL", "direction": "OUTBOUND", "subject": "Mortgage rate question",
      "occurredAt": "2026-09-02T09:11:00.000Z",
      "author": { "id": "3f1c…", "fullName": "Adam Nowak" }, "outcome": "SUCCESSFUL" }
  ],
  "openTasks": [
    { "id": "t900…", "title": "Send mortgage offer", "dueAt": "2026-09-10T15:00:00.000Z",
      "priority": "HIGH", "status": "OPEN", "overdue": false,
      "assignee": { "id": "3f1c…", "fullName": "Adam Nowak" } }
  ],
  "degraded": []
}
```

`degraded` lists sub-resources that failed to load (e.g. `["products"]`) — the client renders the `partial` state for exactly those panels instead of failing the whole screen. Sub-resource timeout is 800 ms; the aggregate never exceeds 1.2 s.

---

#### `PATCH /clients/{id}` — update

**Permission:** `client:write` in scope · **Headers:** `If-Match` (required)

```json
{ "email": "a.kowalska@newmail.com", "preferredChannel": "EMAIL", "segment": "PREMIUM" }
```

**`200 OK`** · `ETag: "8"` — full client representation.

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | Unknown field, `null` on a non-nullable field, bad format |
| `403` | `PERMISSION_DENIED` | Changing `ownerManagerId`, `teamId` or `kycStatus` (any caller); `risk` without `client:kyc`; `status` without `client:delete` |
| `404` | `CLIENT_NOT_FOUND` | Out of scope |
| `409` | `VERSION_CONFLICT` | `If-Match` stale; `details[0].current` holds server state |
| `409` | `CLIENT_DUPLICATE_EMAIL` | New email belongs to another client |
| `409` | `CLIENT_DUPLICATE_TAX_ID` | New tax ID belongs to another client |
| `412` | `PRECONDITION_REQUIRED` | `If-Match` missing |
| `422` | `BUSINESS_RULE_VIOLATED` | Editing a `CLOSED` client (CP-BR-08); changing `externalRef` (CP-BR-01) |

**Field-level rules.** `client:write` in scope covers the profile and contact fields —
`firstName`, `lastName`, `middleName`, `dateOfBirth`, `email`, `phone`, `taxId`, `address`,
`preferredChannel`, `segment`. Every other field has an owner that `PATCH` must not bypass:

| Field | Through `PATCH` | Why |
|---|---|---|
| `externalRef` | `422`, any caller | Immutable after creation (CP-BR-01) |
| `teamId` | `403`, any caller | Derived from the owner, never set independently (CP-BR-03) |
| `ownerManagerId` | `403`, any caller | Belongs to `POST /clients/{id}/reassign`, which requires a reason and moves open tasks (CP-US-05) |
| `kycStatus` | `403`, any caller | Belongs to `POST /clients/{id}/kyc` and the CP-BR-04 state machine |
| `risk` | also requires `client:kyc` in scope | A risk rating is a compliance assessment and follows the separation of duties in CP-BR-05 (A-13) |
| `status` | also requires `client:delete` | Client lifecycle is admin authority; CP-BR-08 already names reopening admin-only (A-13) |

A `CLOSED` client accepts nothing but `{"status": "ACTIVE"}` (CP-BR-08). An explicit `null`
clears a nullable field (`middleName`, `email`, `taxId`, `address`) and is `400` on any other.
A patch that changes nothing returns `200` with the current representation, no version bump
and no event — CP-BR-12's "one event per write" counts writes, not requests. The `409
VERSION_CONFLICT` body carries decrypted PII in `details[0].current`, so it emits
`READ_SENSITIVE` exactly as a card read does (CP-BR-13).

---

#### `POST /clients/{id}/kyc` — KYC transition

**Permission:** depends on the target (CP-BR-05) — `client:write` in scope to move to `PENDING`;
`client:kyc` in scope (supervisor, admin, compliance) to set `VERIFIED` or `REJECTED`. `EXPIRED`
is never a caller's to set: the nightly sweep owns that edge (CP-BR-06).

```json
{ "targetStatus": "VERIFIED", "verifiedOn": "2026-09-09", "validityMonths": 24, "note": "Passport + utility bill on file" }
```

| Field | Rule |
|---|---|
| `targetStatus` | Required |
| `verifiedOn`, `validityMonths` | Required for `VERIFIED`; `kyc_verified_at` is `verifiedOn` at 00:00 UTC and `kyc_expires_at` is that plus `validityMonths` |
| `reason` | Required for `REJECTED`, stored in `kyc_rejection_reason` |
| `note` | Optional, ≤ 2000 characters. Stored encrypted in `kyc_note_enc` — free text about identity documents will eventually hold a document number — and returned as `kyc.note` |

Leaving `VERIFIED` clears `kyc_verified_at` and `kyc_expires_at`, so a `PENDING` client never
shows a stale "expires in 12 days"; leaving `REJECTED` clears the reason.

**`200 OK`** — client representation; the `kyc` block also carries `rejectionReason` and `note`.

| Status | Code | Cause |
|---|---|---|
| `403` | `ROLE_REQUIRED` | `VERIFIED` or `REJECTED` without `client:kyc` — a manager cannot approve KYC (CP-BR-05) |
| `403` | `PERMISSION_DENIED` | The caller owns the client; `details[0].reason` is `SELF_APPROVAL_FORBIDDEN` (CP-BR-05) |
| `404` | `CLIENT_NOT_FOUND` | Out of scope |
| `409` | `ILLEGAL_STATE_TRANSITION` | A pair outside CP-BR-04, e.g. `NOT_STARTED → VERIFIED`; or `targetStatus = EXPIRED` |
| `422` | `BUSINESS_RULE_VIOLATED` | `REJECTED` with no `reason`; `VERIFIED` without `verifiedOn` or `validityMonths`; `validityMonths` outside 6–60; `verifiedOn` in the future; a verification whose expiry has already passed |

---

#### `POST /clients/{id}/reassign` — change owner (CP-US-05)

**Permission:** `client:reassign` scope `TEAM` (supervisor) or `ALL` (admin)

```json
{ "newOwnerManagerId": "4a2d…", "reason": "Parental leave cover until 2027-01", "transferOpenTasks": true }
```

**`200 OK`** — client representation; response includes `"tasksTransferred": 3`.

| Status | Code | Cause |
|---|---|---|
| `403` | `SCOPE_VIOLATION` | Supervisor targeting a manager outside their team |
| `404` | `USER_NOT_FOUND` | `newOwnerManagerId` unknown or deactivated |
| `422` | `BUSINESS_RULE_VIOLATED` | New owner equals current owner; new owner has no primary team, so CP-BR-03 has nothing to derive from; new owner lacks the `MANAGER` role; `reason` under 10 characters |

Two parts of this endpoint wait on tables that do not exist yet, and both fail open rather than
pretending: the **`MANAGER` role check** needs `user_roles`, which ships with RBAC in v2, so today
any active user with a primary team may receive a client; and **`transferOpenTasks`** is accepted
and answered with `"tasksTransferred": 0`, because tasks live in interaction-service and nothing
can have followed the client. The count is reported rather than omitted so the response shape does
not change when it starts being true.

---

#### `POST /clients/merge` — deduplicate (CP-US-06)

**Permission:** `client:merge` (admin only)

```json
{ "survivorId": "9b21…", "mergedId": "77cd…", "reason": "Same person, duplicate created by branch import 2026-08-30", "fieldResolution": { "email": "SURVIVOR", "phone": "MERGED", "address": "MERGED" } }
```

**`200 OK`**

```json
{
  "survivor": { "…": "full client representation" },
  "moved": { "interactions": 12, "tasks": 3, "products": 2 },
  "skipped": { "products": 1 },
  "mergedId": "77cd…"
}
```

| Status | Code | Cause |
|---|---|---|
| `403` | `ROLE_REQUIRED` | Caller is not an admin |
| `409` | `ILLEGAL_STATE_TRANSITION` | Either record is already merged, or the two are already in the same merge chain |
| `422` | `BUSINESS_RULE_VIOLATED` | `survivorId == mergedId`; either client has an `ACTIVE` product of the same `external_product_id`; `reason` under 20 characters |

---

#### `DELETE /clients/{id}` — soft delete

**Permission:** `client:delete` (admin only) · **Headers:** `If-Match` (required)
**Query:** `reason` (required, ≥ 10 chars)

**`204 No Content`**

| Status | Code | Cause |
|---|---|---|
| `403` | `ROLE_REQUIRED` | Not an admin |
| `422` | `BUSINESS_RULE_VIOLATED` | Client holds an `ACTIVE` product, or has open tasks (CP-BR-09); `reason` under 10 characters |

Only the product half of CP-BR-09 is enforced today: open tasks live in interaction-service, which
has no code yet, so there is nothing to ask. The check belongs on this side of the call and is
added when the service exists.

---

#### Product sub-resource

| Method | Path | Permission | Notes |
|---|---|---|---|
| `GET` | `/clients/{id}/products` | `client:read` | `?status=ACTIVE&type=MORTGAGE`; `200` with `[]` when none. Emits no `READ_SENSITIVE`: nothing here is decrypted PII, and CP-BR-13 ties the event to a genuine disclosure rather than to every panel that renders |
| `POST` | `/clients/{id}/products` | `product:write` (admin / sync service account) | `Idempotency-Key` required; `201`; `409 PRODUCT_DUPLICATE_EXTERNAL_ID`; `422` when the client is not cleared for new products (CP-BR-07) |
| `PATCH` | `/clients/{id}/products/{productId}` | `product:write` | `If-Match` required; `409 VERSION_CONFLICT` with `details[0].current`, `404 PRODUCT_NOT_FOUND`, `422` on closing a product with a non-zero balance |
| `POST` | `/clients/{id}/products/sync` | `product:sync` | `202 Accepted`; pulls fresh state from core banking; `503 DEPENDENCY_UNAVAILABLE` when the core is down |

---

#### Internal endpoints — how other services ask

`interaction-service` and `audit-service` own no client or user table (§3.1), so the two questions
they cannot answer themselves are answered here. Both relay the **caller's own bearer token**, not
a service credential: the decision is about the human making the request, so their identity has to
survive the hop, and ER-01's `404` passes back through the calling service unchanged.

| Method | Path | Answers |
|---|---|---|
| `GET` | `/internal/clients/{id}/access?permission=<code>` | `200` with `{clientId, ownerManagerId, teamId, status, kycStatus}` when the caller holds that permission over the client; `404` when absent or out of scope; `403` when the permission is held at no scope |
| `GET` | `/internal/users?ids=<uuid>,<uuid>` | `200` with `[{id, fullName}]` — display names for authors and assignees, capped at 100 ids |

Deliberately **not** `GET /clients/{id}`. Authorizing a write to an interaction does not need the
client's name, email or phone; reusing the card would ship all three between services and write a
`READ_SENSITIVE` row for a disclosure nobody read (CP-BR-13). What the access view does carry is
exactly what a caller needs to apply the client's own rules — CP-BR-07 turns on `kycStatus` and
`status`, CP-BR-08 on `status`.

User names are corporate directory data, not client PII — the same reason `users.email` is a
plaintext column (§9.2.3) — so any authenticated caller may resolve a name for an id they already
hold. A directory dump is a different request with a different permission (`user:read`).

---

#### `GET /clients` — scoped list / search

**Permission:** `client:read` (scope-filtered)
**Query:** `q`, `segment`, `status`, `kycStatus`, `ownerId`, `teamId`, `kycExpiringWithinDays`, `page`, `size`, `sort`

Returns the offset-paginated envelope of card summaries. `sort` accepts `lastName`, `createdAt`, `lastInteractionAt`, `kycExpiresAt`. Unknown sort field → `400 VALIDATION_FAILED`.

### 5.4 Business logic rules

| ID | Rule |
|---|---|
| CP-BR-01 | `external_ref` is globally unique among non-deleted clients and **immutable after creation**. It is the join key to core banking; changing it would silently re-point history. |
| CP-BR-02 | Email and tax ID are unique among non-deleted clients. Phone is **not** unique — shared household landlines are legitimate; the lookup returns all matches for disambiguation. |
| CP-BR-03 | A client always has exactly one `owner_manager_id`. `team_id` is derived from the owner's primary team at assignment time and re-derived on every reassignment; it is never set independently. |
| CP-BR-04 | Legal KYC transitions: `NOT_STARTED → PENDING`; `PENDING → VERIFIED \| REJECTED`; `VERIFIED → EXPIRED` (system only) or `→ PENDING` (re-verification); `REJECTED → PENDING`; `EXPIRED → PENDING`. Every other pair is `409 ILLEGAL_STATE_TRANSITION`. |
| CP-BR-05 | **Separation of duties.** A manager can move a client to `PENDING` but cannot set `VERIFIED` or `REJECTED`. Those require `client:kyc`, held by supervisor / admin / compliance. A user may never approve KYC on a client they own, even holding the permission — checked at request time, `403 PERMISSION_DENIED`, code detail `SELF_APPROVAL_FORBIDDEN`. |
| CP-BR-06 | A nightly job (03:00 UTC) moves `VERIFIED` clients past `kyc_expires_at` to `EXPIRED`, emits `client.kyc_expired`, and creates a `KYC_REFRESH` task for the owner due in 14 days. The job is idempotent and safe to re-run. |
| CP-BR-07 | Clients with `kyc_status IN ('REJECTED','EXPIRED')` or `status = 'BLOCKED'` are **read-only for product operations**: `POST /products` returns `422`. Interactions and tasks remain writable — the manager still has to call the client about it. |
| CP-BR-08 | A `CLOSED` client accepts no field edits except `status` (reopening to `ACTIVE`, admin only) and no new interactions except `NOTE`. |
| CP-BR-09 | Soft delete is refused while the client holds an `ACTIVE` product or an open task. Close or reassign first — this prevents orphaning live banking relationships. |
| CP-BR-10 | **Merge semantics.** The survivor keeps its own `id` and `external_ref`. Interactions, tasks and non-conflicting products re-point via `UPDATE … SET client_id = survivor`. Conflicting products (same `external_product_id`) are skipped and reported in `skipped`. The loser is soft-deleted with `merged_into_id` set; every subsequent read of its ID returns `410` with a `Location` to the survivor. Merges are **not reversible** through the API — a reversal is a DBA runbook. |
| CP-BR-11 | `last_interaction_at` and `open_task_count` are denormalized counters maintained by the `interaction.events` / `task.events` consumer inside `client-service`. They are display-only and may lag by seconds. **No business decision may read them** — anything authoritative queries `interaction-service` directly. A nightly reconciliation job repairs drift. |
| CP-BR-12 | Every write emits exactly one event through the outbox: `client.created`, `client.updated`, `client.deleted`, `client.merged`, `client.kyc_changed`, `client.reassigned`, and on the product projection `client.product_added` and `client.product_updated` (`entity.type = CLIENT_PRODUCT`, keyed by `client_id` like the rest). `changedFields` masks sensitive values per AR-01 — on a product that means `maskedNumber` and `balanceMinor`, because the envelope carries `clientId` beside them. A request that changes nothing writes nothing and emits nothing: the rule counts writes, not requests. |
| CP-BR-13 | Reading a client card emits a `READ_SENSITIVE` audit event **only when decrypted PII is actually returned** — i.e. not for masked search results. This keeps the audit table from being flooded by list views while still recording every genuine PII disclosure. |
| CP-BR-14 | Minimum age 18 is enforced by DB constraint and validated at the API edge for a better message. Corporate clients (`segment = 'SME'`) use the registration date as `date_of_birth`; the constraint holds. |

### 5.5 Screens & components

#### S-CP-01 — Client Lookup

Single search input, format hint, recent-clients strip.

| State | Behaviour |
|---|---|
| `idle` | Focused input, placeholder "Email, phone, or CIF ID", plus up to 8 recently-opened clients as chips |
| `loading` | Debounced 250 ms; inline spinner inside the input; recent chips stay visible |
| `success-exact` | Exactly one in-scope match → navigate straight to the card, no intermediate list. This is the whole point of the 2-minute target |
| `success-multi` | 2+ matches → list of result cards showing name, masked contacts, segment, KYC badge, owner |
| `empty` | "No client matches that email/phone/ID" + primary action **Create client** (permission-gated) |
| `partial` | Out-of-scope matches render greyed with a lock icon and a **Request access** button (RB-US-05) |
| `error` | Inline error strip with retry; the typed query is preserved |
| `rate-limited` | `429` → "Too many lookups. Try again in N seconds", countdown, input disabled |

#### S-CP-02 — Client 360 Card

Header (identity + KYC badge + owner + actions) over tabs: **Overview**, **Interactions**, **Tasks**, **Products**, **Audit**.

| State | Behaviour |
|---|---|
| `loading` | Skeleton mirroring the real layout: header block, four tab stubs, three timeline rows |
| `success` | Header always above the fold; Overview tab preloaded from `/summary` |
| `partial` | `degraded: ["products"]` → the Products panel shows "Couldn't load products" + retry, everything else stays live |
| `stale` | `syncAgeMinutes > 1440` on products → amber "Synced 2 days ago" chip + **Sync now** |
| `not-found` | Full-page "Client not found or not in your scope" + back to search. Copy must not distinguish the two causes |
| `merged` | `410` → auto-redirect to the survivor with a persistent banner: "This record was merged into Anna Kowalska on 2026-08-30" |
| `forbidden` | Reached with `client:read` but no tab permission → tab renders a lock panel naming the missing permission |
| `error` | Full-page error card with `requestId` for support |

**Header component states:** `kyc-verified` (green), `kyc-expiring` (amber, "Expires in 12 days"), `kyc-expired` / `kyc-rejected` (red), `blocked` (red banner across the header, all write actions disabled with tooltips).

#### S-CP-03 — Edit Contact Details (inline / drawer)

| State | Behaviour |
|---|---|
| `readonly` | Values with a pencil affordance, shown only when `permissions.canEdit` |
| `editing` | Per-field validation on blur; phone auto-formats to E.164 with the country flag |
| `saving` | Submit disabled with a spinner; fields stay readable, no layout shift |
| `success` | Toast "Saved", fields return to readonly, `ETag` updated in the store |
| `conflict` | `409 VERSION_CONFLICT` → two-column diff "Your change / Current value" per field, with **Keep mine** / **Take theirs** / **Cancel**. The user's typing is never silently discarded |
| `duplicate` | `409 CLIENT_DUPLICATE_EMAIL` → field-level error with a link to the conflicting client (name only if out of scope) |
| `error` | Non-field error banner above the form, retry keeps all input |

#### S-CP-04 — Products Panel

| State | Behaviour |
|---|---|
| `loading` | Three skeleton rows |
| `success` | Grouped by type; each row: masked number, status pill, balance, opened date |
| `empty` | "No products on file" + **Sync from core banking** when permitted |
| `error` | Inline panel error, does not block the rest of the card |
| `syncing` | `202` accepted → rows dim, "Syncing…" chip, polls every 2 s up to 30 s, then falls back to "Sync is taking longer than usual" |
| `sync-failed` | `503` → "Core banking unavailable. Showing data from 09 Sep 06:00" — last known data stays visible |

#### S-CP-05 — Merge Clients (admin wizard)

Three steps: pick duplicate → resolve fields → confirm.

| State | Behaviour |
|---|---|
| `selecting` | Search for the second record; self-selection blocked in the UI |
| `comparing` | Side-by-side field table with radio buttons per differing field; identical fields collapsed |
| `warning` | Product conflicts listed explicitly: "1 product will not be moved" |
| `confirming` | Requires typing the loser's `externalRef` to enable the button — the operation is irreversible |
| `saving` | Progress with a moved-record count |
| `success` | Redirect to the survivor with a summary banner |
| `error` | `422` conflicts render as a resolvable checklist, not a raw error string |

### 5.6 Edge cases

| ID | Case | Expected behaviour |
|---|---|---|
| CP-EC-01 | Two managers `PATCH` the same client concurrently | Second write fails `409 VERSION_CONFLICT`; UI renders the field-level diff; no lost update |
| CP-EC-02 | Client changes email to one held by a soft-deleted client | Allowed — the unique index is partial on `deleted_at IS NULL` |
| CP-EC-03 | Mother and son share a landline; manager searches by phone | Both returned in `results`; `exact: false`; disambiguation list shows DOB year to tell them apart |
| CP-EC-04 | Phone entered as `511 234 567` (national format) | Normalized to E.164 using the client's country before hashing; both forms find the same record. Normalization **validates**, it does not merely reformat: a retired trunk prefix (`0511 234 567`, dropped in Poland in 2009) still parses, to `+480511234567`, and accepting it would hash one person to two values and make them unfindable by the number they were created with. Unparseable or invalid → `400` |
| CP-EC-05 | Email differs only in case or has a `+tag` | Case is normalized before hashing (so `A@x.com` = `a@x.com`). `+tag` is **not** stripped — it is a semantically different address at some providers, and stripping it would merge distinct people |
| CP-EC-06 | Owner manager is deactivated while holding 60 clients | `RESTRICT` blocks the FK delete. Deactivation flow (RB-BR-07) forces bulk reassignment first; orphan clients are impossible |
| CP-EC-07 | KYC expires overnight while a manager has the card open | Next write returns `422` with an explanation; the UI polls `updatedAt` every 60 s and shows a "This record changed" banner |
| CP-EC-08 | Client turns out to be a minor (bad birth date from import) | DB `CHECK` rejects. The import lands in a quarantine table with the violated constraint recorded; no partial client row is created |
| CP-EC-09 | Merge is attempted on a client that is itself a merge survivor | Allowed — chains are permitted. `410` resolution follows `merged_into_id` transitively, capped at 10 hops to defeat a cycle, then `500 INTERNAL_ERROR` with an alert |
| CP-EC-10 | Merge attempted where both records are the same person's `ACTIVE` mortgage | Product conflict → skipped, reported in `skipped`, and an admin task is created to reconcile manually. The merge itself still succeeds |
| CP-EC-11 | Core banking returns a product for an unknown client | Rejected with `404 CLIENT_NOT_FOUND`; the message goes to the DLT; an alert fires. Never auto-create a client from a product feed |
| CP-EC-12 | GDPR erasure request | `POST /clients/{id}/erase` (admin + compliance dual approval). Sensitive columns overwritten with a tombstone, names replaced by `ERASED-<short-id>`, `deleted_at` set. **Rows are never dropped** — audit rows reference `client_id` and must stay resolvable. Interaction bodies are erased the same way; audit rows are untouched because they never contained plaintext (AR-01) |
| CP-EC-13 | KMS unreachable at read time | `503 DEPENDENCY_UNAVAILABLE`. Non-sensitive fields are **not** served as a partial card — a half-decrypted client card invites the manager to act on incomplete data |
| CP-EC-14 | Client with 0 products, 0 interactions, KYC `NOT_STARTED` (just imported) | Card renders fully with three separate `empty` states, each with its own next action. Never one generic "no data" |
| CP-EC-15 | 300-character name from a transliterated legal entity | `VARCHAR(100)` per part rejects with `400` naming the field and limit. The importer truncates to 100 and flags the row for review rather than failing the whole batch |
| CP-EC-16 | Manager opens a client 5 seconds after a supervisor reassigns it away | `GET` succeeds if the read started before the change; the next write returns `404 CLIENT_NOT_FOUND` (now out of scope). The UI shows "This client was reassigned" and returns to search |

---

## 6. Module 2 — Interaction Log

**Service:** `interaction-service` · **Phase:** MVP · **Prefix:** `IL`

The chronological feed that replaces "notes in Excel or on paper". One table backs calls, meetings, emails, chats, notes and tickets; only tickets carry a lifecycle.

### 6.1 User stories

| ID | As a… | I want to… | So that… | Acceptance criteria |
|---|---|---|---|---|
| IL-US-01 | Manager | see every touchpoint with a client in one reverse-chronological feed | I know what was already discussed before I dial | Feed merges all types; newest first; keyset-paginated at 50/page; first page p95 < 400 ms |
| IL-US-02 | Manager | log a call in under 20 seconds while still on the line | notes get written down instead of postponed and lost | Composer opens with one keystroke; type, subject and body are the only required fields; `occurredAt` defaults to now; saves optimistically |
| IL-US-03 | Manager | filter the feed by type and date range | I can find "that mortgage conversation from spring" without scrolling 200 entries | Multi-select type filter + date range; filters are URL-encoded and shareable; `empty-filtered` state offers "clear filters" |
| IL-US-04 | Manager | correct a typo right after saving a note | the record is accurate without a paper trail of trivial edits | Free edit for 15 minutes by the author; after that only an append-only correction linked to the original |
| IL-US-05 | Manager | raise a ticket from an interaction and track it to resolution | client issues do not get lost between the call and the back office | Ticket has status, priority, assignee and an SLA due date derived from priority; status transitions are validated |
| IL-US-06 | Manager | attach a document to an interaction | the signed form lives with the conversation, not in an inbox | Up to 5 files, 10 MB each; virus scan before the file is downloadable; blocked types rejected at upload |
| IL-US-07 | Supervisor | read every interaction for clients on my team, including who logged it | I can coach on quality and verify SLA claims | Team-scoped feed across clients; author column always populated; private notes are excluded (IL-BR-09) |
| IL-US-08 | Manager | mark a note private to me | I can record a personal reminder about tone or context without publishing it to the whole team | `visibility = PRIVATE` hides it from everyone but the author and admins; the audit trail still records its existence |

### 6.2 Data model

#### 6.2.1 Enumerated types

```sql
CREATE TYPE interaction_type       AS ENUM ('CALL','MEETING','EMAIL','CHAT','NOTE','TICKET');
CREATE TYPE interaction_direction  AS ENUM ('INBOUND','OUTBOUND','INTERNAL');
CREATE TYPE interaction_visibility AS ENUM ('TEAM','PRIVATE');
CREATE TYPE interaction_outcome    AS ENUM ('SUCCESSFUL','NO_ANSWER','CALLBACK_REQUESTED',
                                            'ESCALATED','REFUSED','NOT_APPLICABLE');
CREATE TYPE interaction_source     AS ENUM ('WEB','API','TELEPHONY_IMPORT','EMAIL_IMPORT','MIGRATION');
CREATE TYPE ticket_status          AS ENUM ('NEW','IN_PROGRESS','WAITING_CLIENT','RESOLVED','CLOSED','REJECTED');
CREATE TYPE ticket_priority        AS ENUM ('LOW','MEDIUM','HIGH','CRITICAL');
CREATE TYPE scan_status            AS ENUM ('PENDING','CLEAN','INFECTED','FAILED');
```

#### 6.2.2 `interactions`

```sql
CREATE TABLE interactions (
    id                UUID                   PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id         UUID                   NOT NULL,
    type              interaction_type       NOT NULL,
    direction         interaction_direction  NOT NULL,
    subject           VARCHAR(200)           NOT NULL,   -- plaintext: searchable
    body_enc          BYTEA                  NOT NULL,   -- sensitive: encrypted
    key_version       SMALLINT               NOT NULL DEFAULT 1,
    occurred_at       TIMESTAMPTZ            NOT NULL,
    duration_seconds  INTEGER,
    outcome           interaction_outcome    NOT NULL DEFAULT 'NOT_APPLICABLE',
    visibility        interaction_visibility NOT NULL DEFAULT 'TEAM',
    source            interaction_source     NOT NULL DEFAULT 'WEB',
    external_ref      VARCHAR(64),                       -- PBX call id, email message-id
    author_id         UUID                   NOT NULL,
    -- ticket subtype (see ck_interactions_ticket_fields)
    ticket_status     ticket_status,
    ticket_priority   ticket_priority,
    ticket_assignee_id UUID,
    sla_due_at        TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,
    resolution_note   TEXT,
    -- correction chain (IL-US-04)
    corrects_id       UUID,
    edited_at         TIMESTAMPTZ,
    edit_count        SMALLINT               NOT NULL DEFAULT 0,
    -- housekeeping
    version           INTEGER                NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ            NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ            NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    deleted_by        UUID,
    deletion_reason   TEXT,

    CONSTRAINT fk_int_client   FOREIGN KEY (client_id)          REFERENCES clients(id) ON DELETE RESTRICT,
    CONSTRAINT fk_int_author   FOREIGN KEY (author_id)          REFERENCES users(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_int_assignee FOREIGN KEY (ticket_assignee_id) REFERENCES users(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_int_corrects FOREIGN KEY (corrects_id)        REFERENCES interactions(id) ON DELETE RESTRICT,

    -- ticket fields exist if and only if the row is a ticket
    CONSTRAINT ck_interactions_ticket_fields CHECK (
        (type = 'TICKET') = (ticket_status IS NOT NULL)
        AND (type = 'TICKET') = (ticket_priority IS NOT NULL)
        AND (type = 'TICKET') = (sla_due_at IS NOT NULL)
    ),
    CONSTRAINT ck_interactions_resolved_pair CHECK (
        (ticket_status IN ('RESOLVED','CLOSED')) = (resolved_at IS NOT NULL)
        OR ticket_status IS NULL
    ),
    CONSTRAINT ck_interactions_resolution_note CHECK (
        ticket_status NOT IN ('RESOLVED','REJECTED') OR resolution_note IS NOT NULL
    ),
    CONSTRAINT ck_interactions_closed_after_resolved CHECK (
        closed_at IS NULL OR resolved_at IS NULL OR closed_at >= resolved_at
    ),
    -- duration only makes sense for synchronous contact
    CONSTRAINT ck_interactions_duration CHECK (
        (duration_seconds IS NULL)
        OR (type IN ('CALL','MEETING') AND duration_seconds BETWEEN 0 AND 86400)
    ),
    -- "occurred_at at most 5 min in the future" (clock-skew tolerance) is enforced by
    -- trigger trg_interactions_validate, not CHECK: now() is STABLE, not IMMUTABLE.
    CONSTRAINT ck_interactions_direction CHECK (
        type NOT IN ('NOTE','TICKET') OR direction = 'INTERNAL'
    ),
    CONSTRAINT ck_interactions_delete_reason CHECK (
        deleted_at IS NULL OR deletion_reason IS NOT NULL
    ),
    CONSTRAINT ck_interactions_no_self_correct CHECK (corrects_id IS NULL OR corrects_id <> id),
    CONSTRAINT ck_interactions_edit_count CHECK (edit_count BETWEEN 0 AND 100)
);
```

**Indexes**

```sql
-- The timeline query (IL-US-01): keyset on (occurred_at DESC, id DESC)
CREATE INDEX ix_int_client_timeline ON interactions (client_id, occurred_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- Filtered timeline (IL-US-03)
CREATE INDEX ix_int_client_type_time ON interactions (client_id, type, occurred_at DESC)
    WHERE deleted_at IS NULL;

-- "My open tickets" and the SLA sweep
CREATE INDEX ix_int_ticket_queue ON interactions (ticket_assignee_id, sla_due_at)
    WHERE type = 'TICKET' AND ticket_status NOT IN ('CLOSED','REJECTED') AND deleted_at IS NULL;

-- Subject search (bodies are encrypted — see 4.7)
CREATE INDEX ix_int_subject_trgm ON interactions USING gin (lower(subject) gin_trgm_ops)
    WHERE deleted_at IS NULL;

-- Telephony/email import idempotency
CREATE UNIQUE INDEX ux_int_external_ref ON interactions (source, external_ref)
    WHERE external_ref IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX ix_int_author ON interactions (author_id, occurred_at DESC) WHERE deleted_at IS NULL;
```

#### 6.2.3 `interaction_attachments`

```sql
CREATE TABLE interaction_attachments (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    interaction_id  UUID         NOT NULL,
    filename        VARCHAR(255) NOT NULL,
    content_type    VARCHAR(128) NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    storage_key     VARCHAR(512) NOT NULL UNIQUE,   -- object-store key, never a public URL
    checksum_sha256 BYTEA        NOT NULL,
    scan            scan_status  NOT NULL DEFAULT 'PENDING',
    scanned_at      TIMESTAMPTZ,
    uploaded_by     UUID         NOT NULL,
    uploaded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT fk_att_interaction FOREIGN KEY (interaction_id)
        REFERENCES interactions(id) ON DELETE CASCADE,
    CONSTRAINT fk_att_uploader FOREIGN KEY (uploaded_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_att_size CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    CONSTRAINT ck_att_scanned_pair CHECK ((scan = 'PENDING') = (scanned_at IS NULL)),
    CONSTRAINT ck_att_type CHECK (content_type IN (
        'application/pdf','image/jpeg','image/png','image/tiff',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'))
);

CREATE INDEX ix_att_interaction ON interaction_attachments (interaction_id) WHERE deleted_at IS NULL;

-- Max 5 attachments per interaction is enforced in the service layer inside the
-- upload transaction (SELECT … FOR UPDATE on the parent row), not by a constraint:
-- a per-parent count cannot be expressed as a CHECK.
```

### 6.3 API endpoints

---

#### `POST /clients/{clientId}/interactions` — log an interaction (IL-US-02)

**Permission:** `interaction:write` in client scope · **Headers:** `Idempotency-Key`

```json
{
  "type": "CALL",
  "direction": "OUTBOUND",
  "subject": "Mortgage rate question",
  "body": "Client asked about refinancing at the new 5.1% rate. Wants a written offer by Friday.",
  "occurredAt": "2026-09-09T11:30:00.000Z",
  "durationSeconds": 420,
  "outcome": "SUCCESSFUL",
  "visibility": "TEAM",
  "createTask": {
    "title": "Send refinancing offer",
    "dueAt": "2026-09-12T15:00:00.000Z",
    "priority": "HIGH"
  }
}
```

`createTask` is optional and creates a linked task in the **same transaction** (IL-BR-06) — the "note plus follow-up" flow is one action, not two.

**`201 Created`** · `Location: /api/v1/interactions/{id}`

```json
{
  "id": "e100…",
  "clientId": "9b21…",
  "type": "CALL", "direction": "OUTBOUND",
  "subject": "Mortgage rate question",
  "body": "Client asked about refinancing…",
  "occurredAt": "2026-09-09T11:30:00.000Z",
  "durationSeconds": 420,
  "outcome": "SUCCESSFUL", "visibility": "TEAM", "source": "WEB",
  "author": { "id": "3f1c…", "fullName": "Adam Nowak" },
  "attachments": [],
  "editableUntil": "2026-09-09T11:57:07.113Z",
  "linkedTaskId": "t900…",
  "version": 0,
  "createdAt": "2026-09-09T11:42:07.113Z"
}
```

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | `subject` empty or > 200 chars; `body` > 10 000 chars; `durationSeconds` on a `NOTE` |
| `403` | `PERMISSION_DENIED` | Client not in scope for writing |
| `404` | `CLIENT_NOT_FOUND` | Unknown or out-of-scope client |
| `409` | `IDEMPOTENCY_KEY_REUSED` | Same key, different payload |
| `422` | `BUSINESS_RULE_VIOLATED` | `occurredAt` more than 5 min in the future; non-`NOTE` on a `CLOSED` client (CP-BR-08); `type = TICKET` without `priority` |

---

#### `GET /clients/{clientId}/interactions` — timeline (IL-US-01, IL-US-03)

**Permission:** `interaction:read` in client scope
**Query:** `cursor`, `limit` (default 50, max 100), `type` (repeatable), `from`, `to`, `authorId`, `q` (subject only), `includeDeleted` (admin/auditor only)

**`200 OK`**

```json
{
  "content": [
    {
      "id": "e100…", "type": "CALL", "direction": "OUTBOUND",
      "subject": "Mortgage rate question",
      "bodyPreview": "Client asked about refinancing at the new 5.1% rate…",
      "occurredAt": "2026-09-09T11:30:00.000Z",
      "durationSeconds": 420, "outcome": "SUCCESSFUL", "visibility": "TEAM",
      "author": { "id": "3f1c…", "fullName": "Adam Nowak" },
      "attachmentCount": 0, "hasCorrection": false, "edited": false,
      "ticket": null
    },
    {
      "id": "e099…", "type": "TICKET", "direction": "INTERNAL",
      "subject": "Card declined abroad",
      "bodyPreview": "Client reports declines in Italy…",
      "occurredAt": "2026-09-05T08:02:00.000Z",
      "author": { "id": "3f1c…", "fullName": "Adam Nowak" },
      "ticket": {
        "status": "IN_PROGRESS", "priority": "HIGH",
        "assignee": { "id": "5b7e…", "fullName": "Ola Wiśniewska" },
        "slaDueAt": "2026-09-06T08:02:00.000Z", "slaBreached": true
      }
    }
  ],
  "nextCursor": "eyJ0cyI6IjIwMjYtMDktMDVUMDg6MDI6MDBaIiwiaWQiOiJlMDk5In0",
  "hasMore": true
}
```

`bodyPreview` is the first 160 decrypted characters. The full `body` requires `GET /interactions/{id}` — one `READ_SENSITIVE` audit event per opened item, rather than fifty per page scroll (IL-BR-11).

| Status | Code | Cause |
|---|---|---|
| `400` | `INVALID_CURSOR` | Malformed or expired cursor — client restarts from page 1 |
| `400` | `VALIDATION_FAILED` | `from` after `to`; `limit` > 100 |
| `403` | `PERMISSION_DENIED` | `includeDeleted=true` without the auditor/admin role |
| `404` | `CLIENT_NOT_FOUND` | Out of scope |

Empty timeline → `200` with `"content": []`.

---

#### `GET /interactions/{id}` — full detail

**Permission:** `interaction:read` in scope; `PRIVATE` rows require authorship or the admin role

**`200 OK`** — the create-response shape plus full `body`, `attachments[]`, and `corrections[]`.

| Status | Code | Cause |
|---|---|---|
| `404` | `INTERACTION_NOT_FOUND` | Absent, deleted, out of scope, or someone else's private note (rule ER-01) |

---

#### `PATCH /interactions/{id}` — edit inside the window (IL-US-04)

**Permission:** author only, within 15 minutes · **Headers:** `If-Match`

```json
{ "subject": "Mortgage refinancing question", "body": "Corrected: the rate discussed was 5.01%." }
```

**`200 OK`** — updated representation with `edited: true`, `editCount` incremented.

| Status | Code | Cause |
|---|---|---|
| `403` | `PERMISSION_DENIED` | Caller is not the author — supervisors and admins cannot silently rewrite another person's record |
| `409` | `VERSION_CONFLICT` | Stale `If-Match` |
| `422` | `INTERACTION_EDIT_WINDOW_CLOSED` | Past 15 min — response carries `details[0].correctionEndpoint` pointing at the correction route |
| `422` | `BUSINESS_RULE_VIOLATED` | Attempt to change `type`, `clientId` or `occurredAt` — all immutable |

---

#### `POST /interactions/{id}/corrections` — append-only amendment

**Permission:** `interaction:write` in scope

```json
{ "subject": "Correction: mortgage rate", "body": "The rate quoted in the original note was wrong; it was 5.01%, not 5.1%." }
```

Creates a **new** interaction with `corrects_id` set. The original is never mutated. `201 Created`.

| Status | Code | Cause |
|---|---|---|
| `404` | `INTERACTION_NOT_FOUND` | Out of scope |
| `422` | `BUSINESS_RULE_VIOLATED` | The target is itself a correction — chains are limited to one level (IL-BR-05) |

---

#### `DELETE /interactions/{id}` — soft delete

**Permission:** `interaction:delete` (supervisor scope `TEAM`, admin scope `ALL`)
**Query:** `reason` (required, ≥ 10 chars) · **Headers:** `If-Match`

**`204 No Content`**. The row stays in the table; it disappears from the feed but remains visible with `includeDeleted=true` to auditors, struck through and labelled with the deleter and reason.

| Status | Code | Cause |
|---|---|---|
| `403` | `ROLE_REQUIRED` | Managers cannot delete interactions at all — that is the whole point of an audit trail |
| `422` | `BUSINESS_RULE_VIOLATED` | The interaction has a linked open task; close or unlink first |

---

#### Ticket lifecycle

##### `PATCH /interactions/{id}/ticket`

**Permission:** `ticket:write` — assignee, the client's owner, or a supervisor over either

```json
{ "status": "RESOLVED", "resolutionNote": "Card unblocked, client confirmed a successful transaction.", "priority": "HIGH", "assigneeId": "5b7e…" }
```

**`200 OK`** — full interaction with the updated `ticket` block.

| Status | Code | Cause |
|---|---|---|
| `403` | `PERMISSION_DENIED` | Caller is neither assignee, owner, nor supervising either |
| `409` | `ILLEGAL_STATE_TRANSITION` | e.g. `CLOSED → IN_PROGRESS`; `details` lists the legal targets |
| `422` | `BUSINESS_RULE_VIOLATED` | `RESOLVED`/`REJECTED` without `resolutionNote`; raising priority to `CRITICAL` without the supervisor role |

##### `GET /tickets`

**Permission:** `ticket:read` (scope-filtered)
**Query:** `assigneeId`, `status` (repeatable), `priority`, `slaBreached=true`, `clientId`, `cursor`, `limit`

Powers the "my open tickets" queue and the supervisor's SLA view.

---

#### Attachments (IL-US-06)

| Method | Path | Notes |
|---|---|---|
| `POST` | `/interactions/{id}/attachments` | `multipart/form-data`. `201` with `scan: "PENDING"`. `413 PAYLOAD_TOO_LARGE` > 10 MB; `415 UNSUPPORTED_MEDIA_TYPE` for a type outside `ck_att_type`; `422 ATTACHMENT_LIMIT_REACHED` at 5 files |
| `GET` | `/interactions/{id}/attachments/{attachmentId}` | `302` to a 60-second pre-signed object-store URL. `409 ATTACHMENT_SCAN_PENDING` while `PENDING`; `403 ATTACHMENT_INFECTED` when `INFECTED`. Emits `READ_SENSITIVE` |
| `DELETE` | `/interactions/{id}/attachments/{attachmentId}` | Author within the edit window, or admin. `204` |

---

#### `GET /interactions` — cross-client feed (IL-US-07)

**Permission:** `interaction:read` scope `TEAM` or `ALL`
**Query:** `teamId`, `authorId`, `clientId`, `type`, `from`, `to`, `cursor`, `limit`

Supervisor coaching view. `PRIVATE` interactions are excluded regardless of scope (IL-BR-09).

### 6.4 Business logic rules

| ID | Rule |
|---|---|
| IL-BR-01 | An interaction is **immutable in substance**. `type`, `client_id`, `occurred_at` and `author_id` can never change. Only `subject` and `body` are editable, only by the author, only within 15 minutes. |
| IL-BR-02 | The 15-minute edit window is measured from `created_at`, not `occurred_at` — a manager back-dating yesterday's call still gets 15 minutes to fix a typo. |
| IL-BR-03 | After the window, the only remedy is a **correction**: a new row with `corrects_id` pointing at the original. The feed renders the pair together with the original visibly superseded. Nothing is ever rewritten in place. |
| IL-BR-04 | `edit_count` is capped at 100 by constraint. A row hitting the cap is flagged for review — it indicates automation misuse, not human editing. |
| IL-BR-05 | Correction chains are one level deep. Correcting a correction targets the **original**; attempting otherwise returns `422`. This keeps the "what is currently true" resolution to a single hop. |
| IL-BR-06 | `createTask` inside `POST /interactions` runs in one transaction. If task creation fails, the interaction is rolled back too — a manager who thought they set a reminder must never end up with only a note. |
| IL-BR-07 | **SLA is derived from priority at ticket creation** and frozen: `CRITICAL` 4 h, `HIGH` 24 h, `MEDIUM` 72 h, `LOW` 168 h — business hours, Mon–Fri 09:00–18:00 in the team's timezone. Raising priority later **recomputes `sla_due_at` from the original `occurred_at`**, which may make it immediately breached. That is intentional: escalation should reveal lateness, not reset the clock. |
| IL-BR-08 | Legal ticket transitions: `NEW → IN_PROGRESS \| REJECTED`; `IN_PROGRESS → WAITING_CLIENT \| RESOLVED \| REJECTED`; `WAITING_CLIENT → IN_PROGRESS \| RESOLVED`; `RESOLVED → CLOSED \| IN_PROGRESS` (reopen); `CLOSED →` (terminal); `REJECTED →` (terminal). `WAITING_CLIENT` pauses the SLA clock; the paused duration is recorded and excluded from breach calculation. |
| IL-BR-09 | `visibility = PRIVATE` restricts reads to the author and the admin role. Supervisors do **not** see private notes — otherwise the feature is theatre and managers go back to paper. Their *existence* is still audited: an admin can see that a private note exists on a client, and its metadata, without its body. |
| IL-BR-10 | Every write emits `interaction.created`, `interaction.updated`, `interaction.deleted`, `interaction.corrected` or `ticket.status_changed` through the outbox. `subject` and `body` are masked in `changedFields` per AR-01. |
| IL-BR-11 | Reading a full body or downloading an attachment emits `READ_SENSITIVE`. Reading the timeline (previews only) does **not** — otherwise one scroll session generates hundreds of audit rows and the trail becomes unusable in exactly the investigation it exists for. |
| IL-BR-12 | Imported interactions (`source` in `TELEPHONY_IMPORT`, `EMAIL_IMPORT`) are deduplicated on `(source, external_ref)`. A repeated import is a no-op returning the existing row with `200`, not `201`. |
| IL-BR-13 | An interaction cannot be created against a soft-deleted or merged client. Post-merge, interactions belonging to the loser are re-pointed to the survivor and keep their original `occurred_at`, so the merged timeline stays chronologically honest. |
| IL-BR-14 | Deleting an interaction never cascades to its linked task. The task survives with `source_interaction_id` intact; the UI shows "source interaction was removed". |

### 6.5 Screens & components

#### S-IL-01 — Interaction Timeline (tab on the client card)

Filter bar over a virtualized reverse-chronological list with day separators.

| State | Behaviour |
|---|---|
| `loading` | Five skeleton rows with a realistic mixed-height layout |
| `success` | Grouped under "Today / Yesterday / 5 September 2026"; each row: type icon, subject, 160-char preview, author, relative time, attachment and ticket badges |
| `loading-more` | Intersection-observer triggers the next keyset page; two skeleton rows at the foot; existing rows stay scrollable |
| `empty` | "No interactions logged yet" + **Log first interaction** |
| `empty-filtered` | "No interactions match these filters" + **Clear filters**, with the active filter set echoed as removable chips |
| `error` | Inline error card replacing the list, retry preserves the filters |
| `error-partial` | Page 1 loaded, page 2 failed → keep rows, show "Couldn't load more" + retry at the foot |
| `stale-cursor` | `400 INVALID_CURSOR` → silently refetch page 1, toast "Feed refreshed" |
| `optimistic` | A just-created interaction appears immediately with a subdued style, replaced by the server row on `201`, or removed with an error toast on failure |
| `offline` | Banner; the composer still opens and drafts persist in local storage |

**Row sub-states:** `default`, `edited` (pencil + "edited 11:57"), `corrected` (struck subject + "See correction"), `deleted` (auditors only: struck through, red, deleter and reason shown), `private` (lock icon, "Only you can see this"), `sla-breached` (red left border on ticket rows).

#### S-IL-02 — Interaction Composer (modal, IL-US-02)

| State | Behaviour |
|---|---|
| `opening` | Type defaults to `CALL`; `occurredAt` defaults to now; focus lands in `subject`. Opens on `N` from anywhere on the card |
| `editing` | Live character counters on subject (200) and body (10 000); duration field appears only for `CALL`/`MEETING`; a "Create follow-up task" toggle expands inline task fields |
| `validation-error` | Field-level messages under each control; submit stays enabled so the user can retry after fixing |
| `saving` | Submit shows a spinner; the modal cannot be dismissed; `Esc` prompts "Discard this note?" |
| `success` | Modal closes, row appears in the feed, toast "Interaction logged" with **Undo** for 8 s (issues the delete inside the edit window) |
| `error-retryable` | `503`/network → "Couldn't save. Your note is kept." with **Retry**; the draft is written to local storage keyed by client ID |
| `error-fatal` | `422` on a closed client → explanatory message, the type selector narrows to `NOTE` only |
| `draft-restored` | On reopen, a restored draft shows a "Draft from 11:42" banner with **Discard** |

#### S-IL-03 — Interaction Detail (drawer)

| State | Behaviour |
|---|---|
| `loading` | Skeleton in the drawer; the feed behind stays interactive |
| `success` | Full decrypted body, attachment list, correction chain, edit affordance while `editableUntil` is in the future |
| `window-expiring` | Under 2 min left → countdown next to the Edit button |
| `window-closed` | Edit becomes **Add correction** |
| `forbidden` | Opening another user's private note → `404`; the row is removed from the feed and a toast explains it is no longer visible |
| `error` | In-drawer error with retry; the feed is unaffected |

#### S-IL-04 — Ticket Panel

| State | Behaviour |
|---|---|
| `success` | Status stepper `NEW → IN_PROGRESS → RESOLVED → CLOSED`, SLA countdown, assignee picker |
| `sla-warning` | Under 20% of the SLA window left → amber countdown |
| `sla-breached` | Red "Overdue by 3 h 12 m" chip; escalation notice if escalated |
| `sla-paused` | `WAITING_CLIENT` → the countdown greys out with "Paused since 07 Sep" |
| `transition-invalid` | Illegal targets are disabled in the picker with a tooltip, so `409` is essentially unreachable through the UI |
| `saving` | Stepper dims; optimistic status applied, rolled back on error |

#### S-IL-05 — Attachment Uploader

| State | Behaviour |
|---|---|
| `idle` | Drop zone, "PDF, JPG, PNG, DOCX, XLSX · max 10 MB · up to 5 files" |
| `uploading` | Per-file progress bar with cancel |
| `scanning` | "Checking file…" chip; download disabled |
| `clean` | Filename becomes a download link |
| `infected` | Red row "File blocked by virus scan", download permanently disabled, supervisor notified |
| `rejected` | Client-side pre-check on size and type, so the user gets the message before waiting on an upload |
| `limit-reached` | Drop zone replaced by "5 of 5 attachments" |

#### S-IL-06 — Team Interaction Feed (supervisor, IL-US-07)

| State | Behaviour |
|---|---|
| `loading` / `success` | Cross-client feed with a client-name column; filters for manager, type, date |
| `empty` | "No interactions from your team in this period" + a date-range widener |
| `forbidden` | A manager reaching the URL directly gets the `forbidden` state naming the required `TEAM` scope |

### 6.6 Edge cases

| ID | Case | Expected behaviour |
|---|---|---|
| IL-EC-01 | Manager logs a call at 23:59:59 and the client's timezone is a day ahead | `occurred_at` is stored in UTC; the feed groups by the **viewer's** timezone; the day separator can therefore differ between viewers, which is correct |
| IL-EC-02 | Client's clock is 3 minutes fast; `occurredAt` arrives slightly in the future | Accepted — the constraint allows 5 min skew. Beyond that, `422` naming the server time |
| IL-EC-03 | Telephony import delivers the same call twice | `ux_int_external_ref` makes the second a no-op; API returns `200` with the existing row (IL-BR-12) |
| IL-EC-04 | Two managers log the same call from two devices | Both rows persist — the system cannot know they are duplicates. The feed groups interactions within a 60-second window on the same client with a "possible duplicate" hint and a supervisor merge action |
| IL-EC-05 | Body contains a full card number | A PAN detector (Luhn + length) runs before encryption; matches are masked to the last 4 digits and the manager is warned inline before saving. The unmasked value never reaches storage |
| IL-EC-06 | Manager starts a note, session token expires, hits save | `401` → the draft is preserved in local storage, a silent refresh is attempted, and on success the save is retried transparently. Only if refresh fails does the user see a login prompt |
| IL-EC-07 | Attachment upload succeeds; interaction creation then fails | Attachments upload only against an already-created interaction, so this ordering cannot occur. Orphaned object-store blobs from cancelled uploads are swept after 24 h |
| IL-EC-08 | Ticket assignee is deactivated with 12 open tickets | `RESTRICT` blocks the FK. The deactivation flow requires bulk reassignment first (RB-BR-07) |
| IL-EC-09 | Priority raised `LOW → CRITICAL` on a 5-day-old ticket | `sla_due_at` recomputes to `occurred_at + 4 business hours` — already past, so the ticket immediately shows as breached and escalates. Intentional per IL-BR-07 |
| IL-EC-10 | Ticket sits in `WAITING_CLIENT` for 3 weeks | SLA is paused, so no breach. A separate staleness rule flags `WAITING_CLIENT` beyond 14 days to the supervisor — a paused clock must not become a hiding place |
| IL-EC-11 | A private note's author leaves the bank | The note becomes admin-visible only. During the deactivation flow the manager is prompted to convert private notes to `TEAM` or accept that they become inaccessible; both choices are audited |
| IL-EC-12 | Client merge with 400 interactions across both records | Re-pointing runs in batches of 100 inside one transaction; the merge endpoint holds a row lock on both clients. Timeline order after the merge is by `occurred_at`, so the two histories interleave correctly |
| IL-EC-13 | Body of exactly 10 000 characters, multibyte | The limit counts Unicode code points, not bytes, so a Cyrillic note is not penalized. The `BYTEA` column has ample headroom |
| IL-EC-14 | Two people edit the same interaction inside the 15-minute window | Only the author can edit, so concurrency is limited to one person's two tabs. `If-Match` still catches it and returns `409` with a diff |
| IL-EC-15 | Kafka is down while an interaction is created | The write **succeeds** — the outbox row commits with it. Publication retries in the background. The audit event is delayed, never lost, which is exactly the guarantee the outbox buys |
| IL-EC-16 | Auditor filters the deleted-interaction view over 5 years | Keyset pagination plus the `(client_id, occurred_at DESC, id DESC)` index keeps it constant-time per page regardless of depth |

---

## 7. Module 3 — Task / Reminder

**Service:** `interaction-service` · **Phase:** v3 · **Prefix:** `TR`

Follow-up actions with due dates, plus the supervisor dashboard that surfaces overdue work across a team — the second half of the problem statement ("супервайзер не видит просроченные задачи по клиентам в реальном времени").

### 7.1 User stories

| ID | As a… | I want to… | So that… | Acceptance criteria |
|---|---|---|---|---|
| TR-US-01 | Manager | create a follow-up task from an interaction in one action | commitments made on a call turn into tracked work | The composer's task toggle creates the task in the same transaction; the task links back to its source interaction |
| TR-US-02 | Manager | see my tasks for today and this week, sorted by urgency | I start the day knowing what is due without building my own list | "My Day" groups tasks into Overdue / Today / This week / Later; overdue first; p95 < 400 ms |
| TR-US-03 | Manager | complete a task with a short note | the outcome is recorded, not just the fact of ticking a box | Completion requires a note when the task is overdue; `completed_at` and `completed_by` are stored; a `task.completed` event is emitted |
| TR-US-04 | Manager | snooze a task when a client asks me to call back next week | a legitimate delay does not read as a failure | Snooze pushes `due_at`, requires a reason, capped at 3 snoozes per task; every snooze is audited |
| TR-US-05 | Supervisor | see every overdue task across my team on one dashboard, live | I can intervene before an SLA is missed instead of hearing about it after | Dashboard aggregates by manager and by age bucket; refreshes every 60 s; drill-down to the client card in one click |
| TR-US-06 | Supervisor | reassign a task to a different manager | holidays and sick leave do not create silent gaps | Reassignment requires a reason; both managers are notified; the task history records the transfer |
| TR-US-07 | Manager | get a reminder before a task is due | I act in time rather than discovering the overdue badge | Reminder offset defaults to 1 h before `due_at`, configurable per task; in-app plus email |
| TR-US-08 | Supervisor | see which of my managers are systematically late | coaching is grounded in data, not impressions | Team summary shows per-manager open, overdue, completed-on-time and average-days-late over a selectable period |

### 7.2 Data model

#### 7.2.1 Enumerated types

```sql
CREATE TYPE task_status   AS ENUM ('OPEN','IN_PROGRESS','DONE','CANCELLED');
CREATE TYPE task_priority AS ENUM ('LOW','MEDIUM','HIGH','URGENT');
CREATE TYPE task_type     AS ENUM ('CALLBACK','DOCUMENT_REQUEST','KYC_REFRESH',
                                   'FOLLOW_UP','MEETING_PREP','COMPLIANCE','OTHER');
CREATE TYPE reminder_channel AS ENUM ('IN_APP','EMAIL');
CREATE TYPE reminder_status  AS ENUM ('SCHEDULED','SENT','FAILED','CANCELLED');
```

#### 7.2.2 `tasks`

```sql
CREATE TABLE tasks (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id            UUID          NOT NULL,
    source_interaction_id UUID,
    title                VARCHAR(200)  NOT NULL,
    description          TEXT,
    task_type            task_type     NOT NULL DEFAULT 'FOLLOW_UP',
    priority             task_priority NOT NULL DEFAULT 'MEDIUM',
    status               task_status   NOT NULL DEFAULT 'OPEN',
    due_at               TIMESTAMPTZ   NOT NULL,
    original_due_at      TIMESTAMPTZ   NOT NULL,     -- frozen at creation; snoozes never touch it
    assignee_id          UUID          NOT NULL,
    created_by           UUID          NOT NULL,
    -- snooze
    snooze_count         SMALLINT      NOT NULL DEFAULT 0,
    last_snoozed_at      TIMESTAMPTZ,
    last_snooze_reason   TEXT,
    -- completion / cancellation
    completed_at         TIMESTAMPTZ,
    completed_by         UUID,
    completion_note      TEXT,
    cancelled_at         TIMESTAMPTZ,
    cancelled_by         UUID,
    cancellation_reason  TEXT,
    -- escalation (TR-BR-08)
    escalation_level     SMALLINT      NOT NULL DEFAULT 0,
    escalated_at         TIMESTAMPTZ,
    -- housekeeping
    version              INTEGER       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT fk_tasks_client    FOREIGN KEY (client_id)  REFERENCES clients(id)  ON DELETE RESTRICT,
    CONSTRAINT fk_tasks_assignee  FOREIGN KEY (assignee_id) REFERENCES users(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_tasks_creator   FOREIGN KEY (created_by) REFERENCES users(id)    ON DELETE RESTRICT,
    CONSTRAINT fk_tasks_completer FOREIGN KEY (completed_by) REFERENCES users(id)  ON DELETE RESTRICT,
    CONSTRAINT fk_tasks_source    FOREIGN KEY (source_interaction_id)
        REFERENCES interactions(id) ON DELETE SET NULL,

    CONSTRAINT ck_tasks_done_fields CHECK (
        (status = 'DONE') = (completed_at IS NOT NULL)
        AND (status = 'DONE') = (completed_by IS NOT NULL)
    ),
    CONSTRAINT ck_tasks_cancelled_fields CHECK (
        (status = 'CANCELLED') = (cancelled_at IS NOT NULL)
        AND (status <> 'CANCELLED' OR cancellation_reason IS NOT NULL)
    ),
    CONSTRAINT ck_tasks_snooze_cap    CHECK (snooze_count BETWEEN 0 AND 3),
    CONSTRAINT ck_tasks_snooze_reason CHECK (snooze_count = 0 OR last_snooze_reason IS NOT NULL),
    CONSTRAINT ck_tasks_due_forward   CHECK (due_at >= original_due_at),
    CONSTRAINT ck_tasks_escalation    CHECK (escalation_level BETWEEN 0 AND 3),
    CONSTRAINT ck_tasks_escalated_pair CHECK ((escalation_level = 0) = (escalated_at IS NULL)),
    CONSTRAINT ck_tasks_title_not_blank CHECK (length(btrim(title)) >= 3)
);
```

**On `is_overdue`.** There is deliberately no stored or generated `is_overdue` column: `now()` is not `IMMUTABLE`, so PostgreSQL cannot use it in a `GENERATED ALWAYS AS` expression, and a materialized boolean would need a sweeper to stay honest. Overdue is computed in the query as `due_at < now() AND status IN ('OPEN','IN_PROGRESS')`, served by a partial index:

```sql
-- The dashboard query (TR-US-05). Partial index keeps only live work.
CREATE INDEX ix_tasks_open_due ON tasks (due_at, assignee_id)
    WHERE status IN ('OPEN','IN_PROGRESS');

-- "My Day" (TR-US-02)
CREATE INDEX ix_tasks_assignee_due ON tasks (assignee_id, due_at)
    WHERE status IN ('OPEN','IN_PROGRESS');

-- Open tasks on a client card
CREATE INDEX ix_tasks_client ON tasks (client_id, status, due_at);

-- Team analytics (TR-US-08)
CREATE INDEX ix_tasks_completed ON tasks (completed_by, completed_at)
    WHERE status = 'DONE';

-- Escalation sweep
CREATE INDEX ix_tasks_escalation_sweep ON tasks (due_at)
    WHERE status IN ('OPEN','IN_PROGRESS') AND escalation_level < 3;
```

#### 7.2.3 `task_reminders`

```sql
CREATE TABLE task_reminders (
    id          UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id     UUID             NOT NULL,
    remind_at   TIMESTAMPTZ      NOT NULL,
    channel     reminder_channel NOT NULL,
    status      reminder_status  NOT NULL DEFAULT 'SCHEDULED',
    attempts    SMALLINT         NOT NULL DEFAULT 0,
    sent_at     TIMESTAMPTZ,
    last_error  TEXT,
    created_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),

    CONSTRAINT fk_reminders_task FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
    CONSTRAINT uq_reminders UNIQUE (task_id, remind_at, channel),
    CONSTRAINT ck_reminders_attempts CHECK (attempts BETWEEN 0 AND 5),
    CONSTRAINT ck_reminders_sent_pair CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

CREATE INDEX ix_reminders_due ON task_reminders (remind_at)
    WHERE status = 'SCHEDULED';
```

#### 7.2.4 `task_history`

Reassignments, snoozes and escalations need a queryable local history — the audit log lives in another service and is not joinable.

```sql
CREATE TABLE task_history (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id     UUID        NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    changed_by  UUID        NOT NULL,
    change_type VARCHAR(32) NOT NULL,   -- CREATED, REASSIGNED, SNOOZED, ESCALATED, STATUS_CHANGED
    from_value  TEXT,
    to_value    TEXT,
    reason      TEXT,

    CONSTRAINT fk_task_history_task FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
    CONSTRAINT fk_task_history_user FOREIGN KEY (changed_by) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE INDEX ix_task_history_task ON task_history (task_id, changed_at DESC);
```

This duplicates part of the audit trail on purpose: `task_history` is a **product feature** ("who moved this task?" rendered in the drawer), while `audit_log` is a **compliance artifact**. They have different retention, different access rules and different owners; coupling them would compromise both.

### 7.3 API endpoints

---

#### `POST /tasks` — create (TR-US-01)

**Permission:** `task:write` in client scope · **Headers:** `Idempotency-Key`

```json
{
  "clientId": "9b21…",
  "sourceInteractionId": "e100…",
  "title": "Send refinancing offer",
  "description": "Written offer at 5.01%, valid 14 days.",
  "taskType": "DOCUMENT_REQUEST",
  "priority": "HIGH",
  "dueAt": "2026-09-12T15:00:00.000Z",
  "assigneeId": "3f1c…",
  "reminderOffsetMinutes": 60,
  "reminderChannels": ["IN_APP", "EMAIL"]
}
```

**`201 Created`** · `Location: /api/v1/tasks/{id}`

```json
{
  "id": "t900…",
  "client": { "id": "9b21…", "displayName": "Anna Kowalska" },
  "sourceInteractionId": "e100…",
  "title": "Send refinancing offer",
  "taskType": "DOCUMENT_REQUEST", "priority": "HIGH", "status": "OPEN",
  "dueAt": "2026-09-12T15:00:00.000Z",
  "originalDueAt": "2026-09-12T15:00:00.000Z",
  "overdue": false,
  "hoursUntilDue": 75.3,
  "assignee": { "id": "3f1c…", "fullName": "Adam Nowak" },
  "createdBy": { "id": "3f1c…", "fullName": "Adam Nowak" },
  "snoozeCount": 0, "snoozesRemaining": 3,
  "escalationLevel": 0,
  "reminders": [
    { "remindAt": "2026-09-12T14:00:00.000Z", "channel": "IN_APP", "status": "SCHEDULED" },
    { "remindAt": "2026-09-12T14:00:00.000Z", "channel": "EMAIL",  "status": "SCHEDULED" }
  ],
  "version": 0
}
```

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | `title` under 3 chars; `dueAt` missing or malformed; unknown `taskType` |
| `403` | `PERMISSION_DENIED` | Assigning to a user outside the caller's team (managers may only self-assign) |
| `404` | `CLIENT_NOT_FOUND` / `USER_NOT_FOUND` | Client out of scope, or unknown assignee |
| `422` | `BUSINESS_RULE_VIOLATED` | `dueAt` in the past; `dueAt` more than 2 years out; assignee is deactivated; client is `CLOSED` |

---

#### `GET /tasks` — scoped task list (TR-US-02)

**Permission:** `task:read` (scope-filtered)
**Query:** `assigneeId`, `clientId`, `status` (repeatable), `priority`, `taskType`, `overdue` (`true`/`false`), `dueFrom`, `dueTo`, `bucket` (`OVERDUE|TODAY|THIS_WEEK|LATER`), `page`, `size`, `sort`

Default when no filter is supplied: the caller's own open tasks, soonest first.

**`200 OK`**

```json
{
  "content": [
    { "id": "t900…", "title": "Send refinancing offer",
      "client": { "id": "9b21…", "displayName": "Anna Kowalska" },
      "dueAt": "2026-09-10T15:00:00.000Z", "priority": "HIGH", "status": "OPEN",
      "overdue": true, "overdueByHours": 20.7, "escalationLevel": 1,
      "assignee": { "id": "3f1c…", "fullName": "Adam Nowak" } }
  ],
  "buckets": { "OVERDUE": 3, "TODAY": 5, "THIS_WEEK": 11, "LATER": 8 },
  "page": 0, "size": 25, "totalElements": 27, "totalPages": 2, "hasNext": true
}
```

`buckets` counts the **whole filtered set**, not the current page, so the UI's group headers stay correct while paging.

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | `dueFrom` after `dueTo`; unknown `bucket` |
| `403` | `SCOPE_VIOLATION` | `assigneeId` names someone outside the caller's scope |

---

#### `GET /tasks/{id}`

**Permission:** `task:read` in scope. **`200 OK`** — full representation plus `history[]` from `task_history`.

| Status | Code | Cause |
|---|---|---|
| `404` | `TASK_NOT_FOUND` | Absent or out of scope |

---

#### `PATCH /tasks/{id}` — edit

**Permission:** assignee, creator, or a supervisor over the assignee · **Headers:** `If-Match`

```json
{ "title": "Send refinancing offer (revised)", "priority": "URGENT", "dueAt": "2026-09-11T12:00:00.000Z" }
```

| Status | Code | Cause |
|---|---|---|
| `403` | `PERMISSION_DENIED` | Not assignee, creator, or supervising supervisor |
| `409` | `VERSION_CONFLICT` | Stale `If-Match` |
| `422` | `BUSINESS_RULE_VIOLATED` | Editing a `DONE` or `CANCELLED` task; moving `dueAt` **earlier than** `original_due_at` (use a new task instead — see TR-BR-04); changing `clientId` (immutable) |

---

#### `POST /tasks/{id}/complete` (TR-US-03)

**Permission:** assignee, or a supervisor over the assignee

```json
{ "note": "Offer sent by email at 14:20, client confirmed receipt.", "logInteraction": true }
```

`logInteraction: true` creates a linked `NOTE` interaction in the same transaction, so completing a task also leaves a trace on the client's timeline.

**`200 OK`** — task with `status: "DONE"`, `completedAt`, `completedBy`, and `linkedInteractionId` when one was created.

| Status | Code | Cause |
|---|---|---|
| `403` | `PERMISSION_DENIED` | Not the assignee; supervisors may complete but the response records who actually did it |
| `409` | `ILLEGAL_STATE_TRANSITION` | Task is already `DONE` or `CANCELLED` |
| `422` | `BUSINESS_RULE_VIOLATED` | `note` missing on an overdue task (TR-BR-05); `note` over 2 000 chars |

---

#### `POST /tasks/{id}/snooze` (TR-US-04)

**Permission:** assignee only

```json
{ "newDueAt": "2026-09-16T10:00:00.000Z", "reason": "Client is abroad until 15 Sep, asked to be called after." }
```

**`200 OK`** — updated task; `snoozeCount` incremented, `snoozesRemaining` decremented, reminders rescheduled.

| Status | Code | Cause |
|---|---|---|
| `403` | `PERMISSION_DENIED` | Only the assignee may snooze — a supervisor postponing someone else's work is a reassignment or a due-date edit, not a snooze |
| `422` | `TASK_SNOOZE_LIMIT_REACHED` | Already snoozed 3 times; the response suggests escalation to a supervisor |
| `422` | `BUSINESS_RULE_VIOLATED` | `newDueAt` not in the future; more than 30 days beyond `original_due_at`; `reason` under 10 chars |

---

#### `POST /tasks/{id}/reassign` (TR-US-06)

**Permission:** `task:reassign` — supervisor scope `TEAM`, admin scope `ALL`

```json
{ "newAssigneeId": "4a2d…", "reason": "Adam is on leave until 20 Sep", "notify": true }
```

**`200 OK`** — updated task; a `REASSIGNED` row is appended to `task_history`.

| Status | Code | Cause |
|---|---|---|
| `403` | `SCOPE_VIOLATION` | Target user outside the supervisor's team |
| `422` | `BUSINESS_RULE_VIOLATED` | New assignee equals current; new assignee is deactivated or lacks `MANAGER`; task is `DONE`/`CANCELLED` |

---

#### `DELETE /tasks/{id}` — cancel

**Permission:** creator or supervisor · **Query:** `reason` (required, ≥ 10 chars) · **Headers:** `If-Match`

Sets `status = 'CANCELLED'`. Tasks are never hard-deleted — a cancelled commitment is itself information.

**`204 No Content`** · `422 BUSINESS_RULE_VIOLATED` when already `DONE`.

---

#### `GET /dashboard/overdue` — supervisor live view (TR-US-05)

**Permission:** `dashboard:team` (supervisor scope `TEAM`, admin scope `ALL`)
**Query:** `teamId` (admin only), `managerId`, `minAgeHours`, `priority`

**`200 OK`**

```json
{
  "generatedAt": "2026-09-09T11:42:07.113Z",
  "scope": { "teamId": "77aa…", "teamName": "Retail Warsaw North" },
  "totals": { "overdue": 14, "dueToday": 22, "openTotal": 87, "slaBreachedTickets": 3 },
  "ageBuckets": { "under24h": 6, "d1to3": 5, "d3to7": 2, "over7d": 1 },
  "byManager": [
    { "manager": { "id": "3f1c…", "fullName": "Adam Nowak" },
      "overdue": 6, "dueToday": 9, "openTotal": 31,
      "oldestOverdueHours": 52.4, "onTimeCompletionRate": 0.82 }
  ],
  "topOverdue": [
    { "id": "t900…", "title": "Send refinancing offer",
      "client": { "id": "9b21…", "displayName": "Anna Kowalska" },
      "assignee": { "id": "3f1c…", "fullName": "Adam Nowak" },
      "dueAt": "2026-09-07T15:00:00.000Z", "overdueByHours": 52.4,
      "priority": "HIGH", "escalationLevel": 2 }
  ]
}
```

`topOverdue` is capped at 50, ordered by `overdueByHours DESC`. The response is cached for 30 s per `(teamId, filter)` key; `generatedAt` lets the UI show data age honestly instead of implying real-time.

| Status | Code | Cause |
|---|---|---|
| `403` | `ROLE_REQUIRED` | Caller is a manager |
| `403` | `SCOPE_VIOLATION` | Supervisor requesting another team's `teamId` |

---

#### `GET /dashboard/team-summary` — coaching metrics (TR-US-08)

**Permission:** `dashboard:team`
**Query:** `teamId`, `from`, `to` (default: last 30 days), `granularity` (`DAY|WEEK`)

Returns per-manager counts of `created`, `completed`, `completedOnTime`, `overdueNow`, `avgDaysLate`, `snoozeRate`, plus a time series for charting.

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | Range over 365 days |

---

#### `GET /clients/{clientId}/tasks`

**Permission:** `task:read` in client scope. Convenience view backing the client card's Tasks tab; same filters as `GET /tasks`.

### 7.4 Business logic rules

| ID | Rule |
|---|---|
| TR-BR-01 | Every task belongs to exactly one client. `client_id` is `NOT NULL` and immutable — this is what lets task RBAC derive from the client's team without a second scoping mechanism, and it matches the domain: a bank task without a customer is not follow-up work. |
| TR-BR-02 | Overdue is **computed, never stored**: `due_at < now() AND status IN ('OPEN','IN_PROGRESS')`. A `DONE` task completed late is *not* overdue — it is "completed late", a separate metric derived from `completed_at > original_due_at`. |
| TR-BR-03 | Legal transitions: `OPEN → IN_PROGRESS \| DONE \| CANCELLED`; `IN_PROGRESS → OPEN \| DONE \| CANCELLED`; `DONE → OPEN` (reopen, supervisor only, requires a reason); `CANCELLED →` terminal. |
| TR-BR-04 | `due_at` may only move **forward** relative to `original_due_at` (constraint `ck_tasks_due_forward`). Pulling a deadline earlier would erase the record of the original commitment; the correct action is a new, more urgent task. |
| TR-BR-05 | Completing an **overdue** task requires a `note`. On-time completion does not. This keeps friction where the information is actually valuable and nowhere else. |
| TR-BR-06 | Snoozing is capped at 3 per task, each ≤ 30 days past `original_due_at`, each with a reason. A 4th request returns `422` and prompts escalation — the cap is what stops "snooze forever" from replacing "ask for help". |
| TR-BR-07 | Reminders are created at `due_at − reminderOffsetMinutes` per requested channel. Editing `due_at` or snoozing **cancels** pending reminders and reschedules them. A reminder whose computed time is already past is created as `CANCELLED` with `last_error = 'PAST_DUE_AT_CREATION'` rather than firing instantly. |
| TR-BR-08 | **Escalation ladder**, evaluated every 15 minutes over the partial index: 24 h overdue → level 1, notify the assignee's supervisor; 72 h → level 2, notify the supervisor daily and flag on the dashboard; 168 h → level 3, notify the team's admin. `escalation_level` never decreases while the task is open, and a completed task stops the ladder. |
| TR-BR-09 | Reminder delivery is at-least-once. The `(task_id, remind_at, channel)` unique constraint plus a `SENT` check makes redelivery idempotent; a duplicate in-app badge is acceptable, a duplicate email is suppressed by the same key at the notifier. |
| TR-BR-10 | `KYC_REFRESH` tasks are created automatically by the KYC expiry job (CP-BR-06), assigned to the client's owner, due in 14 days, priority `HIGH`. They cannot be cancelled by a manager — only by a supervisor with a reason, because cancelling one silently drops a compliance obligation. |
| TR-BR-11 | Reassigning a client (CP-US-05) with `transferOpenTasks: true` moves every `OPEN`/`IN_PROGRESS` task to the new owner in the same transaction and appends a `REASSIGNED` history row to each. `DONE` and `CANCELLED` tasks keep their original assignee — history must not be rewritten. |
| TR-BR-12 | Every write emits `task.created`, `task.updated`, `task.completed`, `task.cancelled`, `task.snoozed`, `task.reassigned` or `task.escalated` through the outbox. |
| TR-BR-13 | The dashboard reads from the same PostgreSQL tables as the write path, with a 30-second response cache. No separate read store in v3 — at the stated scale (hundreds of managers, tens of thousands of open tasks) the partial indexes make this a sub-100 ms query, and a second store would add a consistency problem for no measured gain. Revisit only against a real p95 regression. |
| TR-BR-14 | Business-day arithmetic (used by ticket SLA, IL-BR-07) uses the **team's** timezone and holiday calendar, not the server's or the viewer's. Task `due_at` is an absolute instant and does no business-day arithmetic. |

### 7.5 Screens & components

#### S-TR-01 — My Day (manager home, TR-US-02)

Four collapsible groups: **Overdue**, **Today**, **This week**, **Later**.

| State | Behaviour |
|---|---|
| `loading` | Four group headers with count skeletons plus three row skeletons |
| `success` | Rows show title, client name, due-time chip, priority dot, and a one-click complete checkbox |
| `empty` | "Nothing due — you're clear" with an illustration. Deliberately positive, not an error-shaped panel |
| `empty-filtered` | "No tasks match these filters" + **Clear filters** |
| `all-overdue` | Overdue group auto-expands and is pinned; the others collapse |
| `completing` | Optimistic strike-through and fade; row is removed on `200`, restored with a toast on failure |
| `error` | Full-panel error with retry; group counts from the last good response stay visible and are marked `stale` |
| `offline` | Read-only banner; completion is queued and replayed on reconnect, with each queued item badged |

#### S-TR-02 — Task Detail Drawer

| State | Behaviour |
|---|---|
| `loading` | Skeleton; the list behind stays usable |
| `success` | Title, description, client link, source-interaction link, assignee, due date, snooze counter, `task_history` timeline |
| `editable` | Edit affordances appear only for assignee, creator, or supervising supervisor |
| `readonly` | `DONE`/`CANCELLED` → all controls disabled with a "Completed on 09 Sep by Adam Nowak" summary |
| `snooze-exhausted` | Snooze button disabled with "3 of 3 snoozes used — escalate instead" and a supervisor-contact link |
| `orphaned-source` | The source interaction was deleted → "Source interaction was removed" instead of a broken link |
| `conflict` | `409` → field-level diff, same pattern as S-CP-03 |
| `saving` / `error` | Standard |

#### S-TR-03 — Supervisor Dashboard (TR-US-05)

Header KPI row, age-bucket bar, per-manager table, top-overdue list.

| State | Behaviour |
|---|---|
| `loading` | KPI tiles as skeletons; layout reserved so nothing shifts on load |
| `success` | Auto-refresh every 60 s; `generatedAt` rendered as "Updated 12 seconds ago" |
| `empty` | "No overdue tasks in your team" — a genuinely good outcome, rendered as a success state in green, not an empty grey panel |
| `partial` | The per-manager table failed while KPIs loaded → the table panel shows its own retry |
| `stale` | Auto-refresh has failed twice → amber "Last updated 3 minutes ago" chip, refresh remains manual until a fetch succeeds |
| `error` | Full-panel error with `requestId` |
| `forbidden` | Manager reaching the URL → explanation of the required `dashboard:team` permission, link back to My Day |
| `drilldown` | Clicking a manager filters the table in place; clicking a task opens the client card in a new tab with the Tasks tab preselected |

#### S-TR-04 — Task Composer (inline in the interaction modal and standalone)

| State | Behaviour |
|---|---|
| `collapsed` | Single "Create follow-up task" toggle inside S-IL-02 |
| `expanded` | Title, due date (quick chips: Tomorrow / +3 days / Next week), priority, assignee (defaults to self) |
| `validation-error` | Past due date is blocked in the picker itself, so `422` is rarely reachable |
| `saving` / `success` / `error` | Standard; on failure inside the interaction flow, **both** the interaction and the task are rolled back (IL-BR-06) and the composer stays open with the input intact |

#### S-TR-05 — Reminder Settings (per task)

| State | Behaviour |
|---|---|
| `default` | 1 h before, in-app only |
| `custom` | Offset picker plus channel checkboxes |
| `past-offset` | Offset earlier than now → inline warning "This reminder would already have passed" and the channel is not scheduled |
| `delivery-failed` | Reminder row shows "Email failed — retrying", with the last error on hover for admins |

### 7.6 Edge cases

| ID | Case | Expected behaviour |
|---|---|---|
| TR-EC-01 | Task due at 17:00; manager completes it at 17:00:30 | Late by 30 s. `completed_at > original_due_at` makes it "completed late", but it never appeared as overdue on any dashboard because the sweep runs every 15 min. Metrics use exact timestamps; alerting uses the sweep. The gap is documented, not hidden |
| TR-EC-02 | Assignee goes on leave with 40 open tasks | Bulk reassign at the team level: `POST /tasks/bulk-reassign` with a filter, capped at 200 per call, transactional per task with a partial-success report |
| TR-EC-03 | Client is soft-deleted while tasks are open | `RESTRICT` blocks it (CP-BR-09). Open tasks must be completed or cancelled first |
| TR-EC-04 | Task created with `dueAt` two years out | Allowed up to exactly 2 years; beyond that `422`. Reminders are scheduled but the reminder table row is created lazily, 30 days before firing, to keep the scheduled set small |
| TR-EC-05 | Daylight-saving shift between creation and due date | `TIMESTAMPTZ` stores an absolute instant, so the reminder fires at the intended real moment. The UI renders in the viewer's current offset; a task created "at 15:00 CET" displays as 15:00 CEST after the shift, which is the behaviour users expect from a wall-clock commitment |
| TR-EC-06 | Two supervisors reassign the same task simultaneously | `If-Match` → the second gets `409` with the current assignee shown, and can retry deliberately |
| TR-EC-07 | Escalation sweep runs while a manager is completing the task | Both operations touch the row; last writer wins on `escalation_level`, but the completion transaction sets `status = 'DONE'`, and the sweep's `WHERE status IN ('OPEN','IN_PROGRESS')` predicate excludes it. A `DONE` task can never be escalated |
| TR-EC-08 | Email reminder bounces | `attempts` increments with exponential backoff up to 5; then `FAILED` with `last_error`. The in-app reminder is unaffected — channels fail independently |
| TR-EC-09 | Snooze pushes past `original_due_at + 30 days` | `422`, with the maximum allowed date in `details` so the picker can clamp |
| TR-EC-10 | Manager tries to cancel an auto-created `KYC_REFRESH` task | `403` per TR-BR-10, with a message explaining that a supervisor must do it and why |
| TR-EC-11 | Dashboard requested for a team with zero managers | `200` with zeroed totals and an empty `byManager`; the UI renders `empty` with "No managers assigned to this team yet" |
| TR-EC-12 | 5 000 overdue tasks across a large team | `topOverdue` caps at 50; `totals` and `ageBuckets` are full aggregate counts. The UI states "Showing 50 of 5 000 — filter to narrow" rather than silently truncating |
| TR-EC-13 | Task completed, then reopened, then completed again | `completed_at` is overwritten on the second completion; `task_history` holds both `STATUS_CHANGED` rows, so the round trip stays visible where it matters |
| TR-EC-14 | Reminder fires for a task cancelled one minute earlier | The notifier re-checks task status immediately before sending and skips it, marking the reminder `CANCELLED`. Scheduling alone is never authorization to send |
| TR-EC-15 | Clock skew between the API and the sweeper container | Both use `now()` from PostgreSQL, never the JVM clock, so overdue is evaluated against exactly one clock system-wide |
| TR-EC-16 | Supervisor completes a manager's task | Allowed; `completed_by` records the supervisor while `assignee_id` stays unchanged, so on-time-completion metrics remain attributed to the assignee and the intervention is still visible in history |

---

## 8. Module 4 — Audit Trail

**Service:** `audit-service` · **Phase:** v2 · **Prefix:** `AT`

An append-only, tamper-evident record of every mutation and every PII disclosure. `audit-service` is a **consumer only** — it has no write API. Nothing in the system can create an audit entry by calling an endpoint, which is what makes the log trustworthy.

### 8.1 User stories

| ID | As a… | I want to… | So that… | Acceptance criteria |
|---|---|---|---|---|
| AT-US-01 | Compliance officer | see every change made to a given client, with actor and timestamp | I can answer a regulator's "who touched this record" in minutes | Client-scoped audit view, reverse-chronological, keyset-paginated, filterable by action and date; covers all services |
| AT-US-02 | Compliance officer | see everything a specific employee did in a date range | I can investigate a suspected data-misuse incident | Actor-scoped view across all entities including reads of sensitive data and denied permission attempts |
| AT-US-03 | Admin | prove the log has not been altered | the audit trail is evidence, not just a table someone could edit | Hash-chain verification endpoint reports the verified range, the chain head, and the exact sequence number of any break |
| AT-US-04 | Compliance officer | export a filtered slice of the audit log to CSV | I can attach it to a regulatory response | Asynchronous export job with progress; signed download link valid 15 min; the export itself is audited |
| AT-US-05 | Supervisor | see who viewed a client's sensitive data | I can spot access that has no business justification | `READ_SENSITIVE` events are first-class and filterable, including break-glass accesses with their stated reason |
| AT-US-06 | Admin | be alerted when audit ingestion falls behind | a silent consumer failure does not create a compliance gap | Consumer lag is exposed as a metric with an alert above 60 s, and a health endpoint reports last-processed offsets per partition |

### 8.2 Data model

#### 8.2.1 Enumerated types

```sql
CREATE TYPE audit_action AS ENUM (
    'CREATE','UPDATE','DELETE','MERGE',
    'READ_SENSITIVE','EXPORT',
    'LOGIN_SUCCESS','LOGIN_FAILURE','LOGOUT','TOKEN_REFRESH',
    'PERMISSION_DENIED','ROLE_CHANGE','ACCESS_GRANT','ACCESS_REVOKE');
CREATE TYPE export_status AS ENUM ('QUEUED','RUNNING','COMPLETED','FAILED','EXPIRED');
```

#### 8.2.2 `audit_log` (partitioned, append-only)

```sql
CREATE TABLE audit_log (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    event_id      UUID         NOT NULL,             -- producer-assigned; idempotency key
    chain_id      SMALLINT     NOT NULL,             -- = Kafka partition; one hash chain per partition
    chain_seq     BIGINT       NOT NULL,             -- monotonic within (chain_id, partition)
    occurred_at   TIMESTAMPTZ  NOT NULL,             -- when the change happened (producer clock)
    recorded_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- when audit-service persisted it
    -- actor: denormalized, never an FK (audit-service owns no user table)
    actor_id      UUID,
    actor_email   VARCHAR(255),
    actor_role    VARCHAR(32),
    actor_ip      INET,
    user_agent    VARCHAR(512),
    -- subject
    service       VARCHAR(32)  NOT NULL,
    entity_type   VARCHAR(48)  NOT NULL,
    entity_id     UUID         NOT NULL,
    client_id     UUID,                              -- denormalized for AT-US-01
    action        audit_action NOT NULL,
    changed_fields JSONB,                            -- sensitive values already masked (AR-01)
    context       JSONB,                             -- reason, break-glass grant id, endpoint, http status
    -- tracing
    request_id    UUID,
    correlation_id UUID,
    kafka_offset  BIGINT       NOT NULL,
    -- tamper evidence
    prev_hash     BYTEA,
    row_hash      BYTEA        NOT NULL,

    PRIMARY KEY (occurred_at, id),
    CONSTRAINT ck_audit_chain_seq CHECK (chain_seq >= 0),
    CONSTRAINT ck_audit_recorded_after CHECK (recorded_at >= occurred_at - INTERVAL '1 hour'),
    CONSTRAINT ck_audit_actor_present CHECK (
        actor_id IS NOT NULL OR action IN ('LOGIN_FAILURE')   -- failed login has no authenticated actor
    )
) PARTITION BY RANGE (occurred_at);

-- Idempotency. A unique index on a partitioned table must include the partition key;
-- producers always supply occurred_at, so the redelivery lookup is exact.
CREATE UNIQUE INDEX ux_audit_event ON audit_log (occurred_at, event_id);
CREATE UNIQUE INDEX ux_audit_chain ON audit_log (occurred_at, chain_id, chain_seq);

CREATE INDEX ix_audit_client  ON audit_log (client_id, occurred_at DESC) WHERE client_id IS NOT NULL;
CREATE INDEX ix_audit_actor   ON audit_log (actor_id, occurred_at DESC);
CREATE INDEX ix_audit_entity  ON audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_action  ON audit_log (action, occurred_at DESC);
CREATE INDEX ix_audit_changed ON audit_log USING gin (changed_fields jsonb_path_ops);
```

**Partitioning.** Monthly range partitions on `occurred_at`, created three months ahead by a scheduled job. At 7-year retention (banking record-keeping) this is ~84 live partitions; partitions older than 24 months are moved to slower storage, and expired ones are detached and archived rather than dropped.

```sql
CREATE TABLE audit_log_2026_09 PARTITION OF audit_log
    FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');
```

#### 8.2.3 Append-only enforcement

Two independent layers, because a single one is a single point of failure:

```sql
-- 1. Privilege: the application role can only insert and select.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM client360_app;
GRANT  INSERT, SELECT            ON audit_log TO   client360_app;

-- 2. Trigger: blocks even a superuser mistake and leaves a clear error.
CREATE OR REPLACE FUNCTION audit_log_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (attempted % on %)', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE OR DELETE OR TRUNCATE
    ON audit_log FOR EACH STATEMENT EXECUTE FUNCTION audit_log_immutable();
```

The trigger must be re-applied to each new partition; the partition-creation job does this as part of the same transaction that creates the partition.

#### 8.2.4 Hash chain

```
row_hash = SHA256(
    prev_hash                 -- 32 bytes, or 32 zero bytes for chain_seq = 0
 || event_id                  -- 16 bytes
 || to_char(occurred_at, 'YYYY-MM-DD"T"HH24:MI:SS.USOF')
 || coalesce(actor_id::text, '')
 || entity_type || entity_id::text
 || action::text
 || coalesce(changed_fields::text, '')   -- canonical JSON: sorted keys, no whitespace
)
```

**One chain per Kafka partition** (`chain_id`), not one global chain. A global chain would force a single-threaded consumer and make the audit pipeline the throughput ceiling of the whole system. Per-partition chains let six consumers run in parallel while each chain stays strictly ordered and independently verifiable. The chain head of every partition is written to a separate append-only `audit_chain_head` table every 1 000 rows and mirrored to an external WORM store nightly, so an attacker who rewrites a partition and recomputes its chain still cannot match the externally held head.

```sql
CREATE TABLE audit_chain_head (
    chain_id   SMALLINT    NOT NULL,
    chain_seq  BIGINT      NOT NULL,
    head_hash  BYTEA       NOT NULL,
    sealed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chain_id, chain_seq)
);
```

#### 8.2.5 `audit_export_jobs`

```sql
CREATE TABLE audit_export_jobs (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    requested_by  UUID          NOT NULL,
    filter        JSONB         NOT NULL,
    format        VARCHAR(8)    NOT NULL,
    status        export_status NOT NULL DEFAULT 'QUEUED',
    row_count     BIGINT,
    storage_key   VARCHAR(512),
    checksum_sha256 BYTEA,
    error_message TEXT,
    requested_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_export_format CHECK (format IN ('CSV','JSONL')),
    CONSTRAINT ck_export_completed CHECK (
        (status = 'COMPLETED') = (storage_key IS NOT NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_export_failed CHECK (status <> 'FAILED' OR error_message IS NOT NULL),
    CONSTRAINT ck_export_expiry CHECK (expires_at > requested_at)
);
```

#### 8.2.6 `audit_consumer_state`

```sql
CREATE TABLE audit_consumer_state (
    topic            VARCHAR(64) NOT NULL,
    partition_no     SMALLINT    NOT NULL,
    last_offset      BIGINT      NOT NULL,
    last_event_at    TIMESTAMPTZ NOT NULL,
    last_processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lag_seconds      INTEGER     GENERATED ALWAYS AS (0) STORED,  -- placeholder; computed at read time
    PRIMARY KEY (topic, partition_no)
);
```

Lag is reported by the health endpoint as `now() - last_event_at`, computed on read; the stored column exists only to keep the row shape stable for tooling.

### 8.3 API endpoints

`audit-service` exposes **reads only**. There is no `POST /audit`.

---

#### `GET /audit` — search the log (AT-US-01, AT-US-02, AT-US-05)

**Permission:** `audit:read` — auditor and admin (scope `ALL`), supervisor (scope `TEAM`, clients on their team only)
**Query:** `clientId`, `actorId`, `entityType`, `entityId`, `action` (repeatable), `service`, `from`, `to`, `cursor`, `limit` (default 50, max 200)

**`200 OK`**

```json
{
  "content": [
    {
      "id": "a001…",
      "occurredAt": "2026-09-09T11:42:07.113Z",
      "recordedAt": "2026-09-09T11:42:07.918Z",
      "lagMs": 805,
      "actor": { "id": "3f1c…", "email": "a.nowak@bank.example", "role": "MANAGER", "ip": "10.4.11.87" },
      "service": "client-service",
      "entity": { "type": "CLIENT", "id": "9b21…" },
      "clientId": "9b21…",
      "action": "UPDATE",
      "changedFields": {
        "segment": { "old": "RETAIL", "new": "PREMIUM" },
        "email":   { "old": "***MASKED***", "new": "***MASKED***" }
      },
      "context": { "endpoint": "PATCH /api/v1/clients/9b21…", "httpStatus": 200 },
      "requestId": "c4d0…",
      "chainId": 3, "chainSeq": 184291
    }
  ],
  "nextCursor": "eyJ0cyI6…",
  "hasMore": true
}
```

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | No filter at all — an unbounded scan of 7 years is refused; at least one of `clientId`, `actorId`, `entityId`, or a `from`/`to` range under 90 days is required |
| `400` | `INVALID_CURSOR` | Malformed or expired |
| `403` | `ROLE_REQUIRED` | Manager role — managers have no audit access whatsoever |
| `403` | `SCOPE_VIOLATION` | Supervisor querying a client outside their team, or any `actorId` other than their own team members |

Every call to `GET /audit` is itself audited as `READ_SENSITIVE` on `entity_type = 'AUDIT_LOG'`. Watching the watchers is not optional in a banking context.

---

#### `GET /audit/{occurredAt}/{id}` — single entry

Composite key because the table is partitioned. **`200 OK`** — the full entry plus `prevHash` and `rowHash` in hex, and `chainVerified: true|false` from an on-the-fly recomputation of that single row against its predecessor.

| Status | Code | Cause |
|---|---|---|
| `404` | `AUDIT_ENTRY_NOT_FOUND` | Unknown, or in a detached archive partition — the message says which |

---

#### `GET /clients/{clientId}/audit` — client-scoped view (AT-US-01)

Convenience alias for `GET /audit?clientId=…`, additionally permitted to **supervisors for their own team's clients**. This is the endpoint behind the client card's Audit tab.

---

#### `POST /audit/verify` — hash-chain integrity (AT-US-03)

**Permission:** `audit:verify` (admin only)

```json
{ "chainId": 3, "fromSeq": 180000, "toSeq": 185000 }
```

Or by time: `{ "from": "2026-09-01T00:00:00Z", "to": "2026-09-09T00:00:00Z" }` — verifies all chains in the range.

**`200 OK`** — verification succeeded:

```json
{
  "verified": true,
  "chains": [ { "chainId": 3, "fromSeq": 180000, "toSeq": 185000, "rowsChecked": 5001, "headHash": "9f2a…" } ],
  "rowsChecked": 5001,
  "durationMs": 4120,
  "externalHeadMatch": true
}
```

**`200 OK`** — verification **failed** (still `200`; the request succeeded, the data did not):

```json
{
  "verified": false,
  "breaks": [
    { "chainId": 3, "chainSeq": 184291, "occurredAt": "2026-09-09T11:42:07.113Z",
      "expectedHash": "9f2a…", "actualHash": "1c88…",
      "diagnosis": "ROW_MODIFIED_OR_DELETED" }
  ],
  "rowsChecked": 5001,
  "externalHeadMatch": false
}
```

A break fires a `CRITICAL` alert to the security channel regardless of who called the endpoint.

| Status | Code | Cause |
|---|---|---|
| `403` | `ROLE_REQUIRED` | Not an admin |
| `422` | `BUSINESS_RULE_VIOLATED` | Range over 1 000 000 rows — use the async export path instead |

---

#### `POST /audit/exports` — request an export (AT-US-04)

**Permission:** `audit:export` (auditor, admin) · **Headers:** `Idempotency-Key`

```json
{
  "filter": { "clientId": "9b21…", "from": "2024-01-01T00:00:00Z", "to": "2026-09-09T00:00:00Z" },
  "format": "CSV",
  "reason": "Regulatory request REF-2026-0918 from the national supervisor"
}
```

**`202 Accepted`** · `Location: /api/v1/audit/exports/{jobId}`

```json
{ "jobId": "x100…", "status": "QUEUED", "estimatedRows": 1842, "expiresAt": "2026-09-16T11:42:07.113Z" }
```

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | `reason` under 20 chars — an export without a stated purpose is not auditable |
| `422` | `BUSINESS_RULE_VIOLATED` | Estimated rows over 5 000 000; range over 7 years |
| `429` | `RATE_LIMIT_EXCEEDED` | Over 5 exports/hour |

##### `GET /audit/exports/{jobId}`

**`200 OK`** — `{ "jobId", "status", "progressPercent", "rowCount", "downloadUrl", "checksumSha256", "expiresAt" }`. `downloadUrl` is a 15-minute pre-signed link, present only while `status = COMPLETED`.

| Status | Code | Cause |
|---|---|---|
| `403` | `PERMISSION_DENIED` | Not the requester and not an admin — exports are not shared by URL |
| `404` | `EXPORT_JOB_NOT_FOUND` | Unknown or purged |
| `410` | `EXPORT_EXPIRED` | Past `expires_at`; the file is deleted and the job must be re-requested |

Downloading an export emits an `EXPORT` audit event carrying the `jobId`, row count and checksum.

---

#### `GET /audit/health` — ingestion health (AT-US-06)

**Permission:** `audit:admin`, plus the monitoring service account

**`200 OK`**

```json
{
  "status": "DEGRADED",
  "partitions": [
    { "topic": "client.events", "partition": 0, "lastOffset": 918273, "lagSeconds": 2 },
    { "topic": "client.events", "partition": 1, "lastOffset": 918100, "lagSeconds": 187 }
  ],
  "maxLagSeconds": 187,
  "dltDepth": 3,
  "oldestUnprocessedAt": "2026-09-09T11:39:00.000Z"
}
```

`status` is `HEALTHY` below 60 s max lag with an empty DLT, `DEGRADED` up to 300 s or a non-empty DLT, `UNHEALTHY` beyond that. `503` is returned for `UNHEALTHY` so orchestrators react.

### 8.4 Business logic rules

| ID | Rule |
|---|---|
| AT-BR-01 | The audit log is **append-only**. No API, service or application role can update or delete a row. Enforced by revoked privileges *and* a statement-level trigger on every partition. |
| AT-BR-02 | Audit entries are created **only** by consuming Kafka. `audit-service` has no ingestion endpoint, so a compromised application service cannot forge an entry without also compromising the broker. |
| AT-BR-03 | Ingestion is idempotent. Redelivery of the same `event_id` at the same `occurred_at` is absorbed by `ux_audit_event`; the consumer catches the unique violation, commits the offset and continues. At-least-once delivery therefore yields exactly-once storage. |
| AT-BR-04 | Sensitive values are **never** stored, even encrypted. `changed_fields` records that a field changed, not its value (AR-01). The audit trail proves accountability; it is not a second copy of the customer database, and it must not become a new place to breach. |
| AT-BR-05 | Every row is chained: `row_hash` covers `prev_hash`. Modifying or deleting any row breaks every subsequent hash in its chain, and `POST /audit/verify` reports the exact `chain_seq` of the break. |
| AT-BR-06 | Chain heads are sealed every 1 000 rows into `audit_chain_head` and mirrored nightly to external WORM storage. Local recomputation alone cannot defeat detection, because the external head is outside the database an attacker would have compromised. |
| AT-BR-07 | `occurred_at` comes from the producer, `recorded_at` from the consumer. Both are stored. Their difference is the pipeline lag, exposed per row as `lagMs`, so a delayed audit is visible as delayed rather than silently back-dated. |
| AT-BR-08 | Events that fail processing after 5 attempts go to the DLT and raise an alert. **The consumer does not skip them** — the offset is not committed past an unprocessable event without an operator decision, because a silently dropped audit event is exactly the failure this module exists to prevent. |
| AT-BR-09 | Retention is 7 years, aligned to banking record-keeping. Expired partitions are **detached and archived**, never `DROP`ped in place, and the archival operation is itself audited. |
| AT-BR-10 | Reading the audit log is audited. `GET /audit`, `GET /audit/exports/{id}` downloads, and verification runs all emit their own entries. |
| AT-BR-11 | Managers have no audit access at all. Supervisors are limited to `TEAM` scope on clients and to their own team's actors. Auditors get read-only `ALL`. Admins get `ALL` plus verification, but **not** the ability to delete — no role in the system can delete an audit row. |
| AT-BR-12 | `PERMISSION_DENIED` events are audited with the full attempted request. A failed access attempt is more interesting to an investigator than a successful one, so it must never be dropped as noise. |
| AT-BR-13 | Break-glass reads carry the `access_grant_id` and the stated reason in `context`, so a `READ_SENSITIVE` outside normal scope is self-explaining in the log without a join into another service. |
| AT-BR-14 | Clock skew between producers is bounded by NTP. `ck_audit_recorded_after` tolerates one hour of back-dating; an event arriving further out of order is written to the DLT and alerted rather than accepted, since it indicates either a broken clock or forgery. |

### 8.5 Screens & components

#### S-AT-01 — Audit Log Viewer (compliance workspace)

Filter rail (date range, action, actor, entity, client) beside a virtualized table.

| State | Behaviour |
|---|---|
| `initial` | No results shown; the filter rail is focused with "Select a client, actor or date range to begin" — this mirrors AT-BR/API rule that an unfiltered query is refused, so the UI never invites an impossible request |
| `loading` | Ten skeleton rows; the filter rail stays interactive |
| `success` | Columns: time, actor, action badge, entity, summary of changed fields, request ID. Row expands to the full JSON diff |
| `loading-more` | Keyset page appended at the foot |
| `empty` | "No audit entries match these filters" + **Widen date range**, with the current range echoed |
| `error` | Error card with `requestId`; the filters are preserved |
| `forbidden` | Supervisor querying outside their team → inline "You can only view audit entries for clients on your team" |
| `export-running` | Sticky footer bar with a progress percentage and a cancel action |
| `export-ready` | Footer becomes a download button plus the file checksum |

**Row expansion states:** `masked` (sensitive fields shown as `***MASKED***` with a tooltip explaining AR-01 — so a compliance officer does not read it as data loss), `structural` (create/delete with no diff), `denied` (red, showing the attempted endpoint and the reason).

#### S-AT-02 — Client Audit Tab (on the client card)

| State | Behaviour |
|---|---|
| `loading` | Five skeleton rows inside the tab |
| `success` | Compact timeline: "Adam Nowak changed segment from Retail to Premium — 9 Sep, 11:42" |
| `empty` | "No changes recorded for this client yet" |
| `forbidden` | The tab is hidden entirely for managers, rather than shown disabled — a locked tab advertises the existence of something they may not know about |
| `partial` | Audit service unreachable → "Audit history is temporarily unavailable" inside the tab only; the rest of the card is unaffected |

#### S-AT-03 — Chain Verification (admin)

| State | Behaviour |
|---|---|
| `idle` | Range picker plus per-chain last-verified timestamps |
| `running` | Progress bar with rows-checked counter; long runs continue server-side if the tab is closed |
| `verified` | Green panel: rows checked, chain heads, external-head match confirmation |
| `break-detected` | Red panel naming the exact `chainId`/`chainSeq`, the expected and actual hashes, the surrounding rows, and an **Export evidence** action. Deliberately alarming — this is a security incident |
| `partial-verify` | Some chains verified, one unreachable → per-chain status list rather than a single verdict |
| `error` | Verification could not run (DB timeout); explicitly distinguished from "verification failed", because conflating the two would be dangerous |

#### S-AT-04 — Export Wizard

| State | Behaviour |
|---|---|
| `configuring` | Filter summary plus a live estimated row count and file size |
| `reason-required` | Submit stays disabled until the reason reaches 20 characters, with a live counter |
| `queued` / `running` | Job card with progress; the user may leave the page |
| `completed` | Download button, row count, SHA-256 checksum, expiry countdown |
| `expired` | "This export expired on 16 Sep" + **Request again** with the filters pre-filled |
| `failed` | Error message plus retry; the failure is itself in the audit log |
| `too-large` | `422` → "This export would contain 8.2 M rows. Narrow the date range." with a suggested split |

#### S-AT-05 — Ingestion Health (admin)

| State | Behaviour |
|---|---|
| `healthy` | Green, per-partition lag sparklines |
| `degraded` | Amber banner naming the lagging partitions and the current max lag |
| `unhealthy` | Red banner, DLT depth, **Inspect DLT** action, and an explicit statement that audit coverage may currently be incomplete |
| `loading` / `error` | Standard; an error here escalates to the on-call alert rather than staying only on screen |

### 8.6 Edge cases

| ID | Case | Expected behaviour |
|---|---|---|
| AT-EC-01 | Kafka redelivers 10 000 events after a consumer restart | All are absorbed by `ux_audit_event`. Duplicate-rate is exposed as a metric; a sustained high rate points at an offset-commit bug |
| AT-EC-02 | Two consumer instances process the same partition during a rebalance | Kafka's partition assignment makes this transient; the unique constraint on `(occurred_at, chain_id, chain_seq)` makes it harmless. The loser rolls back and re-reads the chain head |
| AT-EC-03 | Chain head is needed but the previous row is in a detached archive partition | The chain head per `chain_id` is cached in `audit_chain_head`, which is never detached, so ingestion never depends on reading an archived partition |
| AT-EC-04 | An event arrives with `occurred_at` two hours in the past after a producer outage | Accepted only within the 1-hour tolerance; beyond it, the row goes to the DLT with `CLOCK_SKEW_SUSPECTED` and alerts. Backfill after a long outage runs through an operator-approved procedure that creates rows with an explicit `context.backfill: true` |
| AT-EC-05 | Audit service is down for 4 hours | The main API keeps serving — audit writes are asynchronous by design. Outbox rows accumulate, Kafka retains 7 days, and the consumer catches up on restart. Lag alerts fire throughout, and `GET /audit/health` reports the true gap. **No mutation is blocked**, which is the deliberate trade-off `PROJECT_IDEA.md` §5 makes by putting audit behind Kafka |
| AT-EC-06 | The outbox relay dies with 50 000 unpublished rows | The partial index on `published_at IS NULL` keeps the recovery query fast. Events publish in `created_at` order; ordering within a chain is by arrival, so the chain stays valid regardless of the backlog |
| AT-EC-07 | A DBA modifies a row directly with superuser rights | The trigger blocks it. If the trigger is dropped first, the chain breaks and `POST /audit/verify` reports the exact `chain_seq`; the external WORM head makes a full rewrite detectable too |
| AT-EC-08 | A client is GDPR-erased but audit rows reference `client_id` | Audit rows are untouched — they hold no PII (AR-01), only a UUID. The erasure is itself audited. This is precisely why AR-01 exists: it makes GDPR erasure and 7-year audit retention compatible instead of contradictory |
| AT-EC-09 | Export requested for 3 M rows | Runs in the async job with server-side streaming and a 500-row fetch size; the file is gzipped and written straight to object storage. The API never materializes it in memory |
| AT-EC-10 | Export download link is shared with a colleague | The pre-signed URL works for 15 minutes for anyone holding it — accepted, and mitigated by the short TTL, the audited `EXPORT` event, and the requester's stated reason being on record |
| AT-EC-11 | `changed_fields` for a bulk reassignment of 200 clients | One audit event **per client**, not one for the batch, with a shared `correlation_id`. Per-entity granularity is what makes "who touched this record" answerable |
| AT-EC-12 | Verification requested over 7 years | `422` above 1 000 000 rows. Verification is chunked per month by the scheduled nightly job, which stores per-partition results so an ad-hoc full verify is never needed |
| AT-EC-13 | A partition for next month does not exist when an event arrives | The partition job runs 3 months ahead. As a safety net, the consumer catches the "no partition found" error, creates the partition with its trigger in a separate transaction, and retries once |
| AT-EC-14 | An auditor searches with no filters | `400 VALIDATION_FAILED` naming the minimum filter set. The UI's `initial` state prevents the request being made at all |
| AT-EC-15 | `PERMISSION_DENIED` audit write fails because the audit service is down | The denial is also written to the application log with the same `correlation_id`, and a reconciliation job replays application-log denials that never reached the audit trail. Denials are the one event class with a second path |
| AT-EC-16 | Supervisor tries to audit their own actions to check what is recorded | Allowed — self-audit is within `TEAM` scope, and the read is itself audited. Transparency about what is logged is a feature; the log is tamper-evident regardless of who reads it |

---

## 9. Module 5 — RBAC

**Service:** `client-service` · **Phase:** v2 · **Prefix:** `RB`

Roles, data scopes, sessions and break-glass access. Every other module's `403`/`404` decision is made here.

### 9.1 User stories

| ID | As a… | I want to… | So that… | Acceptance criteria |
|---|---|---|---|---|
| RB-US-01 | Manager | see and edit only the clients assigned to me | I cannot accidentally act on a colleague's customer, and my access footprint is small | `OWN` scope filters every list and single-record read; out-of-scope IDs return `404` per ER-01 |
| RB-US-02 | Supervisor | see everything my team's managers can see | I can cover, review and coach without a second login | `TEAM` scope resolves through `team_members`; scope changes take effect within one token lifetime (15 min) or immediately on refresh |
| RB-US-03 | Admin | assign and revoke roles | joiners, movers and leavers are handled the same way every time | Role changes are immediate on next token refresh, require a reason, and emit a `ROLE_CHANGE` audit event |
| RB-US-04 | Admin | deactivate a departing employee | their access ends the same day and their work is not orphaned | Deactivation revokes all sessions within 60 s and refuses to complete until owned clients and open tasks are reassigned |
| RB-US-05 | Manager | request temporary access to a client outside my scope | I can cover an urgent call for an absent colleague without an admin ticket | Break-glass grant with a mandatory reason, max 8 h, supervisor-approved; every read under it is audited with the grant ID |
| RB-US-06 | Security officer | see failed logins and denied permission attempts | I can detect credential stuffing and probing early | Login attempts are recorded with IP and user agent; 5 failures lock the account for 15 min; every denial reaches the audit trail |
| RB-US-07 | Any user | stay signed in across a working day without re-entering my password every 15 minutes | security controls do not push people toward workarounds | 15-minute access token, 8-hour refresh token with rotation and reuse detection; idle timeout 60 min |
| RB-US-08 | Admin | see the effective permission matrix for a role | I can answer "what can a supervisor actually do?" without reading code | `GET /permissions/matrix` returns roles × permissions × scopes, generated from the same data the enforcement layer reads |

### 9.2 Data model

#### 9.2.1 Enumerated types

```sql
CREATE TYPE user_status  AS ENUM ('ACTIVE','SUSPENDED','DEACTIVATED');
CREATE TYPE access_scope AS ENUM ('OWN','TEAM','ALL');
```

#### 9.2.2 `teams`

```sql
CREATE TABLE teams (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(120) NOT NULL,
    code           VARCHAR(32)  NOT NULL,
    supervisor_id  UUID,                       -- FK added after users (circular reference)
    parent_team_id UUID,
    timezone       VARCHAR(64)  NOT NULL DEFAULT 'Europe/Warsaw',
    active         BOOLEAN      NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT uq_teams_code UNIQUE (code),
    CONSTRAINT fk_teams_parent FOREIGN KEY (parent_team_id) REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT ck_teams_no_self_parent CHECK (parent_team_id IS NULL OR parent_team_id <> id)
);
```

#### 9.2.3 `users`

```sql
CREATE TABLE users (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_no         VARCHAR(32)  NOT NULL,
    email               VARCHAR(255) NOT NULL,
    full_name           VARCHAR(200) NOT NULL,
    password_hash       VARCHAR(255) NOT NULL,     -- bcrypt, cost 12
    status              user_status  NOT NULL DEFAULT 'ACTIVE',
    primary_team_id     UUID,
    failed_login_count  SMALLINT     NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    must_change_password BOOLEAN     NOT NULL DEFAULT false,
    last_login_at       TIMESTAMPTZ,
    deactivated_at      TIMESTAMPTZ,
    deactivated_by      UUID,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT uq_users_employee_no UNIQUE (employee_no),
    CONSTRAINT uq_users_email       UNIQUE (email),
    CONSTRAINT fk_users_team        FOREIGN KEY (primary_team_id) REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT ck_users_failed_count CHECK (failed_login_count BETWEEN 0 AND 100),
    CONSTRAINT ck_users_email_shape  CHECK (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    CONSTRAINT ck_users_deactivated_pair
        CHECK ((status = 'DEACTIVATED') = (deactivated_at IS NOT NULL))
);

-- deferred: circular FK between teams and users
ALTER TABLE teams ADD CONSTRAINT fk_teams_supervisor
    FOREIGN KEY (supervisor_id) REFERENCES users(id) ON DELETE RESTRICT;

CREATE INDEX ix_users_team   ON users (primary_team_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_users_status ON users (status);
```

User email is **not** encrypted, unlike client email: it is corporate directory data, not customer PII, and it is needed in plaintext for login, notification routing and the audit log's `actor_email`.

#### 9.2.4 `team_members`

A user's primary team is on `users`; this table carries additional memberships (a supervisor covering two branches).

```sql
CREATE TABLE team_members (
    team_id    UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    is_primary BOOLEAN     NOT NULL DEFAULT false,
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at    TIMESTAMPTZ,

    PRIMARY KEY (team_id, user_id),
    CONSTRAINT fk_tm_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_tm_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_tm_left_after CHECK (left_at IS NULL OR left_at > joined_at)
);

CREATE UNIQUE INDEX ux_tm_one_primary ON team_members (user_id)
    WHERE is_primary AND left_at IS NULL;
CREATE INDEX ix_tm_active ON team_members (user_id) WHERE left_at IS NULL;
```

#### 9.2.5 `roles`, `permissions`, `role_permissions`, `user_roles`

```sql
CREATE TABLE roles (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(32)  NOT NULL UNIQUE,   -- MANAGER, SUPERVISOR, ADMIN, AUDITOR, COMPLIANCE
    name        VARCHAR(80)  NOT NULL,
    description TEXT,
    is_system   BOOLEAN      NOT NULL DEFAULT false,  -- system roles cannot be deleted
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE permissions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(64) NOT NULL UNIQUE,    -- 'client:read', 'task:reassign', …
    resource    VARCHAR(32) NOT NULL,
    action      VARCHAR(32) NOT NULL,
    description TEXT,

    CONSTRAINT uq_permissions_pair UNIQUE (resource, action),
    CONSTRAINT ck_permissions_code_shape CHECK (code = resource || ':' || action)
);

CREATE TABLE role_permissions (
    role_id       UUID         NOT NULL,
    permission_id UUID         NOT NULL,
    scope         access_scope NOT NULL DEFAULT 'OWN',

    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id)       REFERENCES roles(id)       ON DELETE CASCADE,
    CONSTRAINT fk_rp_perm FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE RESTRICT
);

CREATE TABLE user_roles (
    user_id    UUID        NOT NULL,
    role_id    UUID        NOT NULL,
    granted_by UUID        NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,                    -- NULL = permanent
    reason     TEXT        NOT NULL,

    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_ur_user    FOREIGN KEY (user_id)    REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_role    FOREIGN KEY (role_id)    REFERENCES roles(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ur_granter FOREIGN KEY (granted_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ur_expiry  CHECK (expires_at IS NULL OR expires_at > granted_at),
    CONSTRAINT ck_ur_reason  CHECK (length(btrim(reason)) >= 10)
);

-- No partial index here: an index predicate must be IMMUTABLE, and now() is not.
-- Expiry is filtered at query time; the index covers the lookup.
CREATE INDEX ix_user_roles_user ON user_roles (user_id, expires_at);
```

#### 9.2.6 `access_grants` — break-glass (RB-US-05)

```sql
CREATE TABLE access_grants (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL,
    client_id    UUID        NOT NULL,
    reason       TEXT        NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by  UUID,
    approved_at  TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    revoked_by   UUID,
    use_count    INTEGER     NOT NULL DEFAULT 0,

    CONSTRAINT fk_ag_user     FOREIGN KEY (user_id)     REFERENCES users(id)   ON DELETE CASCADE,
    CONSTRAINT fk_ag_client   FOREIGN KEY (client_id)   REFERENCES clients(id) ON DELETE CASCADE,
    CONSTRAINT fk_ag_approver FOREIGN KEY (approved_by) REFERENCES users(id)   ON DELETE RESTRICT,
    CONSTRAINT ck_ag_reason   CHECK (length(btrim(reason)) >= 20),
    CONSTRAINT ck_ag_window   CHECK (expires_at > requested_at
                                     AND expires_at <= requested_at + INTERVAL '8 hours'),
    CONSTRAINT ck_ag_approved_pair CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CONSTRAINT ck_ag_use_count CHECK (use_count >= 0)
);

CREATE INDEX ix_ag_active ON access_grants (user_id, client_id)
    WHERE approved_at IS NOT NULL AND revoked_at IS NULL;
```

The 8-hour ceiling is a database constraint, not a service-layer check — a break-glass grant that could be made permanent is not break-glass.

#### 9.2.7 `refresh_tokens` and `login_attempts`

```sql
CREATE TABLE refresh_tokens (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID        NOT NULL,
    token_hash         BYTEA       NOT NULL UNIQUE,   -- SHA-256; the raw token is never stored
    family_id          UUID        NOT NULL,          -- rotation lineage, for reuse detection
    issued_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL,
    used_at            TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    revoked_reason     VARCHAR(64),
    replaced_by        UUID,
    ip                 INET,
    user_agent         VARCHAR(512),

    CONSTRAINT fk_rt_user     FOREIGN KEY (user_id)     REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_rt_replaced FOREIGN KEY (replaced_by) REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    CONSTRAINT ck_rt_expiry   CHECK (expires_at > issued_at)
);

CREATE INDEX ix_rt_user_active ON refresh_tokens (user_id)
    WHERE revoked_at IS NULL AND used_at IS NULL;
CREATE INDEX ix_rt_family ON refresh_tokens (family_id);

CREATE TABLE login_attempts (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email          VARCHAR(255) NOT NULL,
    user_id        UUID,                       -- NULL when the email is unknown
    success        BOOLEAN     NOT NULL,
    failure_reason VARCHAR(48),
    ip             INET        NOT NULL,
    user_agent     VARCHAR(512),
    attempted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_la_reason CHECK (success = (failure_reason IS NULL))
);

CREATE INDEX ix_la_email_time ON login_attempts (email, attempted_at DESC);
CREATE INDEX ix_la_ip_time    ON login_attempts (ip, attempted_at DESC);
```

`login_attempts` is a hot operational table for lockout decisions with 90-day retention; the durable record is the `LOGIN_SUCCESS` / `LOGIN_FAILURE` audit event with 7-year retention.

#### 9.2.8 Permission matrix (seed data)

Scope legend: **OWN** = clients the user owns · **TEAM** = clients owned by the user's team members · **ALL** = every client · **—** = not granted.

| Permission | MANAGER | SUPERVISOR | ADMIN | AUDITOR | COMPLIANCE |
|---|---|---|---|---|---|
| `client:read` | OWN | TEAM | ALL | ALL | ALL |
| `client:write` | OWN | TEAM | ALL | — | — |
| `client:delete` | — | — | ALL | — | — |
| `client:reassign` | — | TEAM | ALL | — | — |
| `client:merge` | — | — | ALL | — | — |
| `client:kyc` | — | TEAM | ALL | — | ALL |
| `client:erase` | — | — | ALL | — | ALL |
| `product:read` | OWN | TEAM | ALL | ALL | ALL |
| `product:write` | — | — | ALL | — | — |
| `product:sync` | OWN | TEAM | ALL | — | — |
| `interaction:read` | OWN | TEAM | ALL | ALL | ALL |
| `interaction:write` | OWN | TEAM | ALL | — | — |
| `interaction:delete` | — | TEAM | ALL | — | — |
| `ticket:read` | OWN | TEAM | ALL | ALL | ALL |
| `ticket:write` | OWN | TEAM | ALL | — | — |
| `task:read` | OWN | TEAM | ALL | ALL | ALL |
| `task:write` | OWN | TEAM | ALL | — | — |
| `task:reassign` | — | TEAM | ALL | — | — |
| `dashboard:team` | — | TEAM | ALL | — | — |
| `audit:read` | — | TEAM | ALL | ALL | ALL |
| `audit:export` | — | — | ALL | ALL | ALL |
| `audit:verify` | — | — | ALL | — | — |
| `user:read` | — | TEAM | ALL | ALL | — |
| `user:write` | — | — | ALL | — | — |
| `role:assign` | — | — | ALL | — | — |
| `team:manage` | — | — | ALL | — | — |
| `access:request` | OWN | TEAM | ALL | — | — |
| `access:approve` | — | TEAM | ALL | — | — |

Three properties this matrix enforces deliberately:

- **No role can delete audit data.** `audit:delete` does not exist as a permission code, anywhere.
- **Admins cannot read client PII casually.** `client:read` at `ALL` scope still emits `READ_SENSITIVE` per access, so an admin browsing customer records is visible in the same log a manager is.
- **Auditors are strictly read-only.** They hold no `:write` permission of any kind, which makes them safe to grant broadly.

#### 9.2.9 Relationship map

```
teams 1───* team_members *───1 users
teams 0..1─1 users              (supervisor_id)
teams 0..1─* teams              (parent_team_id)
users *───* roles               (user_roles)
roles *───* permissions         (role_permissions, carrying scope)
users 1───* refresh_tokens
users *───* clients             (access_grants: time-boxed break-glass)
```

### 9.3 API endpoints

---

#### `POST /auth/login`

**Permission:** public

```json
{ "email": "a.nowak@bank.example", "password": "…" }
```

**`200 OK`**

```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIs…",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshToken": "8f2c…",
  "refreshExpiresIn": 28800,
  "user": {
    "id": "3f1c…", "fullName": "Adam Nowak", "email": "a.nowak@bank.example",
    "roles": ["MANAGER"], "primaryTeam": { "id": "77aa…", "name": "Retail Warsaw North" },
    "mustChangePassword": false
  }
}
```

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | Missing field |
| `401` | `INVALID_CREDENTIALS` | Wrong email **or** wrong password — one code for both, so the endpoint cannot be used to enumerate accounts |
| `403` | `ACCOUNT_LOCKED` | 5 consecutive failures; `details[0].lockedUntil` is returned |
| `403` | `ACCOUNT_DEACTIVATED` | `status = DEACTIVATED` |
| `403` | `PASSWORD_EXPIRED` | Over 90 days old; `details[0].changePasswordUrl` |
| `429` | `RATE_LIMIT_EXCEEDED` | Over 10 attempts/min from one IP |

Response time is constant (≈ 250 ms) whether or not the email exists — an unknown email still performs a dummy bcrypt comparison, so timing does not leak account existence.

---

#### `POST /auth/refresh`

```json
{ "refreshToken": "8f2c…" }
```

**`200 OK`** — a new access token **and a new refresh token** (rotation). The old refresh token is marked `used_at` immediately.

| Status | Code | Cause |
|---|---|---|
| `401` | `REFRESH_TOKEN_INVALID` | Unknown or malformed |
| `401` | `REFRESH_TOKEN_EXPIRED` | Past `expires_at` |
| `401` | `REFRESH_TOKEN_REUSED` | Already-used token presented → **the entire `family_id` is revoked**, all sessions for the user are killed, and a security alert fires. Classic stolen-token detection |
| `403` | `ACCOUNT_DEACTIVATED` | Deactivated since issuance |

---

#### `POST /auth/logout` · `POST /auth/logout-all`

Revokes the presented refresh token, or every token for the user. **`204 No Content`**. Both emit `LOGOUT` audit events.

---

#### `GET /me`

**`200 OK`** — identity, roles, teams, the caller's **effective permissions with scopes**, and any active break-glass grants:

```json
{
  "id": "3f1c…", "fullName": "Adam Nowak", "email": "a.nowak@bank.example",
  "status": "ACTIVE",
  "roles": [{ "code": "MANAGER", "expiresAt": null }],
  "primaryTeam": { "id": "77aa…", "name": "Retail Warsaw North", "timezone": "Europe/Warsaw" },
  "teams": [{ "id": "77aa…", "isPrimary": true }],
  "permissions": [
    { "code": "client:read", "scope": "OWN" },
    { "code": "client:write", "scope": "OWN" },
    { "code": "interaction:write", "scope": "OWN" }
  ],
  "activeGrants": [
    { "id": "g100…", "clientId": "5f0a…", "expiresAt": "2026-09-09T18:00:00.000Z" }
  ],
  "sessionExpiresAt": "2026-09-09T11:57:07.113Z"
}
```

The SPA builds its entire navigation from `permissions`. No role strings are hard-coded in the frontend, so adding a role never requires a frontend release.

---

#### User administration

| Method | Path | Permission | Notes |
|---|---|---|---|
| `GET` | `/users` | `user:read` | `?status=&teamId=&role=&q=`, offset-paginated. Supervisors see their team only |
| `POST` | `/users` | `user:write` | `201`. `409 USER_DUPLICATE_EMAIL` / `USER_DUPLICATE_EMPLOYEE_NO`. Password is set via an emailed one-time link, never in the request |
| `GET` | `/users/{id}` | `user:read` | `404 USER_NOT_FOUND` out of scope |
| `PATCH` | `/users/{id}` | `user:write` | `If-Match`. `422` when changing `primaryTeamId` while the user owns clients in the old team |
| `POST` | `/users/{id}/roles` | `role:assign` | Body `{ "roleCode": "SUPERVISOR", "reason": "…", "expiresAt": null }`. `422 BUSINESS_RULE_VIOLATED` on self-assignment (RB-BR-06) |
| `DELETE` | `/users/{id}/roles/{roleCode}` | `role:assign` | `?reason=`. `422` when removing the last role, or the last remaining admin (RB-BR-07) |
| `POST` | `/users/{id}/deactivate` | `user:write` | Body `{ "reason": "…", "reassignClientsTo": "4a2d…", "reassignTasksTo": "4a2d…" }`. `409 USER_HAS_OWNED_CLIENTS` when reassignment targets are missing; the response lists the counts blocking it |
| `POST` | `/users/{id}/reactivate` | `user:write` | Restores `ACTIVE`, forces a password change, grants no clients back automatically |
| `POST` | `/users/{id}/unlock` | `user:write` | Clears `locked_until` and `failed_login_count` |

---

#### Team administration

| Method | Path | Permission | Notes |
|---|---|---|---|
| `GET` | `/teams` | `user:read` | Includes member counts |
| `POST` | `/teams` | `team:manage` | `409 TEAM_DUPLICATE_CODE` |
| `PATCH` | `/teams/{id}` | `team:manage` | `422` when setting a supervisor who lacks the `SUPERVISOR` role, or when `parentTeamId` would create a cycle |
| `POST` | `/teams/{id}/members` | `team:manage` | `422` when the user's only membership is being removed |
| `DELETE` | `/teams/{id}/members/{userId}` | `team:manage` | Sets `left_at`; `422` while the user owns clients in that team |

---

#### Break-glass access (RB-US-05)

##### `POST /access-grants`

**Permission:** `access:request`

```json
{ "clientId": "5f0a…", "reason": "Client called the branch; owner Adam Nowak is on leave and the client needs a same-day answer on their mortgage application.", "requestedHours": 4 }
```

**`201 Created`** — `{ "id": "g100…", "status": "PENDING_APPROVAL", "expiresAt": "…" }`

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | `reason` under 20 chars; `requestedHours` outside 1–8 |
| `404` | `CLIENT_NOT_FOUND` | The client does not exist at all. A client that exists but is out of scope resolves normally — otherwise the feature could not work |
| `409` | `ACCESS_GRANT_EXISTS` | An active grant already covers this user and client |
| `422` | `BUSINESS_RULE_VIOLATED` | The client is already in the caller's scope |

##### `POST /access-grants/{id}/approve` · `POST /access-grants/{id}/revoke`

**Permission:** `access:approve` (the client's supervisor, or an admin)

`403 PERMISSION_DENIED` when the approver is the requester (RB-BR-09 — no self-approval). `409 ILLEGAL_STATE_TRANSITION` when already approved, revoked or expired.

##### `GET /access-grants`

`?userId=&clientId=&active=true` — the supervisor's approval queue and the compliance review list.

---

#### `GET /permissions/matrix` (RB-US-08)

**Permission:** `user:read`

Returns roles × permissions × scopes, read from `role_permissions` — the same rows the enforcement layer consults, so the documentation cannot drift from the behaviour.

### 9.4 Business logic rules

| ID | Rule |
|---|---|
| RB-BR-01 | Authorization is `permission + scope`, never a role string. Application code asks "does this user hold `client:write` covering client X?", never "is this user a supervisor?". Roles exist only as bundles of permissions. |
| RB-BR-02 | **Scope resolution.** `OWN` → `clients.owner_manager_id = :userId`. `TEAM` → `clients.team_id IN (teams where the user is supervisor or an active member)`. `ALL` → unrestricted. Non-client entities inherit the scope of their client: an interaction is in scope exactly when its client is. |
| RB-BR-03 | A user holding several roles gets the **union** of permissions and, for any shared permission, the **widest** scope. A manager who is also an auditor reads all clients and writes only their own. |
| RB-BR-04 | An active break-glass grant widens `client:read`, `client:write`, `interaction:*` and `task:*` for that one client, for its window only. It never widens `client:delete`, `client:merge`, `*:reassign` or any audit permission — break-glass is for continuity of service, not for privilege escalation. |
| RB-BR-05 | The access token carries `userId`, `roles`, `permissions` with scopes, `teamIds` and `grantIds`. It lives 15 minutes, so a revocation takes effect within 15 minutes at worst, and immediately on the next refresh. Deactivation additionally pushes a revocation to a shared denylist checked on every request, bounding it to 60 seconds (RB-US-04). |
| RB-BR-06 | **No self-elevation.** A user cannot grant themselves a role, approve their own break-glass request, or approve KYC on a client they own (CP-BR-05). Enforced at request time by comparing the acting subject with the target, so holding `role:assign` is not enough. |
| RB-BR-07 | **Referential safety on deactivation.** Deactivation is refused while the user owns clients, is assigned open tasks, is the assignee of open tickets, or is the sole supervisor of a team. The `409` response enumerates each blocker with counts and a deep link, so the admin can clear them in one pass. This is what makes `ON DELETE RESTRICT` on `owner_manager_id` a safety net rather than a wall. |
| RB-BR-08 | At least one `ACTIVE` user with the `ADMIN` role must exist at all times. Removing the last admin role or deactivating the last admin returns `422 BUSINESS_RULE_VIOLATED`. A system nobody can administer is unrecoverable. |
| RB-BR-09 | Break-glass requires a second person: the requester and the approver are always different users, the reason is at least 20 characters, and the window never exceeds 8 hours (DB constraint). Every read under a grant records `access_grant_id` in the audit `context` (AT-BR-13). |
| RB-BR-10 | Refresh tokens rotate on every use and are tracked by `family_id`. Presenting an already-used token means the token was stolen, so the whole family is revoked and the user is signed out everywhere. |
| RB-BR-11 | Lockout is 5 consecutive failures → 15 minutes, counted per account, reset on success. IP-level throttling runs independently at 10/min. Counting per account alone lets an attacker spray one password across many accounts; counting per IP alone punishes a shared branch NAT. Both are needed. |
| RB-BR-12 | Passwords: minimum 12 characters, checked against a breached-password list, bcrypt cost 12, 90-day maximum age, no reuse of the last 5. Password comparison is constant-time and runs even for unknown emails. |
| RB-BR-13 | Every authorization decision that ends in a denial emits `PERMISSION_DENIED` with the attempted endpoint, entity and reason — including denials rendered to the user as `404` under rule ER-01. The user sees an ambiguous answer; the auditor sees the precise one. |
| RB-BR-14 | Role and permission changes take effect on the next token refresh (≤ 15 min) and are audited as `ROLE_CHANGE` with a mandatory reason of at least 10 characters. |
| RB-BR-15 | Users are never hard-deleted. Deactivation preserves `author_id`, `created_by` and `completed_by` references across every module, so history stays attributable years later. |

### 9.5 Screens & components

#### S-RB-01 — Login

| State | Behaviour |
|---|---|
| `idle` | Email and password, "Sign in" |
| `submitting` | Button spinner, inputs disabled |
| `error-credentials` | "Email or password is incorrect" — deliberately ambiguous; the attempt counter is not shown until the fourth failure |
| `warning-lockout` | From the fourth failure: "1 attempt remaining before your account is locked for 15 minutes" |
| `locked` | Countdown timer, sign-in disabled, "Contact your administrator" link |
| `password-expired` | Redirect to the change-password flow with the reason stated |
| `must-change` | First login → forced password change before anything else loads |
| `rate-limited` | `429` → countdown, form disabled |
| `session-expired` | Arriving here from an expired session shows "Your session ended. Sign in to continue" and preserves the return URL |

#### S-RB-02 — Users Admin

| State | Behaviour |
|---|---|
| `loading` / `success` | Table: name, employee no., email, roles, team, status, last login |
| `empty-filtered` | "No users match these filters" + **Clear filters** |
| `row-locked` | Locked accounts show a lock icon and an inline **Unlock** action |
| `deactivating` | Modal listing blockers: "12 owned clients, 4 open tasks" with reassignment target pickers, submit disabled until every blocker is resolved |
| `deactivation-blocked` | `409` returns fresh counts and the modal re-renders them rather than showing a raw error |
| `last-admin` | Attempting to remove the final admin disables the control with an explanatory tooltip, before the request is ever sent |
| `forbidden` | Supervisors see the screen scoped to their team, with the "Create user" action absent rather than disabled |

#### S-RB-03 — Role Assignment Drawer

| State | Behaviour |
|---|---|
| `success` | Current roles as removable chips, an add picker, and a live preview of the resulting effective permissions |
| `preview-diff` | Before saving: "This adds `client:read` at TEAM scope and `dashboard:team`" — the admin sees the consequence, not just the role name |
| `reason-required` | Save disabled until the reason reaches 10 characters |
| `self-assignment` | The acting admin's own row has role editing disabled with "You cannot change your own roles" (RB-BR-06) |
| `saving` / `error` | Standard |

#### S-RB-04 — Break-Glass Request (modal from S-CP-01)

| State | Behaviour |
|---|---|
| `intro` | Names the client (display name and owner only — no PII), states that access is time-limited, audited, and reviewed |
| `form` | Reason textarea with a 20-character minimum counter, plus a 1–8 h duration picker |
| `submitting` / `pending` | "Request sent to Ola Wiśniewska (supervisor)" with a live status; approval arrives by push and email |
| `approved` | Banner "Temporary access until 18:00" persists across every screen for that client, with a countdown and a **Release access** action |
| `expiring` | Under 15 minutes left → amber countdown and an extend prompt (a new request, not an extension of the old grant) |
| `expired` | Access ends mid-session: the client card switches to `not-found` with an explanation, and any unsaved composer draft is preserved locally |
| `denied` | "Your request was declined" with the approver's optional note |

#### S-RB-05 — Access Grant Review (supervisor / compliance)

| State | Behaviour |
|---|---|
| `queue` | Pending requests with requester, client, reason and requested duration; approve/deny inline |
| `empty` | "No pending access requests" |
| `history` | Past grants with use counts, so a pattern of repeated break-glass on the same client is visible |
| `self-request` | The supervisor's own request appears greyed with "Awaiting another approver" |

#### S-RB-06 — Permission Matrix (read-only reference)

| State | Behaviour |
|---|---|
| `success` | Roles as columns, permissions as rows, scope badges in cells; filterable by resource |
| `loading` / `error` | Standard |
| `diff-mode` | Two roles selected → highlights only the differences, which is how the question is actually asked |

### 9.6 Edge cases

| ID | Case | Expected behaviour |
|---|---|---|
| RB-EC-01 | A manager is promoted to supervisor mid-session | Existing access token keeps manager scope for up to 15 min. The UI notices the roles changed on the next `/me` poll and prompts "Your permissions changed — refresh to continue", forcing a token refresh |
| RB-EC-02 | A supervisor is removed from a team while viewing a team client's card | The next request returns `404 CLIENT_NOT_FOUND`; the UI shows "You no longer have access to this client" and returns to search. No data already rendered is retroactively hidden — it is already on the user's screen |
| RB-EC-03 | Break-glass expires while the user is mid-edit | The `PATCH` returns `404`. The composer keeps the draft locally and offers **Request access again**; nothing typed is lost |
| RB-EC-04 | Break-glass requested for a client whose supervisor is the requester | No valid approver in the normal chain → the request routes to the team's admin, and the UI says so at submission time rather than leaving it stuck |
| RB-EC-05 | The last admin tries to deactivate themselves | `422 BUSINESS_RULE_VIOLATED` (RB-BR-08); the UI disables the action beforehand |
| RB-EC-06 | Two admins simultaneously remove different roles from the same user | `If-Match` on `PATCH /users/{id}` → the second gets `409` with current roles; role add/remove endpoints are individually idempotent, so a retry converges |
| RB-EC-07 | A stolen refresh token is used after the legitimate client already rotated it | Reuse detected → the whole family is revoked, both sessions die, a security alert fires. The legitimate user re-authenticates, which is the correct and intended cost |
| RB-EC-08 | Credential stuffing across 500 accounts from one IP | Per-account lockout does not trigger (1 attempt each), but the per-IP limit of 10/min does. Both counters are required (RB-BR-11) |
| RB-EC-09 | Employee changes teams while owning 40 clients | `PATCH /users/{id}` with a new `primaryTeamId` returns `422` while clients remain. Either the clients move with them (bulk reassign to the same user, updating `team_id`) or to another manager. `clients.team_id` never silently diverges from the owner's team |
| RB-EC-10 | Supervisor covers two branches | Multiple `team_members` rows; `TEAM` scope resolves to the union. `is_primary` still uniquely determines the team used for new-client assignment |
| RB-EC-11 | A role's permission set is edited while 200 users hold it | The change is data, not code, and applies on each user's next token refresh. `GET /permissions/matrix` reflects it immediately, so the admin can verify before the fleet catches up |
| RB-EC-12 | A user with an expired `user_roles.expires_at` makes a request | The partial index excludes expired grants from permission resolution, so the role silently stops applying. `/me` shows it as expired rather than dropping it without explanation |
| RB-EC-13 | JWT signing key is rotated | Tokens carry a `kid`; the gateway accepts both the old and new key for one full refresh lifetime (8 h), then drops the old one. No forced mass logout |
| RB-EC-14 | A deactivated user's access token is still within its 15-minute life | The revocation denylist is checked on every request, so access ends within 60 s (RB-BR-05). The token's own expiry is the fallback, not the primary control |
| RB-EC-15 | A manager tries to read the audit log directly by URL | `403 ROLE_REQUIRED` — no audit permission exists for the manager role at any scope. The attempt is itself audited as `PERMISSION_DENIED` |
| RB-EC-16 | A client has no owner because their manager's account was force-deleted in the database | `ON DELETE RESTRICT` makes this impossible through any supported path. If it occurs through direct DB manipulation, the client fails scope resolution and appears only to admins, and a nightly consistency check reports it |

---

## 10. Non-Functional Requirements

### 10.1 Performance budgets

The target metric (15–20 min → 2–3 min) is a *human* budget. It survives only if the screen a manager opens before a call paints fast enough that they do not context-switch away from it.

| Operation | p50 | p95 | p99 | Rationale |
|---|---|---|---|---|
| `GET /clients/lookup` | 80 ms | 300 ms | 600 ms | Typed while the phone is ringing |
| `GET /clients/{id}/summary` | 150 ms | 400 ms | 900 ms | The card's first paint — the single most important number here |
| `GET /clients/{id}/interactions` (page 1) | 100 ms | 400 ms | 800 ms | Keyset-indexed |
| `POST /interactions` | 120 ms | 350 ms | 700 ms | Includes the outbox insert in the same transaction |
| `GET /tasks` (My Day) | 100 ms | 400 ms | 800 ms | Partial index |
| `GET /dashboard/overdue` | 200 ms | 700 ms | 1500 ms | Aggregate, 30 s cached |
| `GET /audit` (filtered page) | 200 ms | 800 ms | 2000 ms | Partitioned, cold partitions allowed to be slower |
| Audit ingestion lag | 500 ms | 3 s | 10 s | Alert above 60 s |

Measured server-side, excluding network. The whole client card must be interactive within **1.5 s** on a corporate laptop over branch Wi-Fi.

### 10.2 Scale assumptions

| Dimension | Year 1 | Design headroom |
|---|---|---|
| Clients | 250 000 | 5 M |
| Users | 400 | 5 000 |
| Interactions | 3 M (~12 per client) | 100 M |
| Interactions / day | 12 000 | 500 000 |
| Tasks open at any time | 30 000 | 500 000 |
| Audit rows / day | 60 000 | 2 M |
| Audit rows at 7-year retention | 150 M | 5 B (partitioned) |

Every list endpoint is paginated and every hot query has a covering or partial index, so the scale ceiling is storage rather than query shape.

### 10.3 Availability and resilience

| Property | Target |
|---|---|
| API availability | 99.5% during business hours (Mon–Fri 07:00–20:00 local) |
| RPO | 15 min (continuous WAL archiving) |
| RTO | 2 h |
| Audit durability | No acknowledged mutation may be lost — guaranteed by the outbox, not by Kafka availability |

**Degradation ladder — what still works when a dependency fails:**

| Failure | Behaviour |
|---|---|
| Kafka down | All reads and writes continue. Outbox rows accumulate; audit lag alerts fire. **No user-visible impact** |
| audit-service down | Same. Only the Audit tab and audit endpoints return `503` |
| KMS down | Sensitive reads fail with `503`. Non-sensitive endpoints (tasks, dashboards) keep working. A partially decrypted client card is never served (CP-EC-13) |
| Core banking down | Product data serves from the last sync with a visible staleness chip. Everything else is unaffected |
| PostgreSQL primary down | Failover to the hot standby; writes fail for the failover window with `503` and `Retry-After` |
| Object storage down | Attachment upload and download fail; interactions themselves are unaffected |

### 10.4 Security requirements

- TLS 1.3 in transit; AES-256-GCM at rest for sensitive columns; full-disk encryption underneath.
- JWT signed with RS256; keys in the KMS; rotated quarterly with `kid`-based overlap (RB-EC-13).
- Every input validated at the API edge (Bean Validation) **and** at the database edge (`CHECK` constraints). The database is the last line, not the only one.
- Parameterized queries throughout; no dynamic SQL string building anywhere, including in the audit filter builder, which takes a whitelist of sortable and filterable columns.
- Security headers: `Content-Security-Policy`, `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`.
- Dependency scanning in CI; the build fails on a `CRITICAL` CVE.
- Secrets never in source, environment files in the repo, or logs. `docker-compose.yml` reads from a `.env` that is git-ignored, with a committed `.env.example`.
- Application logs mask PII by default via a serializer-level filter; the audit log holds no PII at all (AR-01).

### 10.5 Observability

| Signal | Implementation |
|---|---|
| Tracing | OpenTelemetry; `traceId` == `X-Request-Id` == audit `request_id`, so a support ticket resolves to an audit row and a trace with one identifier |
| Metrics | Micrometer → Prometheus: request latency by endpoint, outbox depth and age, Kafka consumer lag, audit ingestion lag, task escalation counts, break-glass grants per day |
| Logs | Structured JSON with `traceId`, `userId`, `endpoint`; no request or response bodies |
| Health | `/actuator/health` with liveness and readiness probes; `GET /audit/health` for the ingestion pipeline (AT-US-06) |
| Alerts | Audit lag > 60 s; DLT depth > 0; outbox oldest unpublished > 5 min; hash-chain break (`CRITICAL`); p95 over budget for 10 min; failed logins > 100/5 min |

### 10.6 Testing requirements

| Layer | Coverage requirement |
|---|---|
| Unit | ≥ 80% line coverage on service and domain classes; every business rule in §5.4 / §6.4 / §7.4 / §8.4 / §9.4 has at least one named test |
| Integration | Testcontainers with real PostgreSQL and Kafka. Every DB constraint listed in this spec is asserted by a test that tries to violate it |
| Contract | REST endpoints verified against the OpenAPI spec generated from the controllers; the generated spec is diffed in CI so a breaking change cannot merge silently |
| Security | Automated per-endpoint scope tests: for each role × endpoint, assert the expected status. This matrix is generated from §9.2.8, so a permission change without a test change fails the build |
| Edge cases | Every `*-EC-*` entry in this document maps to a test case ID |
| Load | `GET /clients/{id}/summary` at 200 rps sustained, meeting the §10.1 p95 |

### 10.7 Local environment

`docker compose up` starts PostgreSQL 16, Kafka (KRaft, single broker), MinIO for object storage, and the three services, with Flyway migrations and a seed dataset (50 clients, 6 users covering every role, 300 interactions, 40 tasks with a mix of overdue and upcoming). One command, per `PROJECT_IDEA.md` §5.

---

## 11. Delivery Traceability

### 11.1 Roadmap mapping

| Phase | Modules | Endpoints | Screens | Ships when |
|---|---|---|---|---|
| **MVP** | Client Profile, Interaction Log | §5.3, §6.3 | S-CP-01…05, S-IL-01…05 | A manager can find a client and log a call end to end |
| **v2** | Audit Trail, RBAC | §8.3, §9.3 | S-AT-01…05, S-RB-01…06 | Every MVP mutation appears in a verifiable audit log, and scopes are enforced |
| **v3** | Task / Reminder | §7.3 | S-TR-01…05 | A supervisor sees overdue work across their team without asking anyone |

**Sequencing note.** RBAC ships in v2, but the MVP is not permissionless: it runs with a single hard-coded `MANAGER` role at `ALL` scope, and every endpoint is written against the permission-checking interface from day one. v2 replaces the stub implementation with the real one and no controller changes. Building the MVP without that seam would mean retrofitting scope filters into every query later — the most expensive possible ordering.

Likewise, the outbox and event envelope ship in the **MVP** even though `audit-service` arrives in v2. Events accumulate in `outbox_events` and publish to Kafka from day one; v2 adds the consumer. Retrofitting an outbox into existing write paths means touching every mutation again.

### 11.2 Requirement coverage

| Source statement | Where it is satisfied |
|---|---|
| "Открывает карточку по email/телефону/ID" | CP-US-01, `GET /clients/lookup`, S-CP-01 |
| "Контактные данные, статус KYC, список продуктов, лента взаимодействий" | CP-US-02, `GET /clients/{id}/summary`, S-CP-02 |
| "Добавляет новое взаимодействие (заметку, тикет, задачу)" | IL-US-02, IL-US-05, TR-US-01 |
| "Система логирует каждое изменение в аудит-журнал" | AT-BR-01…03, §3.4 outbox |
| "Супервайзер видит дашборд с просроченными задачами" | TR-US-05, `GET /dashboard/overdue`, S-TR-03 |
| "Роли manager, supervisor, admin" | §9.2.8 (plus `AUDITOR` and `COMPLIANCE` — see §12) |
| Risk: "Утечка персональных данных" | §4.7 encryption, ER-01, AR-01, RB-BR-04 break-glass limits |
| Risk: "Разрастание монолита" | §3.1 three services, separate schemas, no cross-service table reads |
| Risk: "Несогласованность при асинхронной записи аудита" | §3.4 transactional outbox, AT-BR-03 idempotent ingestion, AT-EC-05 |
| "Ключевые таблицы: clients, interactions, tasks, audit_log, users" | §5.2.2, §6.2.2, §7.2.2, §8.2.2, §9.2.3 |

---

## 12. Assumptions & Open Questions

### 12.1 Assumptions made where `PROJECT_IDEA.md` was silent

| # | Assumption | Basis |
|---|---|---|
| A-01 | Two roles beyond the stated three: `AUDITOR` (read-only, all clients) and `COMPLIANCE` (KYC approval and erasure). | The idea file names three roles but also requires GDPR erasure and separation of duties on KYC. Overloading `ADMIN` with compliance authority would break CP-BR-05. Both new roles are additive — the three named roles behave exactly as described. |
| A-02 | 7-year audit retention. | Standard financial record-keeping. Configurable per deployment. |
| A-03 | Ticket SLA: 4 / 24 / 72 / 168 business hours by priority. | Not specified anywhere; these are conventional first-response targets and are configuration, not code. |
| A-04 | Interaction edit window of 15 minutes. | Balances IL-US-04 ("fix a typo") against auditability. Configurable. |
| A-05 | Break-glass ceiling of 8 hours, approved by a second person. | One working day is the longest that "cover an absent colleague" justifies. |
| A-06 | Snooze cap of 3 per task, 30 days each. | Prevents indefinite postponement while allowing genuine client-driven delay. |
| A-07 | Products are read-only, ingested from core banking. | The idea file lists "статус продуктов" as something to *display*; nothing suggests Client360 opens accounts. |
| A-08 | Client email and tax ID are unique; phone is not. | CP-EC-03 — shared household numbers are common and a uniqueness constraint would reject legitimate records. |
| A-09 | The UI is a single-page application. | Screens and states are described framework-agnostically; nothing in this spec depends on the choice. |
| A-10 | Every task belongs to a client. | TR-BR-01. Makes task RBAC derive from client scope with no second mechanism. |
| A-11 | Minimum client age 18. | Retail banking default. SME clients use the registration date. |
| A-12 | Currency stored as minor units in `BIGINT`. | No float arithmetic on money, ever. |
| A-13 | Through `PATCH`, `risk` additionally requires `client:kyc` and `status` requires `client:delete`. | §5.3 named only the manager's `403`, not who may change these. A risk rating is a compliance judgement, so it follows CP-BR-05's separation of duties; client lifecycle is admin authority, and CP-BR-08 already makes reopening admin-only. Mapping to permissions rather than roles keeps rule RB-BR-01. |

### 12.2 Open questions for the product owner

| # | Question | Why it matters | Default if unanswered |
|---|---|---|---|
| Q-01 | Does the bank have an existing IdP (LDAP / Active Directory / SAML)? | Would replace §9.3's local password handling with federation, deleting `password_hash`, `login_attempts` and the lockout logic. | Local authentication as specified, with the `users` table shaped so an external `subject_id` column can be added without migration pain. |
| Q-02 | Do managers need to see clients belonging to *other* teams in read-only mode? | Changes `TEAM` scope resolution and the volume of break-glass requests. | No. Break-glass is the only cross-team path (RB-US-05). |
| Q-03 | Is there a real holiday calendar service for business-hours SLA? | IL-BR-07 and TR-BR-14 need one to be correct near public holidays. | A static per-team holiday table, seeded annually. |
| Q-04 | Should interaction bodies be searchable? | Would require abandoning body encryption or adopting searchable encryption — a significant architectural change (§4.7). | Subject-only search. Bodies stay encrypted. |
| Q-05 | Is there a notification channel beyond in-app and email (SMS, Teams)? | Adds `reminder_channel` values and a delivery adapter. | In-app and email only. |
| Q-06 | What is the actual regulatory retention period in the target jurisdiction? | Drives partition count and archival storage cost. | 7 years (A-02). |
| Q-07 | Should supervisors see managers' private notes (IL-US-08)? | A genuine policy decision, not a technical one. Making them visible removes the feature's purpose; keeping them hidden creates a small blind spot. | Hidden from supervisors, visible to admins as metadata only (IL-BR-09). |
| Q-08 | Is client merge ever reversible? | CP-BR-10 currently specifies it is not, which makes the confirmation step deliberately heavy. | Irreversible through the API; reversal is a DBA runbook. |

---

*End of specification. Rule and edge-case identifiers in this document (`CP-BR-*`, `IL-EC-*`, and so on) are stable references — cite them in code comments, commit messages and test names.*
