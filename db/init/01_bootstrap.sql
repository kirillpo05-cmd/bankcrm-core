-- Client360 — database bootstrap.
-- Runs once, on an empty data directory, via the postgres image entrypoint.
-- Owns everything that is database-wide and therefore not any single service's
-- migration to make: extensions, schemas, and the restricted application role.

-- ---------------------------------------------------------------- extensions
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid(), digest(), hmac()
CREATE EXTENSION IF NOT EXISTS pg_trgm;    -- trigram name/subject search

-- ------------------------------------------------------------------- schemas
-- One schema per service (SPEC.md §3.1). In production these are separate
-- database instances; locally they share one so a single compose file works.
CREATE SCHEMA IF NOT EXISTS client;
CREATE SCHEMA IF NOT EXISTS interaction;
CREATE SCHEMA IF NOT EXISTS audit;

COMMENT ON SCHEMA client      IS 'client-service: clients, products, RBAC';
COMMENT ON SCHEMA interaction IS 'interaction-service: interactions, attachments, tasks';
COMMENT ON SCHEMA audit       IS 'audit-service: append-only audit_log (v2)';

-- ------------------------------------------------------- application role
-- SPEC.md §8.2.3 revokes UPDATE/DELETE on audit_log from this role. The role
-- must therefore exist before the audit migrations run.
-- Local-development credential only; production uses IAM/vault-issued auth.
DO $bootstrap$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'client360_app') THEN
        CREATE ROLE client360_app LOGIN PASSWORD 'client360_app_local_only';
    END IF;
END
$bootstrap$;

GRANT USAGE ON SCHEMA client, interaction, audit TO client360_app;

-- Tables created later by Flyway inherit these defaults.
ALTER DEFAULT PRIVILEGES IN SCHEMA client, interaction
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO client360_app;

-- audit is append-only: no UPDATE, no DELETE, ever (AT-BR-01).
ALTER DEFAULT PRIVILEGES IN SCHEMA audit
    GRANT SELECT, INSERT ON TABLES TO client360_app;

-- ---------------------------------------------------------------- session
ALTER DATABASE client360 SET timezone TO 'UTC';
