package dev.monk.translator.api;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import dev.monk.translator.problem.ProblemCode;
import dev.monk.translator.problem.TranslatorException;
import dev.monk.translator.translation.TranslateService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/**
 * Runtime HTTP boundary for S01 translation requests.
 * Backend choice is intentionally not a request field; materialTypes drive routing.
 */
@Path("/v1/translate")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TranslateResource {
    private final TranslateService translateService;
    private final ObjectReader requestReader;

    @Inject
    public TranslateResource(TranslateService translateService, ObjectMapper objectMapper) {
        this.translateService = translateService;
        this.requestReader = objectMapper.readerFor(TranslateRequest.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @POST
    public TranslateEnvelope translate(String body) {
        return translateService.translate(parseRequest(body));
    }

    private TranslateRequest parseRequest(String body) {
        if (body == null || body.isBlank()) {
            throw new TranslatorException(ProblemCode.VALIDATION_ERROR, "JSON request body is required");
        }
        try {
            return requestReader.readValue(body);
        } catch (UnrecognizedPropertyException exception) {
            String propertyName = exception.getPropertyName();
            throw new TranslatorException(
                    ProblemCode.VALIDATION_ERROR,
                    "Unknown request property: " + propertyName,
                    Map.of("property", propertyName),
                    exception);
        } catch (JsonParseException exception) {
            throw new TranslatorException(
                    ProblemCode.VALIDATION_ERROR,
                    "Malformed JSON request body",
                    Map.of(),
                    exception);
        } catch (JsonProcessingException exception) {
            throw new TranslatorException(
                    ProblemCode.VALIDATION_ERROR,
                    "Request JSON does not match the translate schema",
                    Map.of("cause", exception.getClass().getSimpleName()),
                    exception);
        }
    }
}
