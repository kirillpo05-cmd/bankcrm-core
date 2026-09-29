-- Client360 / client-service — V9: the permission matrix.
--
-- SPEC.md §9.2.8, transcribed mechanically rather than by hand: the table in the spec was parsed
-- and every code cross-checked against com.client360.common.security.Permissions, in both
-- directions, so a permission the application asks for but the matrix never grants — or the
-- reverse — could not slip through. 28 permissions, 5 roles, 75 grants.
--
-- This is system configuration, not sample data, so it belongs in a versioned migration and not
-- in db/seed: every scope decision in every service reads these rows, and an environment without
-- them authorizes nothing.
--
-- Three properties the matrix enforces deliberately (§9.2.8):
--
--   * No role can delete audit data. `audit:delete` does not exist as a code, anywhere.
--   * Admins cannot read client PII casually — `client:read` at ALL still emits READ_SENSITIVE,
--     so an admin browsing customer records is as visible in the log as a manager.
--   * Auditors hold no `:write` of any kind, which is what makes the role safe to grant widely.

SET LOCAL search_path = client, public;

-- ------------------------------------------------------------------- roles
INSERT INTO client.roles (code, name, description, is_system)
VALUES ('MANAGER', 'Relationship manager', 'Owns a book of clients; reads and writes only their own.', true);
INSERT INTO client.roles (code, name, description, is_system)
VALUES ('SUPERVISOR', 'Team supervisor', 'Covers a team: everything their managers can do, across the team, plus reassignment and approval.', true);
INSERT INTO client.roles (code, name, description, is_system)
VALUES ('ADMIN', 'Administrator', 'Full access, including client lifecycle and user administration. Cannot delete an audit row — no role can.', true);
INSERT INTO client.roles (code, name, description, is_system)
VALUES ('AUDITOR', 'Auditor', 'Read-only, everywhere, including the audit log. Holds no :write permission of any kind, which is what makes the role safe to grant broadly.', true);
INSERT INTO client.roles (code, name, description, is_system)
VALUES ('COMPLIANCE', 'Compliance officer', 'Read-only on client data plus the KYC and erasure authority the role exists for.', true);

-- ------------------------------------------------------------- permissions
-- resource and action are split so the code can be a CHECK over them rather than a third
-- independent fact that could disagree with the pair.
INSERT INTO client.permissions (code, resource, action) VALUES
    ('client:read', 'client', 'read'),
    ('client:write', 'client', 'write'),
    ('client:delete', 'client', 'delete'),
    ('client:reassign', 'client', 'reassign'),
    ('client:merge', 'client', 'merge'),
    ('client:kyc', 'client', 'kyc'),
    ('client:erase', 'client', 'erase'),
    ('product:read', 'product', 'read'),
    ('product:write', 'product', 'write'),
    ('product:sync', 'product', 'sync'),
    ('interaction:read', 'interaction', 'read'),
    ('interaction:write', 'interaction', 'write'),
    ('interaction:delete', 'interaction', 'delete'),
    ('ticket:read', 'ticket', 'read'),
    ('ticket:write', 'ticket', 'write'),
    ('task:read', 'task', 'read'),
    ('task:write', 'task', 'write'),
    ('task:reassign', 'task', 'reassign'),
    ('dashboard:team', 'dashboard', 'team'),
    ('audit:read', 'audit', 'read'),
    ('audit:export', 'audit', 'export'),
    ('audit:verify', 'audit', 'verify'),
    ('user:read', 'user', 'read'),
    ('user:write', 'user', 'write'),
    ('role:assign', 'role', 'assign'),
    ('team:manage', 'team', 'manage'),
    ('access:request', 'access', 'request'),
    ('access:approve', 'access', 'approve');

