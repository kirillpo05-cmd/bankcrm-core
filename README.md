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
| **Interaction Log** — timeline, corrections, tickets, attachments | MVP | Built |
| **Audit Trail** — append-only log with a per-partition hash chain | v2 | Built |
| **RBAC** — permission + scope, break-glass grants | v2 | Built |
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
| Object storage | S3 API — LocalStack locally, a real bucket in a deployment |
| Build | Maven wrapper, Spotless (Palantir format) |
| Test | JUnit 5, Testcontainers against a real PostgreSQL |
| CI | GitHub Actions — build, tests, formatter, migration immutability |

## Running it

Everything below works today.

```bash
cp .env.example .env
docker compose up -d                      # Postgres + Kafka + S3 + all three Flyway runs
docker compose --profile seed up seed     # 2 teams, 9 users
```

For the services themselves (`client-service` on 8080, `interaction-service` on 8081) you need a
token, and `POST /auth/login` issues one:

```bash
scripts/dev-jwt.sh keys                   # once: writes .dev/, prints two lines for .env
docker compose --profile app up -d --build   # all three services: 8080, 8081, 8082

TOKEN=$(curl -s localhost:8080/api/v1/auth/login           -H 'Content-Type: application/json'           -d '{"email":"a.nowak@bank.example","password":"local-dev-only"}'         | python -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
curl -s localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN"
```

The seeded password is a `{noop}` hash, and the service **refuses to start** with one outside the
`local` or `test` profile — a demo credential must not be able to become a deployed one.
client-service holds the signing key because it is the only issuer; the other services get the
public half only. `scripts/dev-jwt.sh token <uuid> MANAGER` still mints a token by hand, but it
carries no `permissions` claim — and that claim is what authorizes a request in interaction-service
and audit-service, so a hand-minted token authenticates everywhere and authorizes nothing outside
client-service. None of this material may be reused anywhere a real customer record exists.

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
| `GET` `/internal/clients/{id}/access` · `/internal/users` · `/internal/me/teams` | what other services authorize against |
| `POST` `/auth/login` · `/auth/refresh` · `/auth/logout` · `/auth/logout-all` | sessions; refresh tokens rotate and a reused one revokes the family |
| `GET` `/me` · `GET` `/permissions/matrix` | effective permissions with scopes, and the matrix read from the rows enforcement uses |
| `POST`/`GET` `/access-grants` · `POST` `/access-grants/{id}/approve` · `/revoke` | break-glass: one client, 8 hours maximum, a second person approves, every read counted |
| `GET`/`POST` `/users` · `GET`/`PATCH` `/users/{id}` | scoped directory, invitation-style create, optimistic-locked edits |
| `POST`/`DELETE` `/users/{id}/roles` · `/deactivate` · `/reactivate` · `/unlock` | role changes with a mandatory reason; deactivation lists every blocker with counts |
| `GET`/`POST` `/teams` · `PATCH` `/teams/{id}` · `POST`/`DELETE` `/teams/{id}/members` | the unit of TEAM scope, with cycle and sole-membership guards |

**interaction-service** — `/api/v1`

| Endpoint | What it does |
|---|---|
| `POST`/`GET` `/clients/{id}/interactions` | log a contact; keyset-paginated timeline |
| `GET`/`PATCH`/`DELETE` `/interactions/{id}` | detail, edit inside the 15-minute window, soft delete |
| `POST` `/interactions/{id}/corrections` | the only remedy after the window closes |
| `POST`/`GET`/`DELETE` `/interactions/{id}/attachments` | up to 5 per interaction, streamed to object storage |
| `GET` `/interactions` · `GET` `/tickets` | the cross-client feed and the ticket queue, keyset-paginated |
| `GET` `/internal/users/{id}/open-work` | what a leaver still has assigned, for RB-BR-07's deactivation blockers |
| `PATCH` `/interactions/{id}/ticket` | the IL-BR-08 state machine, including the SLA pause |

**audit-service** — `/api/v1/audit`

| Endpoint | What it does |
|---|---|
| `GET` `/audit` | keyset search; refuses an unfiltered query, because a 7-year scan is not a request |
| `POST` `/audit/verify` | recomputes the hash chain; a detected break is `200` with `verified: false` |
| `GET` `/audit/health` | consumer lag, reported as a gap rather than degrading quietly |
| `POST`/`GET` `/audit/exports` | async CSV/JSONL export, streamed to object storage, 15-minute signed link |

There is no write endpoint, and there must never be one: entries are created solely by consuming
Kafka, so a compromised application service cannot forge one without also compromising the broker.
That holds for the service's own events too — reading the log is audited, and the entry goes out
through the outbox to `audit.events` and comes back through the same consumer, so audit-service has
no more privilege over the log than anyone else.

## Testing

605 tests, all against a real PostgreSQL in Testcontainers rather than an in-memory stand-in —
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
├── docker-compose.yml       Postgres, Kafka, S3, Flyway, the services
├── .github/workflows/       build, tests, spotless, migration immutability
├── .claude/
│   ├── rules/               per-folder rules, loaded by glob
│   ├── agents/              database-architect, backend-engineer, security-engineer, …
│   └── skills/              new-migration, new-endpoint, local-stack, pr-description
├── scripts/dev-jwt.sh       keypair generator; also mints a token by hand for debugging
├── src/
│   ├── common/              crypto, outbox, idempotency, security seam, web envelopes
│   ├── client-service/      clients, products, and all RBAC tables
│   ├── interaction-service/ interactions, attachments, tasks
│   └── audit-service/       append-only audit_log, hash chain, verification
└── db/
    ├── init/                extensions, schemas, the restricted role
    ├── migrations/          Flyway, per service, forward-only
    └── seed/                local and test profiles only
```

`db/README.md` covers the schema layout and the two things about this database that will surprise
you: the temporal triggers, and why clients are not in the SQL seed.

## Status

Work in progress, built spec-first with Claude Code. The MVP is complete; v2 is partly built.
Outstanding: the whole of Task/Reminder (§7), the password invitation and change flow §9.3
describes but does not endpoint, and the 27 UI screens of §5.5 through §9.5 — there is no frontend
code yet. Four of the five modules are complete. All three services enforce real permissions:
client-service reads the RBAC tables it owns, and the other two read the access token's
`permissions` claim, which is what lets there be one authorization authority and no call per
check. What is built is tested and runs.
