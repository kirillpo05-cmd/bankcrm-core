package com.client360.client.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A validated {@code PATCH /clients/{id}} body (SPEC.md §5.3).
 *
 * <p>JSON merge-patch semantics need three states per field, which a record of nullable fields
 * cannot hold: <em>absent</em> (leave it alone), <em>present and null</em> (clear it), and
 * <em>present with a value</em>. This keeps presence explicit so that {@code {"email": null}}
 * clears an email and {@code {}} changes nothing.
 *
 * <p>Shape only. Whether the caller may touch a field is an authorization decision and belongs
 * to the service, not here.
 */
public final class ClientPatch {

    /** Every field a patch may name. Anything else in the body is {@code 400}. */
    public enum Field {
        FIRST_NAME("firstName", Kind.PROFILE, false),
        LAST_NAME("lastName", Kind.PROFILE, false),
        MIDDLE_NAME("middleName", Kind.PROFILE, true),
        DATE_OF_BIRTH("dateOfBirth", Kind.PROFILE, false),
        EMAIL("email", Kind.PROFILE, true),
        PHONE("phone", Kind.PROFILE, false),
        TAX_ID("taxId", Kind.PROFILE, true),
        ADDRESS("address", Kind.PROFILE, true),
        PREFERRED_CHANNEL("preferredChannel", Kind.PROFILE, false),
        SEGMENT("segment", Kind.PROFILE, false),
        RISK("risk", Kind.RISK, false),
        STATUS("status", Kind.STATUS, false),
        EXTERNAL_REF("externalRef", Kind.IMMUTABLE, false),
        OWNER_MANAGER_ID("ownerManagerId", Kind.ELSEWHERE, false),
        TEAM_ID("teamId", Kind.ELSEWHERE, false),
        KYC_STATUS("kycStatus", Kind.ELSEWHERE, false);

        private final String json;
        private final Kind kind;
        private final boolean nullable;

        Field(String json, Kind kind, boolean nullable) {
            this.json = json;
            this.kind = kind;
            this.nullable = nullable;
        }

        public String json() {
            return json;
        }

        public Kind kind() {
            return kind;
        }

        public boolean nullable() {
            return nullable;
        }

        static Optional<Field> byJsonName(String name) {
            return Arrays.stream(values()).filter(f -> f.json.equals(name)).findFirst();
        }
    }

    /** Which authority a field answers to. The service maps each kind to a permission. */
    public enum Kind {
        /** {@code client:write} in scope. */
        PROFILE,
        /** Also {@code client:kyc} in scope — a risk rating is a compliance judgement (A-13). */
        RISK,
        /** Also {@code client:delete} — client lifecycle is admin authority (CP-BR-08, A-13). */
        STATUS,
        /** Never through PATCH: immutable after creation (CP-BR-01). */
        IMMUTABLE,
        /** Never through PATCH: owned by a dedicated endpoint or derived (CP-BR-03, CP-BR-04). */
        ELSEWHERE
    }

    private final Map<Field, Object> values;

    ClientPatch(Map<Field, Object> values) {
        this.values = Collections.unmodifiableMap(new EnumMap<>(values));
    }

    public boolean has(Field field) {
        return values.containsKey(field);
    }

    /** The value sent for a present field; {@code null} means "clear it". */
    @SuppressWarnings("unchecked")
    public <T> T value(Field field) {
        return (T) values.get(field);
    }

    /** The present value, or {@code current} when the field was not sent. */
    public <T> T valueOr(Field field, T current) {
        return has(field) ? value(field) : current;
    }

    public Set<Field> fields() {
        return values.keySet();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
