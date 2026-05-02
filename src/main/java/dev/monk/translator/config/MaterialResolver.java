package dev.monk.translator.config;

import dev.monk.translator.config.MaterialConfig.BackendKind;
import dev.monk.translator.config.MaterialConfig.TargetRef;
import dev.monk.translator.problem.ProblemCode;
import dev.monk.translator.problem.TranslatorException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves request materialTypes against the immutable active configuration.
 */
public class MaterialResolver {
    private final ConfigSnapshot snapshot;

    public MaterialResolver(ConfigSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public ResolvedMaterials resolve(List<String> materialTypes) {
        if (materialTypes == null || materialTypes.isEmpty()) {
            throw new TranslatorException(
                    ProblemCode.EMPTY_MATERIAL_TYPES,
                    "At least one material type is required");
        }

        LinkedHashMap<String, SelectedMaterial> selectedMaterials = new LinkedHashMap<>();
        BackendKind selectedBackend = null;
        LinkedHashMap<String, List<String>> backendMaterials = new LinkedHashMap<>();

        for (String materialType : materialTypes) {
            if (selectedMaterials.containsKey(materialType)) {
                continue;
            }

            ConfigSnapshot.MaterialEntry entry = isBlank(materialType) ? null : snapshot.material(materialType);
            if (entry == null) {
                throw new TranslatorException(
                        ProblemCode.UNKNOWN_MATERIAL_TYPE,
                        "Unknown material type: " + materialType,
                        Map.of("materialType", String.valueOf(materialType)));
            }

            selectedMaterials.put(materialType, SelectedMaterial.from(entry));
            backendMaterials.computeIfAbsent(entry.backend().jsonValue(), ignored -> new ArrayList<>()).add(materialType);
            if (selectedBackend == null) {
                selectedBackend = entry.backend();
            } else if (selectedBackend != entry.backend()) {
                throw new TranslatorException(
                        ProblemCode.MIXED_BACKEND_MATERIAL_TYPES,
                        "Requested material types resolve to multiple search backends",
                        Map.of(
                                "backendMaterials", immutableBackendMaterials(backendMaterials),
                                "materialTypes", List.copyOf(selectedMaterials.keySet())));
            }
        }

        return new ResolvedMaterials(
                selectedBackend,
                List.copyOf(selectedMaterials.values()),
                snapshot.version(),
                snapshot.hash(),
                snapshot.loadedAt());
    }

    private static Map<String, List<String>> immutableBackendMaterials(Map<String, List<String>> source) {
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((backend, materials) -> copy.put(backend, List.copyOf(materials)));
        return Collections.unmodifiableMap(copy);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ResolvedMaterials(
            BackendKind backend,
            List<SelectedMaterial> materials,
            String configVersion,
            String configHash,
            Instant loadedAt) {
    }

    public record SelectedMaterial(
            String name,
            BackendKind backend,
            TargetRef target,
            String mappingFile,
            ConfigSnapshot.MappingDocument mapping) {

        static SelectedMaterial from(ConfigSnapshot.MaterialEntry entry) {
            return new SelectedMaterial(
                    entry.name(),
                    entry.backend(),
                    entry.target(),
                    entry.mappingFile(),
                    entry.mapping());
        }
    }
}
