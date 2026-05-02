package dev.monk.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class ContractFixturesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final List<String> ROOT_SCHEMA_FILES = List.of(
            "search-query-dsl.schema.json",
            "mappings.schema.json",
            "material-config.schema.json");
    private static final List<String> IGNORED_PATH_PREFIXES = List.of(
            ".gsd/",
            ".planning/",
            ".audits/",
            "target/");

    @Test
    void contractSchemaDocumentsAreTrackedRootJsonObjects() throws Exception {
        for (String schemaFile : ROOT_SCHEMA_FILES) {
            JsonNode schemaDocument = readRootJson(schemaFile);

            assertEquals("object", schemaDocument.path("type").asText(), schemaFile + " should describe a JSON object root");
            assertTrue(schemaDocument.has("$schema"), schemaFile + " should declare its JSON Schema draft");
            assertNotNull(loadSchema(schemaFile), schemaFile + " should be loadable by NetworkNT");
        }
    }

    @Test
    void sampleMappingsConformToMappingsSchema() throws Exception {
        Set<ValidationMessage> errors = validateRootFixture("mappings.schema.json", "sample-mappings.json");

        assertNoValidationErrors("sample-mappings.json", errors);
    }

    @Test
    void sampleMaterialConfigConformsToMaterialSchema() throws Exception {
        Set<ValidationMessage> errors = validateRootFixture("material-config.schema.json", "sample-material-config.json");

        assertNoValidationErrors("sample-material-config.json", errors);
    }

    @Test
    void sampleMaterialConfigContainsElasticsearchAndSolrBackends() throws Exception {
        JsonNode materials = readRootJson("sample-material-config.json").required("materials");
        Map<String, JsonNode> materialMap = StreamSupport.stream(materials.spliterator(), false)
                .collect(Collectors.toMap(material -> material.path("backend").asText(), material -> material, (first, ignored) -> first));

        assertTrue(materialMap.containsKey("elasticsearch"), "sample material config should include an Elasticsearch material");
        assertTrue(materialMap.containsKey("solr"), "sample material config should include a Solr material");

        materials.fields().forEachRemaining(entry -> {
            String materialName = entry.getKey();
            JsonNode material = entry.getValue();
            String mappingFile = material.path("mappingFile").asText();

            assertEquals("sample-mappings.json", mappingFile, materialName + " should reference the tracked sample mappings fixture");
            assertRootTrackedFixture(mappingFile);
        });
    }

    @Test
    void inlinePhraseTextDslRequestConformsToDslSchema() throws Exception {
        JsonNode phraseTextRequest = MAPPER.readTree("""
                {
                  "name": "phrase text smoke test",
                  "materialTypes": ["books-elasticsearch"],
                  "query": {
                    "field": "title",
                    "data": {
                      "type": "text",
                      "phrases": ["monastic architecture"]
                    }
                  }
                }
                """);

        Set<ValidationMessage> errors = loadSchema("search-query-dsl.schema.json").validate(phraseTextRequest);

        assertNoValidationErrors("inline phrase-text DSL request", errors);
    }

    @Test
    void malformedMaterialConfigMissingElasticsearchIndexReportsSchemaErrors() throws Exception {
        JsonNode invalidMaterialConfig = MAPPER.readTree("""
                {
                  "version": "broken-test-fixture",
                  "materials": {
                    "broken-elasticsearch": {
                      "backend": "elasticsearch",
                      "mappingFile": "sample-mappings.json",
                      "target": {}
                    }
                  }
                }
                """);

        Set<ValidationMessage> errors = loadSchema("material-config.schema.json").validate(invalidMaterialConfig);

        assertFalse(errors.isEmpty(), "missing Elasticsearch index should be reported by schema validation");
    }

    @Test
    void malformedMaterialConfigMissingSolrCoreReportsSchemaErrors() throws Exception {
        JsonNode invalidMaterialConfig = MAPPER.readTree("""
                {
                  "version": "broken-test-fixture",
                  "materials": {
                    "broken-solr": {
                      "backend": "solr",
                      "mappingFile": "sample-mappings.json",
                      "target": {}
                    }
                  }
                }
                """);

        Set<ValidationMessage> errors = loadSchema("material-config.schema.json").validate(invalidMaterialConfig);

        assertFalse(errors.isEmpty(), "missing Solr core should be reported by schema validation");
    }

    private static Set<ValidationMessage> validateRootFixture(String schemaFile, String fixtureFile) throws IOException {
        return loadSchema(schemaFile).validate(readRootJson(fixtureFile));
    }

    private static JsonSchema loadSchema(String schemaFile) throws IOException {
        JsonNode schemaDocument = readRootJson(schemaFile);
        return JsonSchemaFactory.getInstance(versionFlagFor(schemaDocument)).getSchema(schemaDocument);
    }

    private static SpecVersion.VersionFlag versionFlagFor(JsonNode schemaDocument) {
        String schemaUri = schemaDocument.path("$schema").asText();
        if (schemaUri.contains("2020-12")) {
            return SpecVersion.VersionFlag.V202012;
        }
        return SpecVersion.VersionFlag.V7;
    }

    private static JsonNode readRootJson(String relativePath) throws IOException {
        assertRootTrackedFixture(relativePath);
        return MAPPER.readTree(REPOSITORY_ROOT.resolve(relativePath).toFile());
    }

    private static void assertRootTrackedFixture(String relativePath) {
        String normalizedPath = relativePath.replace('\\', '/');
        assertFalse(normalizedPath.startsWith("/") || normalizedPath.contains("../"), relativePath + " should stay under the repository root");
        for (String ignoredPrefix : IGNORED_PATH_PREFIXES) {
            assertFalse(normalizedPath.startsWith(ignoredPrefix), relativePath + " should not point at ignored local-only files");
        }

        Path resolvedPath = REPOSITORY_ROOT.resolve(relativePath).normalize();
        assertTrue(resolvedPath.startsWith(REPOSITORY_ROOT), relativePath + " should resolve under the repository root");
        assertTrue(Files.isRegularFile(resolvedPath), relativePath + " should exist as a root contract fixture");
    }

    private static void assertNoValidationErrors(String label, Set<ValidationMessage> errors) {
        assertTrue(errors.isEmpty(), () -> label + " should validate but had errors: " + errors);
    }
}
