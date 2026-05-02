package dev.monk.translator.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ConfigStatusResourceTest {

    @Test
    void exposesActiveConfigDiagnosticsThroughHttp() {
        String body = given()
                .accept(ContentType.JSON)
        .when()
                .get("/v1/config/status")
        .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("active", equalTo(true))
                .body("version", equalTo("sample-material-config-v1"))
                .body("hash", notNullValue())
                .body("loadedAt", notNullValue())
                .body("materialCount", equalTo(2))
                .body("backendCounts.elasticsearch", equalTo(1))
                .body("backendCounts.solr", equalTo(1))
                .body("lastErrorCode", nullValue())
                .body("lastErrorDetail", nullValue())
                .extract()
                .asString();

        assertFalse(body.contains(Path.of("").toAbsolutePath().normalize().toString()), body);
        assertFalse(body.contains("material-config.schema.json"), body);
    }
}
