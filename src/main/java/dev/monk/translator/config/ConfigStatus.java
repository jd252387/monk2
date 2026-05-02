package dev.monk.translator.config;

import dev.monk.translator.config.MaterialConfig.BackendKind;
import dev.monk.translator.problem.ProblemCode;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent-usable diagnostics for the currently loaded material configuration.
 */
public record ConfigStatus(
        boolean active,
        String version,
        String hash,
        Instant loadedAt,
        int materialCount,
        Map<String, Integer> backendCounts,
        String lastErrorCode,
        String lastErrorDetail) {

    public ConfigStatus {
        backendCounts = immutableCounts(backendCounts);
    }

    public static ConfigStatus active(ConfigSnapshot snapshot) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (BackendKind backendKind : BackendKind.values()) {
            counts.put(backendKind.jsonValue(), 0);
        }
        snapshot.materials().values().forEach(material ->
                counts.merge(material.backend().jsonValue(), 1, Integer::sum));
        counts.entrySet().removeIf(entry -> entry.getValue() == 0);

        return new ConfigStatus(
                true,
                snapshot.version(),
                snapshot.hash(),
                snapshot.loadedAt(),
                snapshot.materials().size(),
                counts,
                null,
                null);
    }

    public static ConfigStatus failed(ProblemCode code, String detail) {
        return new ConfigStatus(
                false,
                null,
                null,
                null,
                0,
                Map.of(),
                code.name(),
                detail);
    }

    private static Map<String, Integer> immutableCounts(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
