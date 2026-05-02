package dev.monk.translator.translation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.monk.translator.api.TranslateRequest;
import dev.monk.translator.problem.ProblemCode;
import dev.monk.translator.problem.TranslatorException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * S01 semantic validator for the one supported root-level phrase-text leaf query.
 */
@ApplicationScoped
public class DslValidator {

    public ValidatedTextQuery validatePhraseTextLeaf(TranslateRequest request) {
        if (request == null) {
            throw new TranslatorException(ProblemCode.VALIDATION_ERROR, "Translate request is required");
        }
        TranslateRequest.QueryNode query = request.query();
        if (query == null) {
            throw new TranslatorException(ProblemCode.VALIDATION_ERROR, "query is required");
        }
        if (query.minimumMatch() != null || Boolean.TRUE.equals(query.isNot()) || isBlank(query.field())) {
            throw unsupported("S01 supports only a root-level positive field query");
        }

        JsonNode data = query.data();
        if (data == null || data.isNull() || !data.isObject()) {
            throw new TranslatorException(ProblemCode.VALIDATION_ERROR, "query.data must be an object payload");
        }

        JsonNode typeNode = data.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            throw new TranslatorException(ProblemCode.VALIDATION_ERROR, "query.data.type is required");
        }
        if (!"text".equals(typeNode.asText())) {
            throw unsupported("S01 supports only text phrase payloads", Map.of("type", typeNode.asText()));
        }

        JsonNode phrasesNode = data.get("phrases");
        if (phrasesNode == null || !phrasesNode.isArray()) {
            throw new TranslatorException(ProblemCode.VALIDATION_ERROR, "text query phrases must be an array");
        }
        if (phrasesNode.isEmpty()) {
            throw new TranslatorException(ProblemCode.VALIDATION_ERROR, "text query phrases must not be empty");
        }

        ArrayList<String> phrases = new ArrayList<>();
        for (JsonNode phraseNode : phrasesNode) {
            if (!phraseNode.isTextual() || phraseNode.asText().isBlank()) {
                throw new TranslatorException(ProblemCode.VALIDATION_ERROR, "text query phrases must be non-blank strings");
            }
            phrases.add(phraseNode.asText());
        }

        return new ValidatedTextQuery(query.field(), List.copyOf(phrases));
    }

    private static TranslatorException unsupported(String message) {
        return unsupported(message, Map.of());
    }

    private static TranslatorException unsupported(String message, Map<String, ?> details) {
        return new TranslatorException(ProblemCode.UNSUPPORTED_QUERY_SEMANTICS, message, details);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ValidatedTextQuery(
            String logicalField,
            List<String> phrases) {
    }
}
