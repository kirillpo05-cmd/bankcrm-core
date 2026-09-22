package com.client360.common.security;

import java.util.Set;

/**
 * Every permission code in the SPEC.md §9.2.8 matrix. Code authorizes on these plus a scope, never
 * on a role name (RB-BR-01, CLAUDE.md rule 5).
 *
 * <p>There is no {@code audit:delete}, and there must never be one (AT-BR-11).
 */
public final class Permissions {

    public static final String CLIENT_READ = "client:read";
    public static final String CLIENT_WRITE = "client:write";
    public static final String CLIENT_DELETE = "client:delete";
    public static final String CLIENT_REASSIGN = "client:reassign";
    public static final String CLIENT_MERGE = "client:merge";
    public static final String CLIENT_KYC = "client:kyc";
    public static final String CLIENT_ERASE = "client:erase";
    public static final String PRODUCT_READ = "product:read";
    public static final String PRODUCT_WRITE = "product:write";
    public static final String PRODUCT_SYNC = "product:sync";
    public static final String INTERACTION_READ = "interaction:read";
    public static final String INTERACTION_WRITE = "interaction:write";
    public static final String INTERACTION_DELETE = "interaction:delete";
    public static final String TICKET_READ = "ticket:read";
    public static final String TICKET_WRITE = "ticket:write";
    public static final String TASK_READ = "task:read";
    public static final String TASK_WRITE = "task:write";
    public static final String TASK_REASSIGN = "task:reassign";
    public static final String DASHBOARD_TEAM = "dashboard:team";
    public static final String AUDIT_READ = "audit:read";
    public static final String AUDIT_EXPORT = "audit:export";
    public static final String AUDIT_VERIFY = "audit:verify";
    public static final String USER_READ = "user:read";
    public static final String USER_WRITE = "user:write";
    public static final String ROLE_ASSIGN = "role:assign";
    public static final String TEAM_MANAGE = "team:manage";
    public static final String ACCESS_REQUEST = "access:request";
    public static final String ACCESS_APPROVE = "access:approve";

    public static final Set<String> ALL = Set.of(
            CLIENT_READ,
            CLIENT_WRITE,
            CLIENT_DELETE,
            CLIENT_REASSIGN,
            CLIENT_MERGE,
            CLIENT_KYC,
            CLIENT_ERASE,
            PRODUCT_READ,
            PRODUCT_WRITE,
            PRODUCT_SYNC,
            INTERACTION_READ,
            INTERACTION_WRITE,
            INTERACTION_DELETE,
            TICKET_READ,
            TICKET_WRITE,
            TASK_READ,
            TASK_WRITE,
            TASK_REASSIGN,
            DASHBOARD_TEAM,
            AUDIT_READ,
            AUDIT_EXPORT,
            AUDIT_VERIFY,
            USER_READ,
            USER_WRITE,
            ROLE_ASSIGN,
            TEAM_MANAGE,
            ACCESS_REQUEST,
            ACCESS_APPROVE);

    /** The MANAGER column of §9.2.8 — every code the role holds, at any scope. */
    public static final Set<String> MANAGER = Set.of(
            CLIENT_READ,
            CLIENT_WRITE,
            PRODUCT_READ,
            PRODUCT_SYNC,
            INTERACTION_READ,
            INTERACTION_WRITE,
            TICKET_READ,
            TICKET_WRITE,
            TASK_READ,
            TASK_WRITE,
            ACCESS_REQUEST);

    private Permissions() {}
}
