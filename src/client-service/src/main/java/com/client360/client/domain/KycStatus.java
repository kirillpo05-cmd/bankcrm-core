package com.client360.client.domain;

import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code client.kyc_status}. Transitions are CP-BR-04 and live here rather than in the service,
 * so the legal graph is stated once and the service only asks whether an edge exists.
 */
public enum KycStatus {
    NOT_STARTED,
    PENDING,
    VERIFIED,
    REJECTED,
    EXPIRED;

    /** CP-BR-04. {@code VERIFIED → EXPIRED} is present but system-only; see {@link #isSystemOnly}. */
    private static final Map<KycStatus, Set<KycStatus>> LEGAL = Map.of(
            NOT_STARTED, EnumSet.of(PENDING),
            PENDING, EnumSet.of(VERIFIED, REJECTED),
            VERIFIED, EnumSet.of(EXPIRED, PENDING),
            REJECTED, EnumSet.of(PENDING),
            EXPIRED, EnumSet.of(PENDING));

    /**
     * The nightly expiry sweep owns this edge (CP-BR-06); no caller may drive it through the API.
     */
    public static boolean isSystemOnly(KycStatus from, KycStatus to) {
        return from == VERIFIED && to == EXPIRED;
    }

    /** CP-BR-05: only {@code PENDING} is reachable without {@code client:kyc}. */
    public boolean requiresKycPermission() {
        return this != PENDING;
    }

    public void requireTransitionTo(KycStatus target) {
        if (this == target || !LEGAL.getOrDefault(this, Set.of()).contains(target)) {
            throw new ApiException(
                            org.springframework.http.HttpStatus.CONFLICT,
                            ErrorCodes.ILLEGAL_STATE_TRANSITION,
                            "KYC status cannot move from " + this + " to " + target + " (CP-BR-04).")
                    .detail("from", name(), "to", target.name());
        }
    }
}
