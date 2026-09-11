package com.client360.common.security;

/**
 * The data-visibility qualifier on a permission (SPEC.md §2, RB-BR-02). Declared narrowest to
 * widest so {@link #widest} can compare ordinals.
 */
public enum Scope {
    /** Clients the user owns: {@code clients.owner_manager_id = :userId}. */
    OWN,
    /** Clients whose {@code team_id} is a team the user supervises or belongs to. */
    TEAM,
    /** Every client. */
    ALL;

    /** RB-BR-03: several roles granting one permission yield the widest scope among them. */
    public static Scope widest(Scope a, Scope b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
