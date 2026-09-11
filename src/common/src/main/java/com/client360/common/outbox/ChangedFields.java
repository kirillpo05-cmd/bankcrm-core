package com.client360.common.outbox;

import com.client360.common.outbox.EventEnvelope.Change;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds {@code payload.changedFields}. Rule AR-01: a sensitive field records <em>that</em> it
 * changed, by whom and when — never <em>to what</em>. The mask is applied here, where the event is
 * constructed, so no downstream component can forget to apply it.
 *
 * <p>"Sensitive" is wider than "encrypted at rest": names and date of birth are plaintext columns
 * but still personal data, and an erased client must leave audit rows holding only a UUID
 * (AT-EC-08). Callers decide per field; when in doubt, use {@link #sensitive}.
 */
public final class ChangedFields {

    public static final String MASKED = "***MASKED***";

    private final Map<String, Change> changes = new LinkedHashMap<>();

    public static ChangedFields create() {
        return new ChangedFields();
    }

    /** A non-sensitive field. Recorded only when the value actually changed. */
    public ChangedFields put(String field, Object oldValue, Object newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            changes.put(field, new Change(render(oldValue), render(newValue)));
        }
        return this;
    }

    /**
     * A sensitive field. Values are compared here and then discarded; only {@code null} ("was
     * absent" / "was cleared") or {@link #MASKED} survives.
     */
    public ChangedFields sensitive(String field, Object oldValue, Object newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            changes.put(field, new Change(oldValue == null ? null : MASKED, newValue == null ? null : MASKED));
        }
        return this;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public boolean contains(String field) {
        return changes.containsKey(field);
    }

    public Map<String, Change> asMap() {
        return Collections.unmodifiableMap(changes);
    }

    private static Object render(Object value) {
        return value instanceof Enum<?> e ? e.name() : value;
    }
}
