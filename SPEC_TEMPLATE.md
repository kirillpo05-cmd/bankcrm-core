# Feature Spec — <Feature Name>

> Copy this file to `docs/specs/<feature-slug>.md`, fill every section, and delete the
> guidance blockquotes. A section that genuinely does not apply is marked
> **"Not applicable — <reason>"**, never deleted. An empty section is a decision nobody made.
>
> Sections mirror `SPEC.md` §5–§9 so a finished feature spec can be folded into it directly.

**Prefix:** `<XX>` — two letters, unique across the repo. Every rule and edge case below is
identified as `XX-BR-01`, `XX-EC-01`, and those identifiers are cited in code comments,
commit messages and test names forever. Pick carefully.

| Field | Value |
|---|---|
| Author | |
| Date | |
| Status | Draft / In review / Approved / Implemented |
| Roadmap phase | MVP / v2 / v3 / v4 |
| Owning service | `client-service` / `interaction-service` / `audit-service` |
| Related modules | e.g. Client Profile (§5), RBAC (§9) |
| Supersedes | Spec or rule IDs this changes, if any |

---

## 1. Problem

> Two or three sentences. What is a bank employee doing today that is slow, error-prone or
> unauditable? Name the role. If you cannot name who is hurting, stop here.

## 2. Outcome

> What is true after this ships that is not true now. One measurable statement where possible
> — "a supervisor sees breached SLAs without opening a second system", not "improved
> visibility".

**Non-goals** — what this deliberately does not do, so scope creep has something to bounce off.

---

## 3. User stories

> Minimum three, five or more for anything non-trivial. Acceptance criteria must be
> checkable by a test, not by opinion. "Fast" is not a criterion; "p95 < 400 ms" is.

| ID | As a… | I want to… | So that… | Acceptance criteria |
|---|---|---|---|---|
| XX-US-01 | | | | |
| XX-US-02 | | | | |
| XX-US-03 | | | | |

---

## 4. Data model

> SQL-style DDL, not prose. Named constraints, explicit `ON DELETE`, indexes with the query
> each one serves. If the feature adds no table, say which existing tables it reads and
> whether it needs a new index.

### 4.1 Enumerated types

```sql
CREATE TYPE <name> AS ENUM ('…','…');
```

### 4.2 Tables

```sql
CREATE TABLE <table> (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- …
    version    INTEGER     NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,

    CONSTRAINT fk_<table>_<target> FOREIGN KEY (…) REFERENCES …(id) ON DELETE RESTRICT,
    CONSTRAINT ck_<table>_<rule>   CHECK (…)
);

-- Serves XX-US-02
CREATE INDEX ix_<table>_<cols> ON <table> (…) WHERE deleted_at IS NULL;
```

### 4.3 Checklist

- [ ] `TIMESTAMPTZ`, never `TIMESTAMP`
- [ ] Money as `BIGINT` minor units + `CHAR(3)` currency
- [ ] Sensitive fields as `_enc` / `_hash` pairs plus `key_version`
- [ ] Every `ON DELETE` justified: `CASCADE` only for pure projections
- [ ] `version` column on any mutable aggregate root
- [ ] Every invariant that can be a `CHECK`, is one
- [ ] Partial indexes where a predicate is always present

### 4.4 Relationship map

```
<parent> 1───* <child>   (CASCADE / RESTRICT — why)
```

---

## 5. API endpoints

> One block per endpoint. Method, path, permission, request body, response body, and a
> complete error table. Status and `code` values are the contract — the frontend switches on
> `code`, so changing one is a breaking change.

### `<METHOD> /api/v1/<path>` — <what it does>

**Permission:** `<resource>:<action>` scope `OWN` / `TEAM` / `ALL`
**Headers:** `Idempotency-Key` (creates) · `If-Match` (versioned writes)

```json
{ "request": "body" }
```

**`200 OK`**

