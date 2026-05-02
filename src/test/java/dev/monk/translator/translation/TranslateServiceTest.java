package dev.monk.translator.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.monk.translator.api.TranslateEnvelope;
import dev.monk.translator.api.TranslateRequest;
import dev.monk.translator.config.FileBackedConfigRepository;
import dev.monk.translator.config.MaterialResolver;
import dev.monk.translator.problem.ProblemCode;
import dev.monk.translator.problem.TranslatorException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TranslateServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path SAMPLE_CONFIG = REPOSITORY_ROOT.resolve("sample-material-config.json");

    @Test
    void emitsElasticsearchPhraseEnvelopeWithMappedDestinationAndDiagnostics() {
        TranslateEnvelope envelope = service().translate(request(
                List.of("books-elasticsearch"),
                "title",
                textPayload("monastic architecture")));

        assertEquals("request-1", envelope.requestId());
        assertEquals("phrase text smoke test", envelope.queryName());
        assertEquals("elasticsearch", envelope.backend());
        assertEquals("sample-material-config-v1", envelope.config().version());
        assertNotNull(envelope.config().hash());
        assertEquals(64, envelope.config().hash().length());
        assertNotNull(envelope.config().loadedAt());
        assertEquals(1, envelope.targets().size());
        assertEquals("books-elasticsearch", envelope.targets().getFirst().materialType());
        assertEquals("elasticsearch", envelope.targets().getFirst().backend());
        assertEquals("books_v1", envelope.targets().getFirst().index());
        assertEquals("sample-mappings.json", envelope.targets().getFirst().mappingFile());
        assertTrue(envelope.warnings().isEmpty(), "happy path should not warn");
        assertEquals(List.of("books-elasticsearch"), envelope.diagnostics().materialTypes());
        assertEquals("title", envelope.diagnostics().logicalField());
        assertEquals("title_txt", envelope.diagnostics().destinationField());
        assertEquals("freetext", envelope.diagnostics().fieldType());
        assertEquals(1, envelope.diagnostics().phraseCount());

        Map<?, ?> query = assertInstanceOf(Map.class, envelope.query());
        Map<?, ?> matchPhrase = assertInstanceOf(Map.class, query.get("match_phrase"));
        assertEquals("monastic architecture", matchPhrase.get("title_txt"));
    }

    @Test
    void emitsSolrPhraseEnvelopeWithEscapedMultiplePhraseQueryAndTargetMetadata() {
        TranslateEnvelope envelope = service().translate(request(
                List.of("journals-solr"),
                "title",
                textPayload("monastic architecture", "abbey \"plans\"")));

        assertEquals("solr", envelope.backend());
        assertEquals(1, envelope.targets().size());
        assertEquals("journals-solr", envelope.targets().getFirst().materialType());
        assertEquals("solr", envelope.targets().getFirst().backend());
        assertEquals("journals_v1", envelope.targets().getFirst().core());
        assertEquals("sample-mappings.json", envelope.targets().getFirst().mappingFile());
        assertEquals("title_txt", envelope.diagnostics().destinationField());
        assertEquals(2, envelope.diagnostics().phraseCount());

        Map<?, ?> query = assertInstanceOf(Map.class, envelope.query());
        assertEquals("(title_txt:\"monastic architecture\" OR title_txt:\"abbey \\\"plans\\\"\")", query.get("q"));
        assertEquals("lucene", query.get("defType"));
    }

    @Test
    void rejectsMissingQueryWithStableValidationCode() {
        TranslateRequest request = new TranslateRequest(
                "request-1",
                "missing query",
                List.of("books-elasticsearch"),
                null);

        TranslatorException thrown = assertThrows(TranslatorException.class, () -> service().translate(request));

        assertEquals(ProblemCode.VALIDATION_ERROR, thrown.code());
    }

    @Test
    void rejectsEmptyPhrasesWithStableValidationCode() {
        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> service().translate(request(List.of("books-elasticsearch"), "title", textPayload())));

        assertEquals(ProblemCode.VALIDATION_ERROR, thrown.code());
    }

    @Test
    void rejectsNonStringPhrasesWithStableValidationCode() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("type", "text");
        payload.putArray("phrases").add(42);

        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> service().translate(request(List.of("books-elasticsearch"), "title", payload)));

        assertEquals(ProblemCode.VALIDATION_ERROR, thrown.code());
    }

    @Test
    void rejectsBooleanRootQueryAsUnsupportedSemantics() {
        ArrayNode booleanData = MAPPER.createArrayNode();
        booleanData.add(MAPPER.createArrayNode());
        TranslateRequest request = request(
                List.of("books-elasticsearch"),
                "",
                booleanData);

        TranslatorException thrown = assertThrows(TranslatorException.class, () -> service().translate(request));

        assertEquals(ProblemCode.UNSUPPORTED_QUERY_SEMANTICS, thrown.code());
    }

    @Test
    void rejectsRangePayloadAsUnsupportedSemantics() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("type", "range");
        payload.put("gte", 10);

        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> service().translate(request(List.of("books-elasticsearch"), "pageCount", payload)));

        assertEquals(ProblemCode.UNSUPPORTED_QUERY_SEMANTICS, thrown.code());
    }

    @Test
    void rejectsUnknownFieldBeforeEmittingRawUserFieldNames() {
        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> service().translate(request(List.of("books-elasticsearch"), "missing", textPayload("monastic architecture"))));

        assertEquals(ProblemCode.UNKNOWN_FIELD, thrown.code());
        assertEquals("missing", thrown.details().get("field"));
    }

    @Test
    void rejectsUnsupportedMappedFieldTypeForPhraseText() {
        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> service().translate(request(List.of("books-elasticsearch"), "pageCount", textPayload("12"))));

        assertEquals(ProblemCode.UNSUPPORTED_QUERY_SEMANTICS, thrown.code());
        assertEquals("number", thrown.details().get("fieldType"));
    }

    @Test
    void rejectsSubdocumentRootFieldForPhraseText() {
        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> service().translate(request(List.of("books-elasticsearch"), "author", textPayload("cassian"))));

        assertEquals(ProblemCode.UNSUPPORTED_QUERY_SEMANTICS, thrown.code());
        assertEquals("subdocument", thrown.details().get("fieldType"));
    }

    @Test
    void propagatesUnknownMaterialResolutionCode() {
        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> service().translate(request(List.of("missing-material"), "title", textPayload("monastic architecture"))));

        assertEquals(ProblemCode.UNKNOWN_MATERIAL_TYPE, thrown.code());
    }

    @Test
    void propagatesMixedBackendResolutionCode() {
        TranslatorException thrown = assertThrows(TranslatorException.class,
                () -> service().translate(request(
                        List.of("books-elasticsearch", "journals-solr"),
                        "title",
                        textPayload("monastic architecture"))));

        assertEquals(ProblemCode.MIXED_BACKEND_MATERIAL_TYPES, thrown.code());
        assertTrue(thrown.details().toString().contains("elasticsearch"));
        assertTrue(thrown.details().toString().contains("solr"));
    }

    private static TranslateService service() {
        return new TranslateService(
                new MaterialResolver(FileBackedConfigRepository.loadSnapshot(REPOSITORY_ROOT, SAMPLE_CONFIG)),
                new DslValidator(),
                new ElasticsearchEmitter(),
                new SolrEmitter());
    }

    private static TranslateRequest request(List<String> materialTypes, String field, JsonNode data) {
        return new TranslateRequest(
                "request-1",
                "phrase text smoke test",
                materialTypes,
                new TranslateRequest.QueryNode(field, null, null, data));
    }

    private static JsonNode textPayload(String... phrases) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("type", "text");
        ArrayNode phraseArray = payload.putArray("phrases");
        for (String phrase : phrases) {
            phraseArray.add(phrase);
        }
        return payload;
    }
}
