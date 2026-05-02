package dev.monk.translator.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backend-native translation response with config and target diagnostics.
 */
public record TranslateEnvelope(
        String requestId,
        String queryName,
        String backend,
        List<TargetMetadata> targets,
        ConfigMetadata config,
        Map<String, Object> query,
        List<Warning> warnings,
        Diagnostics diagnostics) {

    public TranslateEnvelope {
        targets = immutableList(targets);
        query = immutableMap(query);
        warnings = immutableList(warnings);
    }

    public record TargetMetadata(
            String materialType,
            String backend,
            String index,
            String core,
            String mappingFile) {
    }

    public record ConfigMetadata(
            String version,
            String hash,
            Instant loadedAt) {
    }

    public record Warning(
            String code,
            String message) {
    }

    public record Diagnostics(
            List<String> materialTypes,
            String logicalField,
            String destinationField,
            String fieldType,
            int phraseCount) {

        public Diagnostics {
            materialTypes = immutableList(materialTypes);
        }
    }

    private static <T> List<T> immutableList(List<T> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return List.copyOf(source);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
