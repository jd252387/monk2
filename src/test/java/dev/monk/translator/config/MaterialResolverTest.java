package dev.monk.translator.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.monk.translator.config.MaterialConfig.BackendKind;
import dev.monk.translator.problem.ProblemCode;
import dev.monk.translator.problem.TranslatorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaterialResolverTest {
    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path SAMPLE_CONFIG = REPOSITORY_ROOT.resolve("sample-material-config.json");

    @TempDir
    Path tempDir;

    @Test
    void loadsTrackedSampleConfigIntoActiveStatusSnapshot() {
        FileBackedConfigRepository repository = new FileBackedConfigRepository(REPOSITORY_ROOT, SAMPLE_CONFIG);

        ConfigStatus status = repository.status();
        ConfigSnapshot snapshot = assertDoesNotThrow(repository::activeSnapshot);

        assertTrue(status.active(), "sample material config should become active");
        assertEquals("sample-material-config-v1", status.version());
        assertEquals("sample-material-config-v1", snapshot.version());
        assertNotNull(status.hash());
        assertEquals(64, status.hash().length(), "SHA-256 hash should be hex encoded");
        assertEquals(status.hash(), snapshot.hash());
        assertNotNull(status.loadedAt());
        assertEquals(2, status.materialCount());
        assertEquals(1, status.backendCounts().get("elasticsearch"));
        assertEquals(1, status.backendCounts().get("solr"));
        assertEquals(2, snapshot.materials().size());
        assertFalse(status.backendCounts().containsKey("opensearch"));
    }

    @Test
    void resolvesSingleElasticsearchMaterialFromConfigOnly() {
        MaterialResolver.ResolvedMaterials resolved = resolverForSample()
                .resolve(List.of("books-elasticsearch"));

        assertEquals(BackendKind.ELASTICSEARCH, resolved.backend());
        assertEquals("sample-material-config-v1", resolved.configVersion());
        assertEquals(1, resolved.materials().size());

        MaterialResolver.SelectedMaterial material = resolved.materials().getFirst();
        assertEquals("books-elasticsearch", material.name());
        assertEquals(BackendKind.ELASTICSEARCH, material.backend());
        assertEquals("books_v1", material.target().index());
        assertEquals("sample-mappings.json", material.mappingFile());
        assertEquals("title_txt", material.mapping().root().get("title").destinationField());
    }

    @Test
    void resolvesSingleSolrMaterialFromConfigOnly() {
        MaterialResolver.ResolvedMaterials resolved = resolverForSample()
                .resolve(List.of("journals-solr"));

        assertEquals(BackendKind.SOLR, resolved.backend());
        assertEquals(1, resolved.materials().size());
        MaterialResolver.SelectedMaterial material = resolved.materials().getFirst();
        assertEquals("journals-solr", material.name());
        assertEquals(BackendKind.SOLR, material.backend());
        assertEquals("journals_v1", material.target().core());
        assertEquals("title_txt", material.mapping().root().get("title").destinationField());
    }

    @Test
    void duplicateMaterialNamesAreResolvedOnceWithoutChangingBackend() {
        MaterialResolver.ResolvedMaterials resolved = resolverForSample()
                .resolve(List.of("books-elasticsearch", "books-elasticsearch"));

        assertEquals(BackendKind.ELASTICSEARCH, resolved.backend());
        assertEquals(1, resolved.materials().size());
        assertEquals("books-elasticsearch", resolved.materials().getFirst().name());
    }

    @Test
    void emptyMaterialTypesAreRejectedWithStableCode() {
        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> resolverForSample().resolve(List.of()));

        assertEquals(ProblemCode.EMPTY_MATERIAL_TYPES, thrown.code());
    }

    @Test
    void unknownMaterialTypesAreRejectedWithStableCode() {
        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> resolverForSample().resolve(List.of("missing-material")));

        assertEquals(ProblemCode.UNKNOWN_MATERIAL_TYPE, thrown.code());
        assertEquals("missing-material", thrown.details().get("materialType"));
    }

    @Test
    void mixedBackendMaterialTypesAreRejectedWithStableCode() {
        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> resolverForSample().resolve(List.of("books-elasticsearch", "journals-solr")));

        assertEquals(ProblemCode.MIXED_BACKEND_MATERIAL_TYPES, thrown.code());
        assertTrue(thrown.details().toString().contains("elasticsearch"));
        assertTrue(thrown.details().toString().contains("solr"));
    }

    @Test
    void badBackendValueIsRejectedBeforeActivation() throws Exception {
        Path config = writeConfig("""
                {
                  "version": "bad-backend",
                  "materials": {
                    "books-opensearch": {
                      "backend": "opensearch",
                      "mappingFile": "sample-mappings.json",
                      "target": { "index": "books_v1" }
                    }
                  }
                }
                """);

        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> FileBackedConfigRepository.loadSnapshot(REPOSITORY_ROOT, config));

        assertEquals(ProblemCode.CONFIG_SCHEMA_INVALID, thrown.code());
    }

    @Test
    void missingElasticsearchIndexIsRejectedBeforeActivation() throws Exception {
        Path config = writeConfig("""
                {
                  "version": "missing-index",
                  "materials": {
                    "books-elasticsearch": {
                      "backend": "elasticsearch",
                      "mappingFile": "sample-mappings.json",
                      "target": {}
                    }
                  }
                }
                """);

        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> FileBackedConfigRepository.loadSnapshot(REPOSITORY_ROOT, config));

        assertEquals(ProblemCode.CONFIG_SCHEMA_INVALID, thrown.code());
    }

    @Test
    void missingSolrCoreIsRejectedBeforeActivation() throws Exception {
        Path config = writeConfig("""
                {
                  "version": "missing-core",
                  "materials": {
                    "journals-solr": {
                      "backend": "solr",
                      "mappingFile": "sample-mappings.json",
                      "target": {}
                    }
                  }
                }
                """);

        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> FileBackedConfigRepository.loadSnapshot(REPOSITORY_ROOT, config));

        assertEquals(ProblemCode.CONFIG_SCHEMA_INVALID, thrown.code());
    }

    @Test
    void missingConfigFileProducesFailedStatusSnapshot() {
        Path missingConfig = tempDir.resolve("missing-material-config.json");

        FileBackedConfigRepository repository = new FileBackedConfigRepository(REPOSITORY_ROOT, missingConfig);

        assertFalse(repository.status().active());
        assertEquals(ProblemCode.CONFIG_LOAD_FAILED.name(), repository.status().lastErrorCode());
        TranslatorException thrown = assertThrows(TranslatorException.class, repository::activeSnapshot);
        assertEquals(ProblemCode.CONFIG_LOAD_FAILED, thrown.code());
    }

    @Test
    void missingReferencedMappingFileNamesMaterialWithoutLeakingAbsolutePaths() throws Exception {
        Path config = writeConfig("""
                {
                  "version": "missing-mapping",
                  "materials": {
                    "books-elasticsearch": {
                      "backend": "elasticsearch",
                      "mappingFile": "missing-mappings.json",
                      "target": { "index": "books_v1" }
                    }
                  }
                }
                """);

        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> FileBackedConfigRepository.loadSnapshot(REPOSITORY_ROOT, config));

        assertEquals(ProblemCode.CONFIG_LOAD_FAILED, thrown.code());
        assertTrue(thrown.getMessage().contains("books-elasticsearch"));
        assertTrue(thrown.getMessage().contains("missing-mappings.json"));
        assertFalse(thrown.getMessage().contains(REPOSITORY_ROOT.toString()));
    }

    @Test
    void activeRepositoryDoesNotRereadConfigUntilReload() throws Exception {
        Path config = writeConfig(Files.readString(SAMPLE_CONFIG));
        FileBackedConfigRepository repository = new FileBackedConfigRepository(REPOSITORY_ROOT, config);
        String loadedHash = repository.status().hash();

        Files.writeString(config, "{\"version\":\"now-broken\",\"materials\":{}}");

        assertTrue(repository.status().active());
        assertEquals(loadedHash, repository.status().hash());
        assertEquals("sample-material-config-v1", repository.activeSnapshot().version());
    }

    private static MaterialResolver resolverForSample() {
        return new MaterialResolver(FileBackedConfigRepository.loadSnapshot(REPOSITORY_ROOT, SAMPLE_CONFIG));
    }

    private Path writeConfig(String json) throws Exception {
        Path config = tempDir.resolve("material-config.json");
        Files.writeString(config, json);
        return config;
    }
}
