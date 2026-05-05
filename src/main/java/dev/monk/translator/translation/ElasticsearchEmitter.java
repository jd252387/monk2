package dev.monk.translator.translation;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits the S01 phrase-text query shape without contacting Elasticsearch.
 */
@ApplicationScoped
public class ElasticsearchEmitter {

    public Map<String, Object> emitPhraseText(String destinationField, List<String> phrases) {
        if (phrases.size() == 1) {
            return Map.copyOf(Map.of("match_phrase", Map.of(destinationField, phrases.getFirst())));
        }

        ArrayList<Map<String, Object>> should = new ArrayList<>();
        for (String phrase : phrases) {
            should.add(Map.of("match_phrase", Map.of(destinationField, phrase)));
        }
        LinkedHashMap<String, Object> bool = new LinkedHashMap<>();
        bool.put("should", List.copyOf(should));
        bool.put("minimum_should_match", 1);
        return Map.copyOf(Map.of("bool", bool));
    }

    private static Map<String, Object> Map.copyOf(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
