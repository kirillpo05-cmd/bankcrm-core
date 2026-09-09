-- Client360 / client-service — V1: teams and users.
--
-- SPEC.md §9.2.2 (teams), §9.2.3 (users).
--
-- Scope note: RBAC ships in v2 (SPEC.md §11.1), but `teams` and `users` are FK
-- targets for `clients` in the MVP, so they are created in full here rather than
-- created thin and altered later. The remaining RBAC tables — roles, permissions,
-- role_permissions, user_roles, team_members, access_grants, refresh_tokens,
-- login_attempts — arrive with v2.

SET LOCAL search_path = client, public;

-- ----------------------------------------------------------------- enums
CREATE TYPE client.user_status AS ENUM ('ACTIVE', 'SUSPENDED', 'DEACTIVATED');

-- ----------------------------------------------------------------- teams
CREATE TABLE client.teams (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(120) NOT NULL,
    code           VARCHAR(32)  NOT NULL,
    supervisor_id  UUID,                                  -- FK added after users
    parent_team_id UUID,
    -- Business-day SLA arithmetic uses the team's timezone, not the server's
    -- and not the viewer's (TR-BR-14).
    timezone       VARCHAR(64)  NOT NULL DEFAULT 'Europe/Warsaw',
    active         BOOLEAN      NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT uq_teams_code UNIQUE (code),
    CONSTRAINT fk_teams_parent FOREIGN KEY (parent_team_id)
        REFERENCES client.teams (id) ON DELETE RESTRICT,
    CONSTRAINT ck_teams_no_self_parent CHECK (parent_team_id IS NULL OR parent_team_id <> id)
);

COMMENT ON TABLE client.teams IS
    'Supervisor + managers. The unit of the TEAM data scope (RB-BR-02).';

-- ----------------------------------------------------------------- users
CREATE TABLE client.users (
    id                   UUID               PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_no          VARCHAR(32)        NOT NULL,
    -- Deliberately NOT encrypted, unlike client email: corporate directory data
    -- needed for login, notification routing and audit actor_email (§9.2.3).
    email                VARCHAR(255)       NOT NULL,
    full_name            VARCHAR(200)       NOT NULL,
    password_hash        VARCHAR(255)       NOT NULL,      -- bcrypt, cost 12
    status               client.user_status NOT NULL DEFAULT 'ACTIVE',
    primary_team_id      UUID,
    failed_login_count   SMALLINT           NOT NULL DEFAULT 0,
    locked_until         TIMESTAMPTZ,
    password_changed_at  TIMESTAMPTZ        NOT NULL DEFAULT now(),
    must_change_password BOOLEAN            NOT NULL DEFAULT false,
    last_login_at        TIMESTAMPTZ,
    deactivated_at       TIMESTAMPTZ,
    deactivated_by       UUID,
    created_at           TIMESTAMPTZ        NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ        NOT NULL DEFAULT now(),
    version              INTEGER            NOT NULL DEFAULT 0,

    CONSTRAINT uq_users_employee_no UNIQUE (employee_no),
    CONSTRAINT uq_users_email       UNIQUE (email),
    CONSTRAINT fk_users_team FOREIGN KEY (primary_team_id)
        REFERENCES client.teams (id) ON DELETE RESTRICT,
    CONSTRAINT ck_users_failed_count CHECK (failed_login_count BETWEEN 0 AND 100),
    CONSTRAINT ck_users_email_shape  CHECK (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    CONSTRAINT ck_users_deactivated_pair
        CHECK ((status = 'DEACTIVATED') = (deactivated_at IS NOT NULL))
);

COMMENT ON TABLE client.users IS
    'Bank employees. Never hard-deleted — deactivation preserves author_id and '
    'created_by references across every module (RB-BR-15).';

-- Deferred: teams.supervisor_id -> users.id closes the circular reference.
ALTER TABLE client.teams
    ADD CONSTRAINT fk_teams_supervisor FOREIGN KEY (supervisor_id)
        REFERENCES client.users (id) ON DELETE RESTRICT;

-- ---------------------------------------------------------------- indexes
CREATE INDEX ix_users_team   ON client.users (primary_team_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_users_status ON client.users (status);
