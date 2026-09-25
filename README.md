# Client360

A banking CRM that gives a relationship manager one auditable view of a customer — built as a
demonstration of secure, spec-first backend architecture for financial services.

## Problem

Bank relationship managers lose 15–20 minutes before every client call piecing together
information scattered across separate systems: contact and KYC data in one place, product status
in another, notes in a spreadsheet. There is no single, auditable view of a customer.

**Target metric: 15–20 minutes of preparation down to 2–3.**

## Solution

One call — `GET /clients/{id}/summary` — returns the profile, products and the recent interaction
feed together, so the card paints in one round trip instead of four. Every change is recorded for
compliance as it happens, not reconstructed afterwards.

| Module | Phase | State |
|---|---|---|
| **Client Profile** — customer card, KYC, products, ownership | MVP | Built |
| **Interaction Log** — timeline of calls, notes, corrections | MVP | Timeline, editing and corrections built; tickets and attachments in progress |
| **Audit Trail** — append-only log with a per-partition hash chain | v2 | Events are already produced through the outbox; the consumer is not built |
| **RBAC** — permission + scope, break-glass grants | v2 | The `AccessPolicy` seam is in place and every endpoint authorizes through it; a stub resolves it until the tables land |
| **Task / Reminder** — follow-ups, escalation, SLA dashboard | v3 | Not started |

`SPEC.md` is the contract for all five: data models, API shapes, business rules and edge cases,
each with a stable identifier (`CP-BR-03`, `IL-EC-07`) that code comments and test names cite.

## Architecture

```
client-service ──┐                          ┌──> audit-service (consumer only)
                 ├──> outbox ──> Kafka ─────┘     append-only audit_log
interaction-service ─┘
```

Three services, three schemas, no cross-service table reads. Data moves by REST or by
denormalized fields on Kafka events. `client-service` is the authorization authority: it answers
`GET /internal/clients/{id}/access`, and `interaction-service` asks it on every write.

Some decisions worth knowing before reading the code:

- **Every mutation writes its outbox row in the same transaction** as the business change. Nothing
  publishes to Kafka from a service method, so an event cannot exist without the change it
  describes, or the reverse.
- **Out-of-scope reads answer `404`, never `403`**, so record existence cannot be probed. The real
  reason still reaches the audit log as `PERMISSION_DENIED`.
- **Sensitive columns come in pairs** — `<field>_enc` (AES-256-GCM) and `<field>_hash` (HMAC, for
  lookup). No plaintext PII column, and no plaintext PII on the bus or in the audit log.
- **Timestamps come from `now()` in PostgreSQL**, never the JVM clock, so "overdue" is judged
  against one clock system-wide.
- **Money is `BIGINT` minor units** plus an ISO currency code. No floating point anywhere.

### Stack

| Layer | Choice |
|---|---|
| Language / framework | Java 21, Spring Boot 3.5, Spring Security |
| Database | PostgreSQL 16, Flyway forward-only migrations |
| Messaging | Apache Kafka (KRaft), transactional outbox |
| Object storage | MinIO (S3 API), for interaction attachments |
| Build | Maven wrapper, Spotless (Palantir format) |
| Test | JUnit 5, Testcontainers against a real PostgreSQL |
| CI | GitHub Actions — build, tests, formatter, migration immutability |

## Running it

Everything below works today.

```bash
cp .env.example .env
docker compose up -d                      # Postgres + Kafka + MinIO + both Flyway runs
docker compose --profile seed up seed     # 2 teams, 9 users
```

For the services themselves (`client-service` on 8080, `interaction-service` on 8081) you also
need a token. Nothing issues one yet — `POST /auth/login` arrives with RBAC in v2 — so a script
stands in for the issuer:

```bash
scripts/dev-jwt.sh keys                   # once: writes .dev/, prints a line for .env
docker compose --profile app up -d --build

TOKEN=$(scripts/dev-jwt.sh token 3f000000-0000-4000-8000-000000000002 MANAGER)
curl -s localhost:8080/api/v1/clients/{id}/summary -H "Authorization: Bearer $TOKEN"
```

