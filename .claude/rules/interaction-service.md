---
name: interaction-service
description: Rules for interaction-service — Interaction Log (SPEC.md §6) and Task/Reminder (§7). Owns interactions, attachments, tickets, tasks, reminders and task history.
globs:
  - "src/interaction-service/**"
  - "src/interaction-service/**/*.java"
  - "src/interaction-service/src/main/resources/**"
---

# interaction-service

Owns: `interactions`, `interaction_attachments`, `tasks`, `task_reminders`, `task_history`,
`outbox_events`.

Implements **Interaction Log** (`SPEC.md` §6) and **Task/Reminder** (§7). It holds no client
or user table — it stores `client_id` and `author_id` as plain UUIDs and resolves display
names through `client-service`. Do not add a foreign key across the service boundary in
production config; the `REFERENCES` clauses in the spec's DDL apply to the shared-database
development setup only.

## Interactions

- An interaction is **immutable in substance**. `type`, `client_id`, `occurred_at` and
  `author_id` never change (IL-BR-01). Only `subject` and `body`, only by the author, only
  within 15 minutes of `created_at` — not `occurred_at` (IL-BR-02).
- After the window the only remedy is a correction: a new row with `corrects_id` set. Never
  rewrite in place (IL-BR-03). Correction chains are one level deep (IL-BR-05).
- `subject` is plaintext and searchable; `body` is AES-256-GCM encrypted. There is no
  full-text search over bodies — this is a deliberate trade-off (§4.7), not a gap to fill.
- Run the PAN detector (Luhn + length) on `body` **before** encryption and mask matches to the
  last 4 digits, warning the caller. An unmasked card number must never reach storage
  (IL-EC-05).
- `visibility = PRIVATE` is readable by the author and admins only. **Supervisors do not see
  private notes** (IL-BR-09) — if that feels wrong, it is a product decision, not a bug.
- Timeline reads return a 160-character `bodyPreview` and emit **no** audit event. Only
  opening a full body or downloading an attachment emits `READ_SENSITIVE` (IL-BR-11).
- Imports dedupe on `(source, external_ref)` and return `200` with the existing row, not `201`
  (IL-BR-12).

## Tickets

- `sla_due_at` is derived from priority at creation and frozen. Raising priority later
  **recomputes it from the original `occurred_at`**, which may make the ticket instantly
  breached — that is intentional (IL-BR-07, IL-EC-09).
- `WAITING_CLIENT` pauses the SLA clock; the paused duration is recorded and excluded from
  breach calculation. A separate staleness rule flags `WAITING_CLIENT` beyond 14 days so a
  paused clock cannot become a hiding place (IL-EC-10).
- Transitions follow IL-BR-08. The UI disables illegal targets, but the service still
  validates — never rely on the client for state-machine correctness.

## Tasks

- Every task belongs to exactly one client, `NOT NULL` and immutable (TR-BR-01). This is what
  lets task authorization derive from client scope with no second mechanism.
- **Overdue is computed, never stored**: `due_at < now() AND status IN ('OPEN','IN_PROGRESS')`,
  served by a partial index. `now()` is not `IMMUTABLE`, so a generated column is impossible —
  do not try to materialize it (TR-BR-02).
- `due_at` moves forward only, relative to `original_due_at` (TR-BR-04). Pulling a deadline
  earlier erases the record of the original commitment; create a new task instead.
- Completing an **overdue** task requires a note; on-time completion does not (TR-BR-05).
- Snooze: assignee only, 3 maximum, 30 days each, reason required (TR-BR-06).
- Escalation runs every 15 minutes over the partial index: 24 h → level 1, 72 h → level 2,
  168 h → level 3. `escalation_level` never decreases while the task is open (TR-BR-08).
- The notifier re-checks task status immediately before sending a reminder. Being scheduled is
  never authorization to send (TR-EC-14).
- `task_history` is a **product feature** (the "who moved this?" timeline in the drawer). It
  duplicates part of the audit trail on purpose — different retention, different access,
  different owner. Do not try to unify them.

## Transactions that must not split

- `POST /interactions` with `createTask` writes both in one transaction. If the task fails,
  the interaction rolls back (IL-BR-06) — a manager who thought they set a reminder must never
  end up with only a note.
- `POST /tasks/{id}/complete` with `logInteraction: true` is likewise atomic.

## Events

Emits `interaction.*` and `ticket.status_changed` on `interaction.events`, and `task.*` on
`task.events`. Always through the outbox; `subject` and `body` masked in `changedFields`.
