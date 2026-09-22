package com.client360.common.outbox;

import com.client360.common.id.UuidV7;
import com.client360.common.outbox.EventEnvelope.Actor;
import com.client360.common.outbox.EventEnvelope.EntityRef;
import com.client360.common.outbox.EventEnvelope.Payload;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.CurrentUsers;
import com.client360.common.time.DatabaseClock;
import com.client360.common.web.RequestMetadata;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Assembles envelopes from the current request: actor from the access token, ip/user agent and
 * endpoint from the servlet request, {@code occurredAt} from PostgreSQL {@code now()}.
 */
@Component
public class EventFactory {

    private final DatabaseClock clock;
    private final String service;

    public EventFactory(DatabaseClock clock, @Value("${spring.application.name}") String service) {
        this.clock = clock;
        this.service = service;
    }

    public Builder event(String eventType, String entityType, UUID entityId) {
        return new Builder(eventType, entityType, entityId);
    }

    public final class Builder {

        private final String eventType;
        private final String entityType;
        private final UUID entityId;
        private UUID clientId;
        private String action;
        private ChangedFields changes = ChangedFields.create();
        private final Map<String, Object> context = new LinkedHashMap<>();
        private UUID correlationId;

        private Builder(String eventType, String entityType, UUID entityId) {
            this.eventType = eventType;
            this.entityType = entityType;
            this.entityId = entityId;
        }

        /** Denormalized so the audit trail can answer "who touched this client" (AT-US-01). */
        public Builder clientId(UUID clientId) {
            this.clientId = clientId;
            return this;
        }

        /** An {@code audit_action} value: CREATE, UPDATE, DELETE, MERGE, READ_SENSITIVE, … */
        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder changes(ChangedFields changes) {
            this.changes = changes;
            return this;
        }

        public Builder context(String key, Object value) {
            if (value != null) {
                context.put(key, value);
            }
            return this;
        }

        /** Shared by every per-entity event of one bulk operation (AT-EC-11). Defaults to the request id. */
        public Builder correlationId(UUID correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public EventEnvelope build() {
            if (action == null) {
                throw new IllegalStateException("event " + eventType + " has no action");
            }
            CurrentUser user = CurrentUsers.find().orElse(null);
            RequestMetadata request = RequestMetadata.current().orElse(null);
            Actor actor = user == null
                    ? null
                    : new Actor(
                            user.id(),
                            user.email(),
                            user.primaryRole(),
                            request == null ? null : request.ip(),
                            request == null ? null : request.userAgent());
            UUID requestId =
                    request == null || request.requestId() == null ? null : UUID.fromString(request.requestId());
            Map<String, Object> fullContext = new LinkedHashMap<>();
            if (request != null) {
                fullContext.put("endpoint", request.endpoint());
            }
            fullContext.putAll(context);
            return new EventEnvelope(
                    UuidV7.next(),
                    eventType,
                    1,
                    clock.now(),
                    service,
                    actor,
                    new EntityRef(entityType, entityId),
                    clientId,
                    requestId,
                    correlationId != null ? correlationId : requestId,
                    new Payload(action, changes.asMap(), fullContext));
        }
    }
}