The private key lives in `.dev/`, which is git-ignored. None of this material may be reused
anywhere a real customer record exists.

```bash
./mvnw verify                             # full build + Testcontainers integration tests
./mvnw spotless:apply                     # format before committing; CI gates on spotless:check
docker compose down -v                    # reset everything, including data
```

Git Bash rewrites in-container paths — prefix `docker exec` with `MSYS_NO_PATHCONV=1`.

## Endpoints today

**client-service** — `/api/v1`

| Endpoint | What it does |
|---|---|
| `POST` `/clients` · `GET` `/clients` | create, scoped admin list |
| `GET` `/clients/lookup` | by email, phone or external reference |
| `GET` `/clients/{id}` · `PATCH` `/clients/{id}` · `DELETE` `/clients/{id}` | card, merge-patch, soft delete |
| `GET` `/clients/{id}/summary` | the card's first paint, with partial failures in `degraded[]` |
| `POST` `/clients/{id}/kyc` · `POST` `/clients/{id}/reassign` | KYC state machine, ownership transfer |
| `GET`/`POST`/`PATCH` `/clients/{id}/products` | read-only projection of core banking |
| `GET` `/internal/clients/{id}/access` · `GET` `/internal/users` | what other services authorize against |

**interaction-service** — `/api/v1`

| Endpoint | What it does |
|---|---|
| `POST`/`GET` `/clients/{id}/interactions` | log a contact; keyset-paginated timeline |
| `GET`/`PATCH`/`DELETE` `/interactions/{id}` | detail, edit inside the 15-minute window, soft delete |
| `POST` `/interactions/{id}/corrections` | the only remedy after the window closes |

## Testing

231 tests, all against a real PostgreSQL in Testcontainers rather than an in-memory stand-in —
the `CHECK` constraints, temporal triggers and partial indexes only behave correctly against the
real thing. Tests are named for the rule they pin: `omitsTheTimelineForACallerWithoutInteractionRead_RB_BR_02`.

The standing rule is that every migration ships with a test that **tries to violate** each
constraint it adds. Today that holds for the two most recent (`idempotency_keys`,
`ticket_sla_pause`); the earlier migrations are covered indirectly, through API tests that trip
the constraints from above. Closing that gap is outstanding work, not a finished practice.

```bash
./mvnw verify
./mvnw -pl src/client-service test         # one service
```

## Repository structure

```
client360/
├── SPEC.md                  the contract: models, APIs, rules, edge cases
├── CLAUDE.md                working agreements for this codebase
├── Dockerfile               one file, both services (a single Maven reactor)
├── docker-compose.yml       Postgres, Kafka, MinIO, Flyway, the services
├── .github/workflows/       build, tests, spotless, migration immutability
├── .claude/
│   ├── rules/               per-folder rules, loaded by glob
│   ├── agents/              database-architect, backend-engineer, security-engineer, …
│   └── skills/              new-migration, new-endpoint, local-stack, pr-description
├── scripts/dev-jwt.sh       local token issuer, standing in until v2
├── src/
│   ├── common/              crypto, outbox, idempotency, security seam, web envelopes
│   ├── client-service/      clients, products, and all RBAC tables
│   ├── interaction-service/ interactions, attachments, tasks
│   └── audit-service/       (v2)
└── db/
    ├── init/                extensions, schemas, the restricted role
    ├── migrations/          Flyway, per service, forward-only
    └── seed/                local and test profiles only
```

`db/README.md` covers the schema layout and the two things about this database that will surprise
you: the temporal triggers, and why clients are not in the SQL seed.

## Status

Work in progress, built spec-first with Claude Code. The MVP is not feature-complete: tickets,
attachments and the cross-client feed are the next items in §6, and `audit-service` has no module
yet. What is built is tested and runs.
