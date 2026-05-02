package dev.monk.translator.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RFC 7807-style error response with stable translator machine codes.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ProblemDetails(
        String type,
        String title,
        int status,
        String code,
        String detail,
        String instance,
        Map<String, ?> details) {

    public ProblemDetails {
        details = immutableDetails(details);
    }

    private static Map<String, Object> immutableDetails(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
