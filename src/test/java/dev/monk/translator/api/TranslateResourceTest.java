package dev.monk.translator.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.monk.translator.problem.ProblemCode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.util.Locale;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TranslateResourceTest {

    @Test
    void translatesElasticsearchMaterialThroughHttp() {
        given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(translateRequest("[\"books-elasticsearch\"]", "title", textPayload("monastic architecture")))
        .when()
                .post("/v1/translate")
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("requestId", equalTo("request-1"))
                .body("queryName", equalTo("phrase text smoke test"))
                .body("backend", equalTo("elasticsearch"))
                .body("targets", hasSize(1))
                .body("targets[0].materialType", equalTo("books-elasticsearch"))
                .body("targets[0].backend", equalTo("elasticsearch"))
                .body("targets[0].index", equalTo("books_v1"))
                .body("targets[0].mappingFile", equalTo("sample-mappings.json"))
                .body("config.version", equalTo("sample-material-config-v1"))
                .body("config.hash", notNullValue())
                .body("query.match_phrase.title_txt", equalTo("monastic architecture"))
                .body("diagnostics.destinationField", equalTo("title_txt"))
                .body("diagnostics.phraseCount", equalTo(1));
    }

    @Test
    void translatesSameQueryToSolrByChangingOnlyMaterialTypes() {
        given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(translateRequest("[\"journals-solr\"]", "title", textPayload("monastic architecture")))
        .when()
                .post("/v1/translate")
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("backend", equalTo("solr"))
                .body("targets", hasSize(1))
                .body("targets[0].materialType", equalTo("journals-solr"))
                .body("targets[0].backend", equalTo("solr"))
                .body("targets[0].core", equalTo("journals_v1"))
                .body("targets[0].mappingFile", equalTo("sample-mappings.json"))
                .body("config.version", equalTo("sample-material-config-v1"))
                .body("query.q", equalTo("title_txt:\"monastic architecture\""))
                .body("query.defType", equalTo("lucene"))
                .body("diagnostics.destinationField", equalTo("title_txt"));
    }

    @Test
    void rejectsMixedBackendMaterialSelectionAsProblemDetails() {
        assertProblem(
                translateRequest("[\"books-elasticsearch\", \"journals-solr\"]", "title", textPayload("monastic architecture")),
                422,
                ProblemCode.MIXED_BACKEND_MATERIAL_TYPES)
                .body("details.backendMaterials.elasticsearch[0]", equalTo("books-elasticsearch"))
                .body("details.backendMaterials.solr[0]", equalTo("journals-solr"));
    }

    @Test
    void rejectsUnknownMaterialAsProblemDetails() {
        assertProblem(
                translateRequest("[\"missing-material\"]", "title", textPayload("monastic architecture")),
                422,
                ProblemCode.UNKNOWN_MATERIAL_TYPE)
                .body("details.materialType", equalTo("missing-material"));
    }

    @Test
    void rejectsMalformedJsonAsValidationProblem() {
        assertProblem("{", 400, ProblemCode.VALIDATION_ERROR)
                .body("detail", equalTo("Malformed JSON request body"));
    }

    @Test
    void rejectsMissingMaterialTypesAsProblemDetails() {
        assertProblem(
                """
                {
                  "id": "request-1",
                  "name": "phrase text smoke test",
                  "query": {
                    "field": "title",
                    "data": { "type": "text", "phrases": ["monastic architecture"] }
                  }
                }
                """,
                400,
                ProblemCode.EMPTY_MATERIAL_TYPES);
    }

    @Test
    void rejectsEmptyMaterialTypesAsProblemDetails() {
        assertProblem(
                translateRequest("[]", "title", textPayload("monastic architecture")),
                400,
                ProblemCode.EMPTY_MATERIAL_TYPES);
    }

    @Test
    void rejectsCallerBackendOverrideAsUnknownPropertyProblem() {
        assertProblem(
                """
                {
                  "id": "request-1",
                  "name": "phrase text smoke test",
                  "backend": "solr",
                  "materialTypes": ["books-elasticsearch"],
                  "query": {
                    "field": "title",
                    "data": { "type": "text", "phrases": ["monastic architecture"] }
                  }
                }
                """,
                400,
                ProblemCode.VALIDATION_ERROR)
                .body("detail", equalTo("Unknown request property: backend"))
                .body("details.property", equalTo("backend"));
    }

    @Test
    void rejectsUnknownRootPropertyAsValidationProblem() {
        assertProblem(
                """
                {
                  "id": "request-1",
                  "name": "phrase text smoke test",
                  "materialTypes": ["books-elasticsearch"],
                  "unexpected": true,
                  "query": {
                    "field": "title",
                    "data": { "type": "text", "phrases": ["monastic architecture"] }
                  }
                }
                """,
                400,
                ProblemCode.VALIDATION_ERROR)
                .body("detail", equalTo("Unknown request property: unexpected"))
                .body("details.property", equalTo("unexpected"));
    }

    @Test
    void rejectsUnsupportedQueryShapeAsProblemDetails() {
        assertProblem(
                translateRequest("[\"books-elasticsearch\"]", "title", "{ \"type\": \"range\", \"gte\": 10 }"),
                422,
                ProblemCode.UNSUPPORTED_QUERY_SEMANTICS);
    }

    @Test
    void rejectsUnknownDslFieldAsProblemDetails() {
        assertProblem(
                translateRequest("[\"books-elasticsearch\"]", "missing", textPayload("monastic architecture")),
                422,
                ProblemCode.UNKNOWN_FIELD)
                .body("details.field", equalTo("missing"));
    }

    private static ValidatableResponse assertProblem(String requestBody, int status, ProblemCode code) {
        ValidatableResponse response = given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(requestBody)
        .when()
                .post("/v1/translate")
        .then()
                .statusCode(status)
                .contentType(ProblemDetailsMapper.PROBLEM_JSON)
                .body("type", equalTo(problemType(code)))
                .body("title", notNullValue())
                .body("status", equalTo(status))
                .body("code", equalTo(code.name()))
                .body("detail", notNullValue())
                .body("instance", equalTo("/v1/translate"));

        String body = response.extract().asString();
        assertNoStackTrace(body);
        return response;
    }

    private static void assertNoStackTrace(String body) {
        assertFalse(body.contains("java."), body);
        assertFalse(body.contains("Exception"), body);
        assertFalse(body.contains("at dev.monk"), body);
    }

    private static String translateRequest(String materialTypes, String field, String data) {
        return """
                {
                  "id": "request-1",
                  "name": "phrase text smoke test",
                  "materialTypes": %s,
                  "query": {
                    "field": "%s",
                    "data": %s
                  }
                }
                """.formatted(materialTypes, field, data);
    }

    private static String textPayload(String phrase) {
        return "{ \"type\": \"text\", \"phrases\": [\"" + phrase + "\"] }";
    }

    private static String problemType(ProblemCode code) {
        return "urn:monk:problem:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
