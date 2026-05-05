package dev.monk.translator.problem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * Domain exception with a stable code and structured, non-sensitive details.
 */
@Getter
@Accessors(fluent = true)
public class TranslatorException extends RuntimeException {
    private final ProblemCode code;
    private final Map<String, Object> details;

    public TranslatorException(ProblemCode code, String message) {
        this(code, message, Map.of(), null);
    }

    public TranslatorException(ProblemCode code, String message, Map<String, ?> details) {
        this(code, message, details, null);
    }

    public TranslatorException(ProblemCode code, String message, Throwable cause) {
        this(code, message, Map.of(), cause);
    }

    public TranslatorException(ProblemCode code, String message, Map<String, ?> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = immutableDetails(details);
    }

    private static Map<String, Object> immutableDetails(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