```json
{ "response": "body" }
```

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_FAILED` | |
| `403` | `PERMISSION_DENIED` | |
| `404` | `<ENTITY>_NOT_FOUND` | Absent **or out of scope** — see ER-01 |
| `409` | `VERSION_CONFLICT` | |
| `422` | `BUSINESS_RULE_VIOLATED` | |

### Endpoint checklist

- [ ] Creates require `Idempotency-Key`; versioned writes require `If-Match`
- [ ] Out-of-scope reads return `404`, not `403` (ER-01)
- [ ] Lists are paginated — keyset for anything unbounded or append-heavy
- [ ] Every mutation writes an outbox row in the same transaction
- [ ] Sensitive values masked in the emitted event (AR-01)
- [ ] Permission added to the matrix in `SPEC.md` §9.2.8 for every role

---

## 6. Screens & components

> One table per screen, using the shared state vocabulary in `SPEC.md` §4.11. List the states
> this screen actually has — including the ones that are easy to forget.

### S-XX-01 — <Screen name>

| State | Behaviour |
|---|---|
| `loading` | Skeleton matching the final layout |
| `empty` | Cause + primary action |
| `empty-filtered` | Distinct from `empty` — offers "clear filters" |
| `success` | |
| `partial` | Secondary panel failed; render the rest with an inline retry |
| `error` | Error card with `requestId` |
| `forbidden` | Names the missing permission |
| `not-found` | Copy must not reveal whether the record exists |
| `saving` | |
| `conflict` | Field-level diff from `details[0].current` |

- [ ] Affordances rendered from the server's `permissions`, never a hard-coded role string
- [ ] Keyboard path defined for the primary action

---

## 7. Business logic rules

> Numbered, testable, and stating the *why* where the rule is surprising. Each becomes a test
> name. If a rule cannot be tested as written, it is not yet a rule.

| ID | Rule |
|---|---|
| XX-BR-01 | |
| XX-BR-02 | |
| XX-BR-03 | |

---

## 8. Edge cases

> Concurrency, clock skew, partial failure, empty and maximum sizes, deactivated users, merged
> or deleted clients, dependency outages, permission changes mid-session. Each becomes a test.

| ID | Case | Expected behaviour |
|---|---|---|
| XX-EC-01 | | |
| XX-EC-02 | | |
| XX-EC-03 | | |

**Prompts worth answering every time:**

- Two users act on the same record simultaneously — who wins, and what does the loser see?
- The dependency (Kafka / KMS / core banking / object storage) is down — what still works?
- The acting user's permissions change mid-session — what happens to their open screen?
- The client is merged, soft-deleted or GDPR-erased — what does this feature do?
- Zero results, and the maximum plausible number of results.

---

## 9. Security & audit

| Question | Answer |
|---|---|
| New permission codes | |
| Scope per role | Update `SPEC.md` §9.2.8 |
| New sensitive fields | Encrypted? Masked in events? Masked in logs? |
| Audit actions emitted | `CREATE` / `UPDATE` / `DELETE` / `READ_SENSITIVE` / … |
| Does anything read PII? | Then it emits `READ_SENSITIVE` — but not on list previews (IL-BR-11) |
| Break-glass interaction | Does a temporary grant widen this? It must not widen delete or audit |

---

## 10. Performance

| Operation | p50 | p95 | Rationale |
|---|---|---|---|
| | | | |

Expected volume at year 1, and the query plan for the hottest path.

---

## 11. Testing

- [ ] One test per `XX-BR-*`, named with the identifier
- [ ] One test per `XX-EC-*`
- [ ] One test per new constraint that **tries to violate** it
- [ ] Authorization test per role × new endpoint
- [ ] Integration tests on Testcontainers — real PostgreSQL and Kafka, not mocks

---

## 12. Assumptions & open questions

| # | Assumption | Basis |
|---|---|---|
| A-01 | | |

| # | Question | Why it matters | Default if unanswered |
|---|---|---|---|
| Q-01 | | | |

> Every open question carries the default you will build if nobody answers. A question with no
> default blocks the work; a question with one does not.
