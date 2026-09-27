package com.client360.interaction.consumer;

import com.client360.interaction.persistence.InteractionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the denormalized client ownership on {@code interactions} current (V6, SPEC.md §3.1).
 *
 * <p>A new interaction is stamped at write time from the authorization answer, so this consumer
 * exists for one thing the write path cannot see: a client changing hands. {@code CP-US-05} moves a
 * client to another manager and team, and every interaction already recorded has to follow, or the
 * supervisor's feed would still be showing them to the team that no longer owns the client — which
 * is a disclosure, not just a stale number.
 *
 * <p>What it deliberately does <em>not</em> do is decide anything. These columns narrow a query;
 * an authorization decision still asks client-service, which owns the truth. So a message this
 * consumer misses costs a filter that is behind, never a permission that is wrong.
 *
 * <p>Idempotent by construction: it writes the values the event states rather than applying a
 * delta, so redelivery is a no-op and out-of-order delivery settles on whatever the last message
 * says. That is the property that makes at-least-once delivery safe here without a dedupe table.
 */
@Component
@ConditionalOnProperty(name = "client360.consumer.client-events.enabled", matchIfMissing = true)
public class ClientEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClientEventsConsumer.class);

    private final InteractionRepository interactions;
    private final ObjectMapper objectMapper;

    public ClientEventsConsumer(InteractionRepository interactions, ObjectMapper objectMapper) {
        this.interactions = interactions;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "client.events", groupId = "interaction-service.client-scope")
    public void onClientEvent(ConsumerRecord<String, String> record) {
        JsonNode event;
        try {
            event = objectMapper.readTree(record.value());
        } catch (Exception e) {
            // A message this service cannot parse is client-service's problem, and blocking the
            // partition over it would stop ownership updates for every other client. The columns
            // are a filter, so falling behind on one client is the lesser failure — but it is
            // logged loudly, because silently skipping is how a feed goes quietly wrong.
            log.error(
                    "unparseable client event at {}-{} offset {}", record.topic(), record.partition(), record.offset());
            return;
        }
        String type = event.path("eventType").asText();
        if ("client.merged".equals(type)) {
            onMerged(event);
            return;
        }
        if (!"client.reassigned".equals(type)) {
            return;
        }
        UUID clientId = uuid(event.path("clientId"));
        JsonNode changed = event.path("payload").path("changedFields");
        UUID owner = uuid(changed.path("ownerManagerId").path("new"));
        UUID team = uuid(changed.path("teamId").path("new"));

        if (clientId == null || owner == null || team == null) {
            // ownerManagerId and teamId are staff and team identifiers, not client PII, so AR-01
            // never masks them and this branch means the producer's contract changed. Say so
            // rather than writing a null that would drop every interaction out of its team feed.
            log.error("client.reassigned for {} carried no usable ownership; leaving rows as they are", clientId);
            return;
        }
        int updated = interactions.reassignClientScope(clientId, owner, team);
        log.info("client {} reassigned: {} interaction(s) re-scoped", clientId, updated);
    }

    /**
     * CP-BR-10: the loser's interactions move to the survivor and keep their {@code occurred_at},
     * so the merged timeline stays chronologically honest (IL-BR-13).
     *
     * <p>They are re-scoped in the same statement. An interaction that moved to another client has
     * moved to that client's owner and team, and leaving the old ones behind would keep it in the
     * losing team's feed — a disclosure rather than a stale filter. The survivor's owner and team
     * travel in the event's {@code context} precisely so this does not need a REST call: a consumer
     * that calls out per message is a consumer that stops when the callee does.
     */
    private void onMerged(JsonNode event) {
        UUID loser = uuid(event.path("clientId"));
        JsonNode context = event.path("payload").path("context");
        UUID survivor = uuid(context.path("survivorId"));
        UUID owner = uuid(context.path("survivorOwnerId"));
        UUID team = uuid(context.path("survivorTeamId"));

        if (loser == null || survivor == null || owner == null || team == null) {
            log.error("client.merged for {} carried no usable survivor; interactions left in place", loser);
            return;
        }
        int moved = interactions.repointToSurvivor(loser, survivor, owner, team);
        log.info("client {} merged into {}: {} interaction(s) moved", loser, survivor, moved);
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
