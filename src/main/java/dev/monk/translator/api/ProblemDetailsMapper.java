package dev.monk.translator.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.monk.translator.problem.ProblemCode;
import dev.monk.translator.problem.TranslatorException;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Locale;
import java.util.Map;

/**
 * Converts domain, JSON, HTTP, and fallback failures into non-leaky Problem Details.
 */
@Provider
@Priority(Priorities.USER)
public class ProblemDetailsMapper implements ExceptionMapper<Throwable> {
    public static final String PROBLEM_JSON = "application/problem+json";

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof TranslatorException translatorException) {
            ProblemCode code = translatorException.code();
            return problemResponse(
                    code,
                    statusFor(code),
                    safeDetail(translatorException.getMessage()),
                    translatorException.details());
        }
        if (exception instanceof JsonProcessingException) {
            return problemResponse(
                    ProblemCode.VALIDATION_ERROR,
                    Response.Status.BAD_REQUEST.getStatusCode(),
                    "Malformed JSON request body",
                    Map.of());
        }
        if (exception instanceof WebApplicationException webApplicationException) {
            int status = webApplicationException.getResponse().getStatus();
            ProblemCode code = status >= 500 ? ProblemCode.INTERNAL_ERROR : ProblemCode.VALIDATION_ERROR;
            return problemResponse(code, status, httpDetail(status), Map.of());
        }
        return problemResponse(
                ProblemCode.INTERNAL_ERROR,
                Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                "Unexpected translator error",
                Map.of());
    }

    private Response problemResponse(ProblemCode code, int status, String detail, Map<String, ?> details) {
        ProblemDetails problem = new ProblemDetails(
                typeFor(code),
                titleFor(code),
                status,
                code.name(),
                detail,
                instance(),
                details);
        return Response.status(status)
                .type(PROBLEM_JSON)
                .entity(problem)
                .build();
    }

    private static int statusFor(ProblemCode code) {
        return switch (code) {
            case VALIDATION_ERROR, EMPTY_MATERIAL_TYPES -> Response.Status.BAD_REQUEST.getStatusCode();
            case UNKNOWN_MATERIAL_TYPE, MIXED_BACKEND_MATERIAL_TYPES, UNKNOWN_FIELD, UNSUPPORTED_QUERY_SEMANTICS -> 422;
            case CONFIG_LOAD_FAILED, CONFIG_SCHEMA_INVALID, CONFIG_SEMANTIC_INVALID -> Response.Status.SERVICE_UNAVAILABLE.getStatusCode();
            case INTERNAL_ERROR -> Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();
        };
    }

    private static String typeFor(ProblemCode code) {
        return "urn:monk:problem:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String titleFor(ProblemCode code) {
        return switch (code) {
            case EMPTY_MATERIAL_TYPES -> "Empty material types";
            case UNKNOWN_MATERIAL_TYPE -> "Unknown material type";
            case MIXED_BACKEND_MATERIAL_TYPES -> "Mixed backend material types";
            case CONFIG_LOAD_FAILED -> "Configuration load failed";
            case CONFIG_SCHEMA_INVALID -> "Configuration schema invalid";
            case CONFIG_SEMANTIC_INVALID -> "Configuration semantic invalid";
            case VALIDATION_ERROR -> "Validation error";
            case UNKNOWN_FIELD -> "Unknown field";
            case UNSUPPORTED_QUERY_SEMANTICS -> "Unsupported query semantics";
            case INTERNAL_ERROR -> "Internal error";
        };
    }

    private String instance() {
        if (uriInfo == null) {
            return null;
        }
        String path = uriInfo.getPath();
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String safeDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "Translator request failed";
        }
        return detail;
    }

    private static String httpDetail(int status) {
        Response.Status statusEnum = Response.Status.fromStatusCode(status);
        if (statusEnum == null) {
            return "HTTP " + status;
        }
        return "HTTP " + status + ": " + statusEnum.getReasonPhrase();
    }
}
