---
name: client-service
description: Rules for client-service — Client Profile (SPEC.md §5) and RBAC (§9). Owns clients, products, users, teams, roles, permissions, access grants and sessions.
globs:
  - "src/client-service/**"
  - "src/client-service/**/*.java"
  - "src/client-service/src/main/resources/**"
---

# client-service

Owns: `clients`, `client_products`, `users`, `teams`, `team_members`, `roles`, `permissions`,
`role_permissions`, `user_roles`, `access_grants`, `refresh_tokens`, `login_attempts`,
`outbox_events`.

Implements **Client Profile** (`SPEC.md` §5) and **RBAC** (§9). It is also the authorization
authority for the whole system — `interaction-service` asks it, never the reverse.

## Client Profile

- `external_ref` is immutable after creation (CP-BR-01). It is the join key to core banking;
  changing it silently re-points history.
- Email and tax ID are unique among non-deleted clients; **phone is not** (CP-BR-02).
  Shared household numbers are legitimate — `lookup` returns all matches for disambiguation.
- `team_id` is always derived from the owner's primary team, never set independently
  (CP-BR-03). Reassignment re-derives it in the same transaction.
- KYC transitions follow CP-BR-04 exactly. A user may never approve KYC on a client they own,
  even holding `client:kyc` — check the acting subject against `owner_manager_id` at request
  time (CP-BR-05, `SELF_APPROVAL_FORBIDDEN`).
- `last_interaction_at` and `open_task_count` are display-only denormalized counters fed by
  the `interaction.events` / `task.events` consumer (CP-BR-11). **No business decision may
  read them.** Anything authoritative queries `interaction-service`.
- Products are a read-only projection of core banking. This service never opens an account or
  changes a balance.
- `GET /clients/{id}/summary` is the card's first paint. Fan out to sub-resources in parallel
  with an 800 ms per-resource timeout, return partial failures in `degraded[]`, and keep the
  aggregate under 1.2 s.

## RBAC

- Expose authorization through the `AccessPolicy` seam. Scope resolution is RB-BR-02;
  multiple roles union their permissions and take the **widest** scope (RB-BR-03).
- A break-glass grant widens `client:read`, `client:write`, `interaction:*` and `task:*` for
  one client only. It never widens delete, merge, reassign or any audit permission
  (RB-BR-04).
- Requester and approver are always different users (RB-BR-09). The 8-hour ceiling is a DB
  constraint — do not add a service-layer path around it.
- Deactivation is refused while the user owns clients, holds open tasks or tickets, or is a
  team's sole supervisor. The `409` body enumerates every blocker with counts (RB-BR-07).
- At least one active admin must always exist (RB-BR-08).
- Refresh tokens rotate on every use and are tracked by `family_id`. Presenting a used token
  revokes the whole family and alerts (RB-BR-10).
- Login responses are constant-time and use one error code for unknown email and wrong
  password, so the endpoint cannot enumerate accounts.

## Encryption

Client email, phone, tax ID and address are AES-256-GCM at rest with an HMAC `_hash` sibling
for lookup (§4.7). **User** email is deliberately plaintext — it is corporate directory data
needed for login, notification routing and `actor_email` in the audit log. Do not "fix" this.

If the KMS is unreachable, fail the read with `503`. Never serve a partially decrypted client
card (CP-EC-13).

## Events

Emits `client.created`, `client.updated`, `client.deleted`, `client.merged`,
`client.kyc_changed`, `client.reassigned` on `client.events`, and `LOGIN_*`, `LOGOUT`,
`ROLE_CHANGE`, `ACCESS_GRANT`, `ACCESS_REVOKE` on `auth.events`. Always through the outbox,
always with sensitive values masked (AR-01).

Bulk operations emit **one event per entity** with a shared `correlation_id`, never one event
for the batch (AT-EC-11).
