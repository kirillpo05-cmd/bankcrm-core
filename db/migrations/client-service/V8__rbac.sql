-- Client360 / client-service — V8: RBAC.
--
-- SPEC.md §9.2. Every endpoint in every service has authorized through the AccessPolicy seam since
-- the first one was written (§11.1's sequencing note), against a stub that answers MANAGER at ALL
-- scope for everybody. These tables are what the seam was waiting for: the real implementation
-- reads them, and no controller changes.
--
-- The point of having built it that way round is visible here — retrofitting scope filters into
-- every query now would have been the most expensive possible ordering, and instead this migration
-- turns on checks that were already written and already tested against a fixture matrix.

SET LOCAL search_path = client, public;

CREATE TYPE client.access_scope AS ENUM ('OWN', 'TEAM', 'ALL');

-- ---------------------------------------------------------- team_members
-- A user's primary team is on `users`; this carries the additional memberships a supervisor
-- covering two branches needs (§9.2.4). RB-BR-02's TEAM scope reads both.
CREATE TABLE client.team_members (
    team_id    UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    is_primary BOOLEAN     NOT NULL DEFAULT false,
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at    TIMESTAMPTZ,

    CONSTRAINT pk_team_members PRIMARY KEY (team_id, user_id),
    CONSTRAINT fk_tm_team FOREIGN KEY (team_id) REFERENCES client.teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_tm_user FOREIGN KEY (user_id) REFERENCES client.users (id) ON DELETE CASCADE,
    CONSTRAINT ck_tm_left_after CHECK (left_at IS NULL OR left_at > joined_at)
);

-- CASCADE here and not RESTRICT: a membership is a pure projection of "who is on this team",
-- with no independent authority. It records no event, so losing it with its team loses nothing.

-- One primary team at a time. A second would make CP-BR-03's "derive team_id from the owner"
-- ambiguous, and an ambiguous owner team is a client that could land in two feeds.
CREATE UNIQUE INDEX ux_tm_one_primary ON client.team_members (user_id)
    WHERE is_primary AND left_at IS NULL;
CREATE INDEX ix_tm_active ON client.team_members (user_id) WHERE left_at IS NULL;

-- ------------------------------------------------------ roles, permissions
CREATE TABLE client.roles (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(32) NOT NULL,
    name        VARCHAR(80) NOT NULL,
    description TEXT,
    -- A system role is one the matrix of §9.2.8 depends on; deleting one would leave users
    -- holding a role that no longer grants anything, which reads as "access revoked" to nobody.
    is_system   BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_roles_code UNIQUE (code)
);

CREATE TABLE client.permissions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(64) NOT NULL,
    resource    VARCHAR(32) NOT NULL,
    action      VARCHAR(32) NOT NULL,
    description TEXT,

    CONSTRAINT uq_permissions_code UNIQUE (code),
    CONSTRAINT uq_permissions_pair UNIQUE (resource, action),
    -- The code is not a separate fact from the pair, and a row where they disagree would make
    -- `client:read` mean one thing to a query and another to a human reading the table.
    CONSTRAINT ck_permissions_code_shape CHECK (code = resource || ':' || action)
);

CREATE TABLE client.role_permissions (
    role_id       UUID                NOT NULL,
    permission_id UUID                NOT NULL,
    scope         client.access_scope NOT NULL DEFAULT 'OWN',

    CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES client.roles (id) ON DELETE CASCADE,
    -- RESTRICT on the permission: deleting a permission code that roles still grant would
    -- silently widen or narrow what those roles mean. Retire the grant first.
    CONSTRAINT fk_rp_perm FOREIGN KEY (permission_id) REFERENCES client.permissions (id) ON DELETE RESTRICT
);

