-- Client360 / interaction-service — V6: denormalized client ownership on interactions.
--
-- SPEC.md §6.3 IL-US-07 (cross-client feed) and §6.3 GET /tickets. Both are scope-filtered
-- queries across many clients, and this service owns no client table, so until now neither
-- could be answered: "every interaction on a client my team owns" needs ownership here.
--
-- CLAUDE.md names the two sanctioned ways data crosses a service boundary — REST, or
-- denormalized fields on Kafka events. REST is wrong for this one: the alternative was a
-- per-query round trip for an unbounded set of client ids, on the hot path of a feed. So
-- these two columns are the denormalized copy, and they are maintained from both ends:
--
--   * At write time. Every interaction write already asks client-service to authorize it, and
--     that answer carries ownerManagerId and teamId — so a new row is stamped from the same
--     call, with no extra hop and no window where it is unstamped.
--   * On `client.reassigned`. A reassignment moves a client between managers and teams, and
--     the consumer rewrites every interaction of that client. Without it the copy would rot
--     the first time somebody went on holiday (CP-US-05).
--
-- They are display-and-filter data, exactly like the counters CP-BR-11 puts on the client card:
-- NO AUTHORIZATION DECISION MAY READ THEM. A decision still asks client-service, which owns the
-- truth. What these serve is "which rows might this caller be interested in", narrowed before
-- the database has to look at everything.

SET LOCAL search_path = interaction, public;

ALTER TABLE interaction.interactions
    ADD COLUMN client_owner_id UUID,
    ADD COLUMN client_team_id  UUID;

COMMENT ON COLUMN interaction.interactions.client_owner_id IS
    'Denormalized from client.clients.owner_manager_id, kept current by the client.events '
    'consumer. Filtering only — never an authorization decision (see V6 header).';

COMMENT ON COLUMN interaction.interactions.client_team_id IS
    'Denormalized from client.clients.team_id. Filtering only — never an authorization decision.';

-- Nullable on purpose, and nullable for good. A row whose client this service has not yet heard
-- about is a row a team feed must not claim to have filtered correctly, and NULL says that
-- plainly where a zero-UUID default would have silently meant "belongs to nobody".

-- The supervisor's cross-client feed (IL-US-07) and the team ticket view. Ordered to match the
-- feed's own keyset, so the index answers the filter and the sort together.
CREATE INDEX ix_int_team_time ON interaction.interactions (client_team_id, occurred_at DESC)
    WHERE deleted_at IS NULL;

-- Backfill for the shared development database, where client.clients is reachable from here.
-- Deliberately guarded rather than assumed: in a deployed environment the two services own
-- separate databases (see the V1 header), this block is a no-op, and the backfill is an
-- operational replay of client.events instead. A migration that assumed the other schema was
-- present would fail the first deployment that took the architecture seriously.
DO $$
BEGIN
    IF to_regclass('client.clients') IS NOT NULL THEN
        UPDATE interaction.interactions i
           SET client_owner_id = c.owner_manager_id,
               client_team_id  = c.team_id
          FROM client.clients c
         WHERE c.id = i.client_id;
    END IF;
END
$$;
