package dev.monk.translator.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * File-backed material routing contract loaded from {@code monk.material-config.path}.
 */
public record MaterialConfig(
        @JsonProperty("$schema") String schema,
        String version,
        Map<String, MaterialDefinition> materials) {

    public MaterialConfig {
        materials = immutableMap(materials);
    }

    public enum BackendKind {
        ELASTICSEARCH("elasticsearch"),
        SOLR("solr");

        private final String jsonValue;

        BackendKind(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        @JsonCreator
        public static BackendKind fromJson(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.toLowerCase(Locale.ROOT);
            for (BackendKind backendKind : values()) {
                if (backendKind.jsonValue.equals(normalized)) {
                    return backendKind;
                }
            }
            throw new IllegalArgumentException("Unsupported backend kind: " + value);
        }

        @JsonValue
        public String jsonValue() {
            return jsonValue;
        }
    }

    public record MaterialDefinition(
            BackendKind backend,
            String mappingFile,
            TargetRef target) {
    }

    public record TargetRef(
            String index,
            String core) {

        public String targetName(BackendKind backend) {
            return switch (backend) {
                case ELASTICSEARCH -> index;
                case SOLR -> core;
            };
        }
    }

    private static Map<String, MaterialDefinition> immutableMap(Map<String, MaterialDefinition> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