CREATE TABLE client.user_roles (
    user_id    UUID        NOT NULL,
    role_id    UUID        NOT NULL,
    granted_by UUID        NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULL is permanent. A temporary role is how cover for a holiday is granted without anyone
    -- having to remember to take it away.
    expires_at TIMESTAMPTZ,
    reason     TEXT        NOT NULL,

    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES client.users (id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES client.roles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ur_granter FOREIGN KEY (granted_by) REFERENCES client.users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_ur_expiry CHECK (expires_at IS NULL OR expires_at > granted_at),
    -- Who granted this and why is the question an auditor asks first.
    CONSTRAINT ck_ur_reason CHECK (length(btrim(reason)) >= 10)
);

-- No partial index on expiry: a predicate must be IMMUTABLE and now() is not (the same rule that
-- shapes TR-BR-02's overdue). Expiry is filtered at query time against this index.
CREATE INDEX ix_user_roles_user ON client.user_roles (user_id, expires_at);

-- ------------------------------------------------------------ access_grants
-- Break-glass (RB-US-05): a manager covering an urgent call for an absent colleague, widened to
-- one client and nothing else (RB-BR-04).
CREATE TABLE client.access_grants (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL,
    client_id    UUID        NOT NULL,
    reason       TEXT        NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by  UUID,
    approved_at  TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    revoked_by   UUID,
    use_count    INTEGER     NOT NULL DEFAULT 0,

    CONSTRAINT fk_ag_user FOREIGN KEY (user_id) REFERENCES client.users (id) ON DELETE CASCADE,
    CONSTRAINT fk_ag_client FOREIGN KEY (client_id) REFERENCES client.clients (id) ON DELETE CASCADE,
    CONSTRAINT fk_ag_approver FOREIGN KEY (approved_by) REFERENCES client.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ag_revoker FOREIGN KEY (revoked_by) REFERENCES client.users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_ag_reason CHECK (length(btrim(reason)) >= 20),
    -- The 8-hour ceiling is here and not in a service method on purpose: a break-glass grant that
    -- could be made permanent is not break-glass, and a constraint is the only version of that
    -- rule no future code path can talk its way around.
    CONSTRAINT ck_ag_window CHECK (expires_at > requested_at
                                   AND expires_at <= requested_at + INTERVAL '8 hours'),
    CONSTRAINT ck_ag_approved_pair CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CONSTRAINT ck_ag_revoked_pair CHECK ((revoked_by IS NULL) = (revoked_at IS NULL)),
    -- RB-BR-09: requester and approver are never the same person.
    CONSTRAINT ck_ag_separation CHECK (approved_by IS NULL OR approved_by <> user_id),
    CONSTRAINT ck_ag_use_count CHECK (use_count >= 0)
);

CREATE INDEX ix_ag_active ON client.access_grants (user_id, client_id)
    WHERE approved_at IS NOT NULL AND revoked_at IS NULL;

-- ------------------------------------------------ refresh_tokens, login_attempts
CREATE TABLE client.refresh_tokens (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- SHA-256 of the token. The raw value is never stored: a stolen database must not become a
    -- set of working sessions.
    token_hash     BYTEA        NOT NULL,
    user_id        UUID         NOT NULL,
    -- Rotation lineage. Presenting a token that was already used revokes the whole family
    -- (RB-BR-10), because the only way two holders exist is that one of them stole it.
    family_id      UUID         NOT NULL,
    issued_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ  NOT NULL,
    used_at        TIMESTAMPTZ,
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(64),
    replaced_by    UUID,
    ip             INET,
    user_agent     VARCHAR(512),

    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_rt_user FOREIGN KEY (user_id) REFERENCES client.users (id) ON DELETE CASCADE,
    CONSTRAINT fk_rt_replaced FOREIGN KEY (replaced_by) REFERENCES client.refresh_tokens (id) ON DELETE SET NULL,
    CONSTRAINT ck_rt_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_rt_revoked_pair CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);

CREATE INDEX ix_rt_user_active ON client.refresh_tokens (user_id)
    WHERE revoked_at IS NULL AND used_at IS NULL;
CREATE INDEX ix_rt_family ON client.refresh_tokens (family_id);

-- Hot operational data for lockout decisions, 90-day retention. The durable record of a login is
-- the LOGIN_SUCCESS / LOGIN_FAILURE audit event, which is kept for seven years — this table is
-- allowed to be pruned precisely because it is not the evidence.
CREATE TABLE client.login_attempts (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email          VARCHAR(255) NOT NULL,
    -- NULL when the email is unknown. Recorded anyway: the pattern of attempts against addresses
    -- that do not exist is itself worth seeing.
    user_id        UUID,
    success        BOOLEAN      NOT NULL,
    failure_reason VARCHAR(48),
    ip             INET         NOT NULL,
    user_agent     VARCHAR(512),
    attempted_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_la_reason CHECK (success = (failure_reason IS NULL))
);

-- No FK to users: an attempt against an unknown address has no user to point at, and the table
-- must keep working while an account is being created or removed.

CREATE INDEX ix_la_email_time ON client.login_attempts (email, attempted_at DESC);
CREATE INDEX ix_la_ip_time ON client.login_attempts (ip, attempted_at DESC);
