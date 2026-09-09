---
name: audit-service
description: Rules for audit-service — Audit Trail (SPEC.md §8). Kafka consumer plus read-only query API over an append-only, hash-chained, partitioned audit_log.
globs:
  - "src/audit-service/**"
  - "src/audit-service/**/*.java"
  - "src/audit-service/src/main/resources/**"
---

# audit-service

Owns: `audit_log` (monthly range partitions), `audit_chain_head`, `audit_export_jobs`,
`audit_consumer_state`.

Implements **Audit Trail** (`SPEC.md` §8). This service is the compliance evidence store. Its
correctness matters more than its throughput, and its integrity matters more than both.

## The two rules that define this service

1. **It is a consumer only.** There is no ingestion endpoint, and you must not add one. Audit
   entries are created solely by consuming Kafka, so a compromised application service cannot
   forge an entry without also compromising the broker (AT-BR-02).
2. **Nothing updates or deletes an audit row.** Not an endpoint, not a role, not a repair
   script. `audit:delete` does not exist as a permission code. Enforced by revoked privileges
   *and* a statement-level trigger on every partition (AT-BR-01).

If a task seems to require violating either, the task is wrong. Say so.

## Ingestion

- Idempotent by the unique index on `(occurred_at, event_id)`. Catch the unique violation,
  commit the offset, continue. At-least-once delivery yields exactly-once storage (AT-BR-03).
- **Never skip an unprocessable event past the offset.** Five attempts, then the DLT plus an
  alert, and the offset stops there pending an operator decision (AT-BR-08). A silently
  dropped audit event is the exact failure this service exists to prevent.
- Store both `occurred_at` (producer) and `recorded_at` (consumer). Their difference is
  pipeline lag, exposed per row as `lagMs`, so a delayed audit is visible as delayed rather
  than silently back-dated (AT-BR-07).
- Reject events more than one hour back-dated to the DLT with `CLOCK_SKEW_SUSPECTED`
  (AT-EC-04). Backfill after a long outage runs through an operator-approved path that stamps
  `context.backfill: true`.
- If a partition is missing, create it **with its immutability trigger in the same
  transaction**, then retry once (AT-EC-13).

## Hash chain

- One chain per Kafka partition (`chain_id`), not one global chain. A global chain forces a
  single-threaded consumer and makes audit the throughput ceiling of the whole system.
- `row_hash` covers `prev_hash`, so modifying or deleting any row breaks every subsequent hash
  in its chain. `changed_fields` is hashed as canonical JSON — sorted keys, no whitespace.
  A non-deterministic serializer silently invalidates the chain; pin it.
- Seal the head into `audit_chain_head` every 1 000 rows and mirror nightly to external WORM
  storage. Local recomputation alone must not defeat detection (AT-BR-06).
- `POST /audit/verify` returns **`200` with `verified: false`** on a break. The request
  succeeded; the data did not. Never map a detected break to a `500`, and never conflate
  "verification failed" with "verification could not run" (S-AT-03).

## Storage

- Never store sensitive values, even encrypted. `changed_fields` records that a field changed,
  not its value (AT-BR-04). This is what makes GDPR erasure and 7-year retention compatible —
  an erased client leaves audit rows holding only a UUID (AT-EC-08).
- Monthly partitions, created 3 months ahead by a scheduled job. Expired partitions are
  **detached and archived**, never dropped in place, and the archival is itself audited
  (AT-BR-09).
- `audit_chain_head` is never detached, so ingestion never depends on reading an archived
  partition (AT-EC-03).

## Query API

- Refuse an unfiltered query with `400`. At least one of `clientId`, `actorId`, `entityId`, or
  a range under 90 days is required — a 7-year scan is not a valid request (AT-EC-14).
- Keyset pagination only. Build filters from a **whitelist** of sortable and filterable
  columns; no dynamic SQL string building anywhere in this service.
- Managers have no audit access at any scope. Supervisors are limited to `TEAM` on clients and
  their own team's actors. Auditors are read-only `ALL` (AT-BR-11).
- Reading the audit log is itself audited (AT-BR-10). Watching the watchers is not optional.
- Exports stream server-side with a 500-row fetch size straight to object storage. Never
  materialize an export in memory (AT-EC-09).

## Availability

This service being down must never block a mutation elsewhere. Outbox rows accumulate, Kafka
retains 7 days, the consumer catches up on restart, and lag alerts fire throughout (AT-EC-05).
Report the true gap through `GET /audit/health` rather than degrading quietly.
