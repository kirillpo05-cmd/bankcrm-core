package com.client360.common.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Canonical JSON: object keys sorted recursively, no whitespace. Two logically equal documents
 * produce byte-identical output, which is what a hash needs — the idempotency payload hash (§4.6)
 * and, in v2, the audit hash chain (§8.2.4), where a non-deterministic serializer would silently
 * invalidate every row after the first.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    public static String write(ObjectMapper mapper, Object value) {
        return canonical(mapper.valueToTree(value)).toString();
    }

    static JsonNode canonical(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                sorted.set(name, canonical(object.get(name)));
            }
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            for (Iterator<JsonNode> it = array.elements(); it.hasNext(); ) {
                copy.add(canonical(it.next()));
            }
            return copy;
        }
        return node;
    }
}
