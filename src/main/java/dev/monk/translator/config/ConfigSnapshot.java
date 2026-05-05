package dev.monk.translator.config;

import dev.monk.translator.config.MaterialConfig.BackendKind;
import dev.monk.translator.config.MaterialConfig.TargetRef;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable runtime configuration shared by translate requests.
 */
public record ConfigSnapshot(
        String version,
        String hash,
        Instant loadedAt,
        Map<String, MaterialEntry> materials) {

    public ConfigSnapshot {
        materials = Map.copyOf(materials);
    }

    public MaterialEntry material(String materialType) {
        return materials.get(materialType);
    }

    public record MaterialEntry(
            String name,
            BackendKind backend,
            TargetRef target,
            String mappingFile,
            MappingDocument mapping) {
    }

    public record MappingDocument(
            String primaryKey,
            Map<String, MappingField> root,
            Map<String, Map<String, MappingField>> documents) {

        public MappingField rootField(String logicalField) {
            return root.get(logicalField);
        }
    }

    public record MappingField(
            String type,
            String subdocumentType,
            String sourceField,
            String destinationField) {
    }
}
