package dev.monk.translator.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Stable inbound translate API shape. Backend selection is intentionally absent;
 * materialTypes are the only caller input used for backend routing.
 */
public record TranslateRequest(
        String id,
        String name,
        List<String> materialTypes,
        QueryNode query) {

    public record QueryNode(
            String field,
            Integer minimumMatch,
            Boolean isNot,
            JsonNode data) {
    }
}
