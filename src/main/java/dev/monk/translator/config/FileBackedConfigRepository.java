package dev.monk.translator.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.monk.translator.config.MaterialConfig.BackendKind;
import dev.monk.translator.config.MaterialConfig.MaterialDefinition;
import dev.monk.translator.problem.ProblemCode;
import dev.monk.translator.problem.TranslatorException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Loads material routing configuration from disk once and exposes immutable active diagnostics.
 */
@ApplicationScoped
public class FileBackedConfigRepository {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private static final String MATERIAL_CONFIG_SCHEMA = "material-config.schema.json";
    private static final String MAPPINGS_SCHEMA = "mappings.schema.json";
    private static final List<String> IGNORED_PATH_PREFIXES = List.of(
            ".gsd/",
            ".planning/",
            ".audits/",
            "target/");

    private final Path repositoryRoot;

    @ConfigProperty(name = "monk.material-config.path")
    String configuredMaterialConfigPath;

    private volatile ConfigSnapshot activeSnapshot;
    private volatile ConfigStatus status = ConfigStatus.failed(
            ProblemCode.CONFIG_LOAD_FAILED,
            "Material configuration has not been loaded");

    public FileBackedConfigRepository() {
        this.repositoryRoot = Path.of("").toAbsolutePath().normalize();
    }

