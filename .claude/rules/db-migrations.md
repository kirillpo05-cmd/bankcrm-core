---
name: db-migrations
description: Rules for Flyway migrations in db/migrations — naming, immutability, constraint conventions, safe rewrites of populated tables, and audit_log partitioning.
globs:
  - "db/migrations/**"
  - "db/migrations/*.sql"
  - "src/*/src/main/resources/db/migration/**"
---

# db/migrations

Flyway, forward-only. The schema is a correctness boundary for financial and personal data —
`SPEC.md` §5.2, §6.2, §7.2, §8.2 and §9.2 hold the intended DDL, including named constraints
and index definitions. Follow them, or update the spec in the same change.

## Naming and immutability

- `V<n>__<snake_case_description>.sql`, sequential, one logical change per file.
- **Never edit a migration that has run anywhere**, including a teammate's local stack. Fix
  forward with a new migration.
- Repeatable migrations (`R__*.sql`) only for views and functions, never for tables or data.

## Constraint conventions

| Kind | Pattern |
|---|---|
| Check | `ck_<table>_<rule>` |
| Foreign key | `fk_<table>_<target>` |
| Unique constraint | `uq_<table>_<cols>` |
| Unique index | `ux_<table>_<cols>` |
| Index | `ix_<table>_<cols>` |

An anonymous constraint produces an unusable error message in production. Name everything.

Every invariant that can be a `CHECK` is a `CHECK`. Where a rule cannot be expressed as one —
a per-parent count, such as the 5-attachment limit — add a comment naming what enforces it
instead, so the absence reads as a decision rather than an oversight.

## Column rules

- `TIMESTAMPTZ` always. `TIMESTAMP` without a zone is a bug here.
- Money is `BIGINT` minor units plus `CHAR(3)` currency with a format `CHECK`. Never `NUMERIC`
  or `FLOAT` for balances.
- Sensitive data is a pair — `<field>_enc BYTEA` and `<field>_hash BYTEA` — plus a
  `key_version SMALLINT` on the table. Never a plaintext PII column.
- UUID v7 primary keys, `DEFAULT gen_random_uuid()` where the DB generates them.
- Mutable aggregate roots carry `version INTEGER NOT NULL DEFAULT 0` for optimistic locking.

## `ON DELETE` is a decision

`CASCADE` only where the child is a pure projection with no independent authority:
`client_products`, `interaction_attachments`, `task_reminders`, `team_members`.

`RESTRICT` where the child records something that happened: `interactions`, `tasks`, and every
`*_by` user reference. `RESTRICT` on `owner_manager_id` is what makes orphaned clients
impossible (CP-EC-06) — do not relax it to make a deactivation flow simpler; fix the flow.

State the reasoning in a header comment when the choice is not obvious.

## Indexes

- Prefer partial indexes wherever the predicate is always present in the query:
  `WHERE deleted_at IS NULL`, `WHERE status IN ('OPEN','IN_PROGRESS')`.
- Name the query each index serves, by SPEC identifier, in a comment above it.
- `now()` is not `IMMUTABLE` — it cannot appear in a generated column or an index predicate.
  Overdue and KYC expiry are computed at query time against a partial index (TR-BR-02).
- On a populated table use `CREATE INDEX CONCURRENTLY` in its own migration with
  `-- flyway:executeInTransaction=false`.

## Safe changes to populated tables

- Adding a `NOT NULL` column is three migrations: add nullable, backfill in batches, set
  `NOT NULL`.
- Adding a `CHECK` to existing data: `ADD CONSTRAINT … NOT VALID`, then `VALIDATE CONSTRAINT`
  in a second migration, so the first does not hold an `ACCESS EXCLUSIVE` lock while scanning.
- Any migration that rewrites a table states its expected duration and lock behaviour in a
  header comment.

## audit_log

- Monthly range partitions on `occurred_at`, created 3 months ahead by a scheduled job.
- A partition is not complete until the immutability trigger is attached **in the same
  transaction** that created it. A partition without its trigger is a silent hole in the
  append-only guarantee.
- Unique indexes on a partitioned table must include the partition key — hence
  `(occurred_at, event_id)` and `(occurred_at, chain_id, chain_seq)`.
- Retention is 7 years. Expired partitions are `DETACH`ed and archived, never `DROP`ped in
  place, and the operation is audited.
- Never write a migration that grants `UPDATE` or `DELETE` on `audit_log` to any role.

## Testing

Every migration ships with a Testcontainers test that **tries to violate** each new constraint
and asserts the failure. A constraint with no such test is untested. Seed data lives in a
separate Flyway location loaded only in the `local` and `test` profiles — never in `V*` files
that run in production.
