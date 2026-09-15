# Client360

Banking CRM that gives a relationship manager one auditable view of a customer.
Target metric: cut pre-contact preparation from 15–20 min to **2–3 min**.

## SPEC.md is the contract

`SPEC.md` holds the data models, API contracts, business rules and edge cases for all five
modules. Identifiers in it (`CP-BR-03`, `IL-EC-07`, `RB-US-05`, …) are **stable references**.

- Read the relevant module section **before** writing code. Do not infer a contract from
  neighbouring code.
- Cite the identifier in code comments, commit messages and test names:
  `@Test void rejectsSnoozeBeyondCap_TR_BR_06()`.
- Behaviour that contradicts SPEC.md is a bug in the code, not a new decision — unless you
  update SPEC.md in the same change and say so.

## Stack

| Layer | Choice |
|---|---|
| Language / framework | Java 21, Spring Boot 3.x, Spring Security |
| Database | PostgreSQL 16 — Flyway migrations in `db/migrations/` |
| Messaging | Apache Kafka (KRaft), transactional outbox on the producer side |
| Object storage | MinIO (S3 API) for interaction attachments |
| Build | Maven wrapper (`./mvnw`) |
| Test | JUnit 5, Testcontainers (real PostgreSQL + Kafka), REST Assured |
| Local env | `docker compose up` — API + DB + broker + MinIO, one command |

## Architecture

```
client-service ──┐                          ┌──> audit-service (consumer only)
                 ├──> outbox ──> Kafka ─────┘     append-only audit_log
interaction-service ─┘
```

| Service | Owns |
|---|---|
| `src/client-service/` | `clients`, `client_products`, and all RBAC tables |
| `src/interaction-service/` | `interactions`, `interaction_attachments`, `tasks`, `task_reminders`, `task_history` |
| `src/audit-service/` | `audit_log` (partitioned), `audit_chain_head`, export jobs |

No service reads another service's tables. Cross-service data moves by REST or by
denormalized fields on Kafka events.

## Non-negotiable rules

1. **No plaintext PII on the Kafka bus or in the audit log** (AR-01). Sensitive values in
   `changedFields` are the literal `***MASKED***`. This is what makes 7-year audit retention
   and GDPR erasure compatible.
2. **Out-of-scope reads return `404`, not `403`** (ER-01), so record existence cannot be
   probed. The real reason still goes to the audit log as `PERMISSION_DENIED`.
3. **Every mutation writes an outbox row in the same transaction** as the business change.
   Never publish to Kafka directly from a service method.
4. **Nothing deletes an audit row.** No endpoint, no role, no permission code. `audit-service`
   has no write API.
5. **Authorize on `permission + scope`, never on a role string.** Ask "does this user hold
   `client:write` covering client X?" — never `hasRole('SUPERVISOR')`.
6. **Money is `BIGINT` minor units + ISO currency.** No floating point, anywhere.
7. **Timestamps are `TIMESTAMPTZ`, UTC, from `now()` in PostgreSQL** — never the JVM clock,
   so overdue is evaluated against one clock system-wide.
8. **Sensitive columns come in pairs**: `<field>_enc` (AES-256-GCM) + `<field>_hash`
   (HMAC over the normalized value) for lookup. Never store plaintext PII.
9. **Validate at both edges**: Bean Validation at the API, `CHECK` constraints in the DB.
   The database is the last line, not the only one.
10. **Never log request or response bodies.** Structured JSON logs carry `traceId`, `userId`,
    `endpoint` — nothing else.

## Conventions

- Base path `/api/v1`. Errors use the envelope in SPEC.md §4.3 with a stable `code`.
- `POST` creates require `Idempotency-Key`; `PATCH`/`DELETE` on versioned aggregates require
  `If-Match` and return `409 VERSION_CONFLICT` on a stale version.
- Offset pagination for admin lists; **keyset pagination** for the interaction timeline and
  audit log. Never `OFFSET` a large table.
- Soft delete everywhere (`deleted_at` + `deletion_reason`). Users and audit rows are never
  hard-deleted.
- Migrations are forward-only and immutable once merged: `V<n>__<snake_case>.sql`.
- UUID v7 for all identifiers.

## Commands

Working today (no Java needed — `cp .env.example .env` first):

```bash
docker compose up -d                     # Postgres + Kafka + MinIO + both Flyway runs
docker compose --profile seed up seed    # 2 teams, 9 users
docker compose logs flyway-client        # migration output
docker compose down -v                   # reset everything, including data
```

Once `src/` has buildable modules:

```bash
docker compose --profile app up -d       # add the three services
./mvnw -pl src/client-service test       # one service's tests
./mvnw verify                            # full build + Testcontainers integration tests
./mvnw spotless:apply                    # format before committing
curl localhost:8082/api/v1/audit/health  # ingestion lag per partition
```

Git Bash rewrites in-container paths — prefix `docker exec` with `MSYS_NO_PATHCONV=1`.

## Where to look

- `db/README.md` — schema layout, migration phasing, and the two things about this
  database that will surprise you (temporal triggers; why clients aren't in the SQL seed).
- `.claude/rules/` — per-folder rules, loaded by glob when you touch a service.
- `.claude/agents/` — `database-architect`, `backend-engineer`, `security-engineer`,
  `frontend-developer`, `qa-reviewer`. Use them when the user asks for them; otherwise work
  directly.
- `SPEC_TEMPLATE.md` — the shape every new feature spec must follow.

## Definition of done

A change is done when: the SPEC rule it implements is cited in a test name; DB constraints
are covered by a test that tries to violate them; the RBAC matrix test still passes for every
role × endpoint; and no new endpoint bypasses the outbox.
