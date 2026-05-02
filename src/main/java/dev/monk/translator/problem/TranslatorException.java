package dev.monk.translator.problem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Domain exception with a stable code and structured, non-sensitive details.
 */
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

    public ProblemCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    private static Map<String, Object> immutableDetails(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
