# Database

PostgreSQL 16. One database (`client360`), three schemas — one per service, per
`SPEC.md` §3.1. In production these are separate instances; locally they share one so a
single `docker compose up` works.

```
db/
├── init/                          # runs once, on an empty data directory
│   └── 01_bootstrap.sql           # extensions, schemas, client360_app role + grants
├── migrations/
│   ├── client-service/            # Flyway, schema `client`
│   └── interaction-service/       # Flyway, schema `interaction`
└── seed/
    └── local/seed_local.sql       # local only, never in a V* migration
```

`audit-service` migrations (schema `audit`) arrive with v2 — see [Phasing](#phasing).

## Running

```bash
docker compose up -d                 # infra + both Flyway runs
docker compose --profile seed up     # add teams and users
docker compose logs flyway-client    # migration output
```

Each service has its **own** Flyway history table in its own schema, so version numbers
restart at `V1` per service and the two migrate independently.
`flyway-interaction` waits for `flyway-client` because interaction tables carry FKs into
`client.clients` and `client.users` in the shared dev database.

Verify:

```bash
docker compose exec postgres psql -U client360 -d client360 -c '\dt client.*'
docker compose exec postgres psql -U client360 -d client360 -c '\dt interaction.*'
```

## Phasing

Migrations follow the roadmap in `SPEC.md` §11.1 rather than landing the whole schema at
once.

| Phase | Tables |
|---|---|
| **MVP** (present) | `teams`, `users`, `clients`, `client_products`, `interactions`, `interaction_attachments`, and an `outbox_events` in each service schema |
| **v2** | `audit_log` (partitioned) + `audit_chain_head` + export jobs; the rest of RBAC — `roles`, `permissions`, `role_permissions`, `user_roles`, `team_members`, `access_grants`, `refresh_tokens`, `login_attempts` |
| **v3** | `tasks`, `task_reminders`, `task_history` |

`teams` and `users` ship in the MVP despite RBAC being a v2 module: they are FK targets
for `clients`, and creating them thin now to widen them later would be pure churn.

The **outbox ships in the MVP** even though `audit-service` is v2. Events accumulate and
publish from day one; v2 only adds the consumer. Retrofitting an outbox into existing
write paths means touching every mutation a second time.

## Two things that will surprise you

**1. Temporal rules are triggers, not `CHECK` constraints.**

PostgreSQL requires every function in a `CHECK` or an index predicate to be `IMMUTABLE`.
`now()` and `CURRENT_DATE` are `STABLE`, so `CHECK (occurred_at <= now() + INTERVAL '5 minutes')`
fails at `CREATE TABLE` with *functions in check constraint must be marked IMMUTABLE*.

Three rules are affected, each enforced by a `BEFORE INSERT OR UPDATE` trigger raising
`SQLSTATE 23514` so the application maps it exactly as it would map a constraint:

| Rule | Trigger |
|---|---|
| Client is at least 18 (CP-BR-14) | `client.trg_clients_validate` |
| `opened_on` not in the future | `client.trg_client_products_validate` |
| `occurred_at` at most 5 min ahead (IL-EC-02) | `interaction.trg_interactions_validate` |

Column-to-column comparisons (`closed_on >= opened_on`) call no function and stay ordinary
`CHECK` constraints. Same restriction is why `is_overdue` is computed at query time
(TR-BR-02). See `SPEC.md` §4.12.

**2. Clients and interactions are not in the SQL seed.**

Their PII columns hold AES-256-GCM ciphertext plus an HMAC lookup hash (`SPEC.md` §4.7),
and pgcrypto cannot produce GCM ciphertext the application could decrypt. Seeding them in
SQL would mean embedding the data key or inventing a plaintext `key_version` that
production code would then have to tolerate — both worse than the alternative. That seed
belongs to a dev-profile `ApplicationRunner` in `client-service`, which holds the key.

The SQL seed covers teams and users, which have no encrypted columns.

## Conventions

Naming, `ON DELETE` policy, safe changes to populated tables, and the `audit_log`
partition rules live in `.claude/rules/db-migrations.md`. Read it before adding a
migration.

Every migration ships with a Testcontainers test that **tries to violate** each new
constraint and asserts the failure. A constraint with no such test is untested.