-- -------------------------------------------------------- role_permissions
-- Written as a join against the two tables above rather than with literal ids, so this cannot be
-- copied into an environment where the generated UUIDs differ.
INSERT INTO client.role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, CAST(g.scope AS client.access_scope)
  FROM (VALUES
    ('MANAGER', 'client:read', 'OWN'),
    ('SUPERVISOR', 'client:read', 'TEAM'),
    ('ADMIN', 'client:read', 'ALL'),
    ('AUDITOR', 'client:read', 'ALL'),
    ('COMPLIANCE', 'client:read', 'ALL'),
    ('MANAGER', 'client:write', 'OWN'),
    ('SUPERVISOR', 'client:write', 'TEAM'),
    ('ADMIN', 'client:write', 'ALL'),
    ('ADMIN', 'client:delete', 'ALL'),
    ('SUPERVISOR', 'client:reassign', 'TEAM'),
    ('ADMIN', 'client:reassign', 'ALL'),
    ('ADMIN', 'client:merge', 'ALL'),
    ('SUPERVISOR', 'client:kyc', 'TEAM'),
    ('ADMIN', 'client:kyc', 'ALL'),
    ('COMPLIANCE', 'client:kyc', 'ALL'),
    ('ADMIN', 'client:erase', 'ALL'),
    ('COMPLIANCE', 'client:erase', 'ALL'),
    ('MANAGER', 'product:read', 'OWN'),
    ('SUPERVISOR', 'product:read', 'TEAM'),
    ('ADMIN', 'product:read', 'ALL'),
    ('AUDITOR', 'product:read', 'ALL'),
    ('COMPLIANCE', 'product:read', 'ALL'),
    ('ADMIN', 'product:write', 'ALL'),
    ('MANAGER', 'product:sync', 'OWN'),
    ('SUPERVISOR', 'product:sync', 'TEAM'),
    ('ADMIN', 'product:sync', 'ALL'),
    ('MANAGER', 'interaction:read', 'OWN'),
    ('SUPERVISOR', 'interaction:read', 'TEAM'),
    ('ADMIN', 'interaction:read', 'ALL'),
    ('AUDITOR', 'interaction:read', 'ALL'),
    ('COMPLIANCE', 'interaction:read', 'ALL'),
    ('MANAGER', 'interaction:write', 'OWN'),
    ('SUPERVISOR', 'interaction:write', 'TEAM'),
    ('ADMIN', 'interaction:write', 'ALL'),
    ('SUPERVISOR', 'interaction:delete', 'TEAM'),
    ('ADMIN', 'interaction:delete', 'ALL'),
    ('MANAGER', 'ticket:read', 'OWN'),
    ('SUPERVISOR', 'ticket:read', 'TEAM'),
    ('ADMIN', 'ticket:read', 'ALL'),
    ('AUDITOR', 'ticket:read', 'ALL'),
    ('COMPLIANCE', 'ticket:read', 'ALL'),
    ('MANAGER', 'ticket:write', 'OWN'),
    ('SUPERVISOR', 'ticket:write', 'TEAM'),
    ('ADMIN', 'ticket:write', 'ALL'),
    ('MANAGER', 'task:read', 'OWN'),
    ('SUPERVISOR', 'task:read', 'TEAM'),
    ('ADMIN', 'task:read', 'ALL'),
    ('AUDITOR', 'task:read', 'ALL'),
    ('COMPLIANCE', 'task:read', 'ALL'),
    ('MANAGER', 'task:write', 'OWN'),
    ('SUPERVISOR', 'task:write', 'TEAM'),
    ('ADMIN', 'task:write', 'ALL'),
    ('SUPERVISOR', 'task:reassign', 'TEAM'),
    ('ADMIN', 'task:reassign', 'ALL'),
    ('SUPERVISOR', 'dashboard:team', 'TEAM'),
    ('ADMIN', 'dashboard:team', 'ALL'),
    ('SUPERVISOR', 'audit:read', 'TEAM'),
    ('ADMIN', 'audit:read', 'ALL'),
    ('AUDITOR', 'audit:read', 'ALL'),
    ('COMPLIANCE', 'audit:read', 'ALL'),
    ('ADMIN', 'audit:export', 'ALL'),
    ('AUDITOR', 'audit:export', 'ALL'),
    ('COMPLIANCE', 'audit:export', 'ALL'),
    ('ADMIN', 'audit:verify', 'ALL'),
    ('SUPERVISOR', 'user:read', 'TEAM'),
    ('ADMIN', 'user:read', 'ALL'),
    ('AUDITOR', 'user:read', 'ALL'),
    ('ADMIN', 'user:write', 'ALL'),
    ('ADMIN', 'role:assign', 'ALL'),
    ('ADMIN', 'team:manage', 'ALL'),
    ('MANAGER', 'access:request', 'OWN'),
    ('SUPERVISOR', 'access:request', 'TEAM'),
    ('ADMIN', 'access:request', 'ALL'),
    ('SUPERVISOR', 'access:approve', 'TEAM'),
    ('ADMIN', 'access:approve', 'ALL')
       ) AS g(role_code, permission_code, scope)
  JOIN client.roles r ON r.code = g.role_code
  JOIN client.permissions p ON p.code = g.permission_code;