    public FileBackedConfigRepository(Path repositoryRoot, Path materialConfigPath) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        reload(materialConfigPath);
    }

    @PostConstruct
    void loadConfiguredMaterialConfig() {
        if (configuredMaterialConfigPath == null || configuredMaterialConfigPath.isBlank()) {
            markFailed(new TranslatorException(
                    ProblemCode.CONFIG_LOAD_FAILED,
                    "Required configuration property monk.material-config.path is missing"));
            return;
        }
        reload(Path.of(configuredMaterialConfigPath));
    }

    public synchronized ConfigStatus reload(Path materialConfigPath) {
        try {
            ConfigSnapshot snapshot = loadSnapshot(repositoryRoot, materialConfigPath);
            activeSnapshot = snapshot;
            status = ConfigStatus.active(snapshot);
            return status;
        } catch (TranslatorException exception) {
            markFailed(exception);
            return status;
        }
    }

    public ConfigSnapshot activeSnapshot() {
        ConfigSnapshot snapshot = activeSnapshot;
        if (snapshot == null) {
            throw new TranslatorException(
                    ProblemCode.CONFIG_LOAD_FAILED,
                    status.lastErrorDetail() == null ? "Material configuration is not active" : status.lastErrorDetail());
        }
        return snapshot;
    }

    public ConfigStatus status() {
        return status;
    }

    public static ConfigSnapshot loadSnapshot(Path repositoryRoot, Path materialConfigPath) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        Path configPath = resolveConfigPath(root, materialConfigPath);
        String configDisplayPath = displayPath(root, configPath);
        byte[] configBytes = readBytes(
                configPath,
                "Material config file '" + configDisplayPath + "'",
                ProblemCode.CONFIG_LOAD_FAILED);
        JsonNode configNode = parseJson(
                configBytes,
                "material config file '" + configDisplayPath + "'",
                ProblemCode.CONFIG_SCHEMA_INVALID);

        validateAgainstSchema(root, MATERIAL_CONFIG_SCHEMA, configNode, "material config file '" + configDisplayPath + "'");
        MaterialConfig materialConfig = parseMaterialConfig(configNode, configDisplayPath);
        validateMaterialConfigSemantics(materialConfig, configDisplayPath);

        LinkedHashMap<String, ConfigSnapshot.MaterialEntry> materialEntries = new LinkedHashMap<>();
        LinkedHashMap<String, ConfigSnapshot.MappingDocument> mappingCache = new LinkedHashMap<>();
        TreeMap<String, byte[]> mappingBytesForHash = new TreeMap<>();

        materialConfig.materials().forEach((materialName, definition) -> {
            validateMaterialDefinition(materialName, definition);
            String mappingReference = normalizeRepositoryRelativePath(
                    definition.mappingFile(),
                    "mappingFile for material '" + materialName + "'");
            ConfigSnapshot.MappingDocument mapping = mappingCache.computeIfAbsent(mappingReference, reference -> {
                MappingLoadResult mappingLoad = loadMapping(root, materialName, reference);
                mappingBytesForHash.put(reference, mappingLoad.bytes());
                return mappingLoad.mapping();
            });

            materialEntries.put(materialName, new ConfigSnapshot.MaterialEntry(
                    materialName,
                    definition.backend(),
                    definition.target(),
                    mappingReference,
                    mapping));
        });

        return new ConfigSnapshot(
                materialConfig.version(),
                computeHash(configBytes, mappingBytesForHash),
                Instant.now(),
                materialEntries);
    }

    private void markFailed(TranslatorException exception) {
        activeSnapshot = null;
        status = ConfigStatus.failed(exception.code(), exception.getMessage());
    }

    private static Path resolveConfigPath(Path repositoryRoot, Path materialConfigPath) {
        if (materialConfigPath == null) {
            throw new TranslatorException(
                    ProblemCode.CONFIG_LOAD_FAILED,
                    "Material config path is missing");
        }
        if (materialConfigPath.isAbsolute()) {
            return materialConfigPath.normalize();
        }
        return repositoryRoot.resolve(materialConfigPath).normalize();
    }

    private static MaterialConfig parseMaterialConfig(JsonNode configNode, String configDisplayPath) {
        try {
            return MAPPER.treeToValue(configNode, MaterialConfig.class);
        } catch (JsonProcessingException exception) {
            throw new TranslatorException(
                    ProblemCode.CONFIG_SCHEMA_INVALID,
                    "Material config file '" + configDisplayPath + "' cannot be parsed after schema validation",
                    Map.of("path", configDisplayPath),
                    exception);
        }
    }

    private static void validateMaterialConfigSemantics(MaterialConfig materialConfig, String configDisplayPath) {
        if (isBlank(materialConfig.version())) {
            throw new TranslatorException(
                    ProblemCode.CONFIG_SEMANTIC_INVALID,
                    "Material config file '" + configDisplayPath + "' has a blank version");
        }
        if (materialConfig.materials().isEmpty()) {
            throw new TranslatorException(
                    ProblemCode.CONFIG_SEMANTIC_INVALID,
                    "Material config file '" + configDisplayPath + "' must define at least one material");
        }
    }

    private static void validateMaterialDefinition(String materialName, MaterialDefinition definition) {
        if (definition == null) {
            throw new TranslatorException(
                    ProblemCode.CONFIG_SEMANTIC_INVALID,
                    "Material '" + materialName + "' is missing its definition");
        }
        if (definition.backend() == null) {
            throw new TranslatorException(
                    ProblemCode.CONFIG_SEMANTIC_INVALID,
                    "Material '" + materialName + "' is missing backend");
        }
        if (definition.target() == null || isBlank(definition.target().targetName(definition.backend()))) {
            String requiredTarget = definition.backend() == BackendKind.ELASTICSEARCH ? "index" : "core";
            throw new TranslatorException(
                    ProblemCode.CONFIG_SEMANTIC_INVALID,
                    "Material '" + materialName + "' is missing target." + requiredTarget);
        }
    }

    private static MappingLoadResult loadMapping(Path repositoryRoot, String materialName, String mappingReference) {
        Path mappingPath = repositoryRoot.resolve(mappingReference).normalize();
        if (!mappingPath.startsWith(repositoryRoot)) {
            throw new TranslatorException(
                    ProblemCode.CONFIG_SEMANTIC_INVALID,
                    "Material '" + materialName + "' references mapping outside the repository: '" + mappingReference + "'");
        }

        String mappingLabel = "Material '" + materialName + "' mapping file '" + mappingReference + "'";
        byte[] mappingBytes = readBytes(mappingPath, mappingLabel, ProblemCode.CONFIG_LOAD_FAILED);
        JsonNode mappingNode = parseJson(mappingBytes, mappingLabel, ProblemCode.CONFIG_SCHEMA_INVALID);
        validateAgainstSchema(repositoryRoot, MAPPINGS_SCHEMA, mappingNode, mappingLabel);

        return new MappingLoadResult(parseMappingDocument(mappingNode, materialName, mappingReference), mappingBytes);
    }

    private static ConfigSnapshot.MappingDocument parseMappingDocument(
            JsonNode mappingNode,
            String materialName,
            String mappingReference) {
        String primaryKey = textOrNull(mappingNode.get("primaryKey"));
        if (isBlank(primaryKey)) {
            throw mappingSemanticError(materialName, mappingReference, "primaryKey is required");
        }

        JsonNode rootNode = mappingNode.get("root");
        if (rootNode == null || !rootNode.isObject()) {
            throw mappingSemanticError(materialName, mappingReference, "root mapping object is required");
        }

        LinkedHashMap<String, ConfigSnapshot.MappingField> rootFields = parseMappingFields(
                rootNode,
                "root",
                materialName,
                mappingReference);
        if (rootFields.isEmpty()) {
            throw mappingSemanticError(materialName, mappingReference, "root mapping must contain at least one field");
        }

        LinkedHashMap<String, Map<String, ConfigSnapshot.MappingField>> documents = new LinkedHashMap<>();
        mappingNode.fields().forEachRemaining(entry -> {
            String documentName = entry.getKey();
            if ("$schema".equals(documentName) || "primaryKey".equals(documentName) || "root".equals(documentName)) {
                return;
            }
            documents.put(documentName, parseMappingFields(entry.getValue(), documentName, materialName, mappingReference));
        });

        return new ConfigSnapshot.MappingDocument(primaryKey, rootFields, documents);
    }

    private static LinkedHashMap<String, ConfigSnapshot.MappingField> parseMappingFields(
            JsonNode documentNode,
            String documentName,
            String materialName,
            String mappingReference) {
        if (!documentNode.isObject()) {
            throw mappingSemanticError(materialName, mappingReference, documentName + " mapping must be an object");
        }

        LinkedHashMap<String, ConfigSnapshot.MappingField> fields = new LinkedHashMap<>();
        documentNode.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode fieldNode = entry.getValue();
            String type = textOrNull(fieldNode.get("type"));
            String subdocumentType = textOrNull(fieldNode.get("subdocumentType"));
            String sourceField = textOrNull(fieldNode.get("sourceField"));
            String destinationField = textOrNull(fieldNode.get("destinationField"));

            if (isBlank(type)) {
                throw mappingSemanticError(materialName, mappingReference,
                        documentName + "." + fieldName + " is missing type");
            }
            if ("subdocument".equals(type) && isBlank(subdocumentType)) {
                throw mappingSemanticError(materialName, mappingReference,
                        documentName + "." + fieldName + " is missing subdocumentType");
            }
            if (isBlank(destinationField)) {
                throw mappingSemanticError(materialName, mappingReference,
                        documentName + "." + fieldName + " is missing destinationField");
            }

            fields.put(fieldName, new ConfigSnapshot.MappingField(type, subdocumentType, sourceField, destinationField));
        });
        return fields;
    }

    private static void validateAgainstSchema(Path repositoryRoot, String schemaFile, JsonNode document, String documentLabel) {
        Path schemaPath = repositoryRoot.resolve(schemaFile).normalize();
        byte[] schemaBytes = readBytes(
                schemaPath,
                "Schema file '" + schemaFile + "'",
                ProblemCode.CONFIG_LOAD_FAILED);
        JsonNode schemaNode = parseJson(schemaBytes, "schema file '" + schemaFile + "'", ProblemCode.CONFIG_SCHEMA_INVALID);
        JsonSchema schema = JsonSchemaFactory.getInstance(versionFlagFor(schemaNode)).getSchema(schemaNode);
        Set<ValidationMessage> errors = schema.validate(document);
        if (!errors.isEmpty()) {
            throw new TranslatorException(
                    ProblemCode.CONFIG_SCHEMA_INVALID,
                    documentLabel + " failed schema validation",
                    Map.of("errors", formatValidationErrors(errors)));
        }
    }

    private static SpecVersion.VersionFlag versionFlagFor(JsonNode schemaDocument) {
        String schemaUri = schemaDocument.path("$schema").asText();
        if (schemaUri.contains("2020-12")) {
            return SpecVersion.VersionFlag.V202012;
        }
        return SpecVersion.VersionFlag.V7;
    }

    private static List<String> formatValidationErrors(Set<ValidationMessage> errors) {
        return errors.stream()
                .map(Object::toString)
                .sorted()
                .collect(Collectors.toList());
    }

    private static byte[] readBytes(Path path, String label, ProblemCode code) {
        if (!Files.isRegularFile(path)) {
            throw new TranslatorException(code, label + " not found", Map.of("path", label));
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new TranslatorException(code, "Unable to read " + label, Map.of("path", label), exception);
        }
    }

    private static JsonNode parseJson(byte[] jsonBytes, String label, ProblemCode code) {
        try {
            return MAPPER.readTree(jsonBytes);
        } catch (JsonProcessingException exception) {
            throw new TranslatorException(code, "Invalid JSON in " + label, Map.of("path", label), exception);
        } catch (IOException exception) {
            throw new TranslatorException(code, "Unable to parse " + label, Map.of("path", label), exception);
        }
    }

    private static String normalizeRepositoryRelativePath(String relativePath, String label) {
        if (isBlank(relativePath)) {
            throw new TranslatorException(ProblemCode.CONFIG_SEMANTIC_INVALID, label + " is required");
        }

        Path rawPath = Path.of(relativePath.replace('\\', '/'));
        Path normalizedPath = rawPath.normalize();
        String normalized = normalizedPath.toString().replace('\\', '/');
        if (rawPath.isAbsolute() || normalized.isBlank() || normalized.equals("..") || normalized.startsWith("../")) {
            throw new TranslatorException(
                    ProblemCode.CONFIG_SEMANTIC_INVALID,
                    label + " must be repository-relative and stay under the repository root");
        }
        for (String ignoredPrefix : IGNORED_PATH_PREFIXES) {
            if (normalized.startsWith(ignoredPrefix)) {
                throw new TranslatorException(
                        ProblemCode.CONFIG_SEMANTIC_INVALID,
                        label + " must not reference ignored local-only paths");
            }
        }
        return normalized;
    }

    private static String displayPath(Path repositoryRoot, Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (normalizedPath.startsWith(repositoryRoot)) {
            return repositoryRoot.relativize(normalizedPath).toString().replace('\\', '/');
        }
        Path fileName = normalizedPath.getFileName();
        return fileName == null ? normalizedPath.toString() : fileName.toString();
    }

    private static String computeHash(byte[] configBytes, Map<String, byte[]> mappingBytesByReference) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, "material-config\0");
            digest.update(configBytes);
            mappingBytesByReference.forEach((reference, mappingBytes) -> {
                updateDigest(digest, "\0mapping\0" + reference + "\0");
                digest.update(mappingBytes);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static TranslatorException mappingSemanticError(String materialName, String mappingReference, String detail) {
        return new TranslatorException(
                ProblemCode.CONFIG_SEMANTIC_INVALID,
                "Material '" + materialName + "' mapping file '" + mappingReference + "' is invalid: " + detail);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record MappingLoadResult(ConfigSnapshot.MappingDocument mapping, byte[] bytes) {
    }
}
