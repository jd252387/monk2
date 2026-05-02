package dev.monk.translator.translation;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Emits the S01 phrase-text query shape without contacting Solr.
 */
@ApplicationScoped
public class SolrEmitter {

    public Map<String, Object> emitPhraseText(String destinationField, List<String> phrases) {
        String query = phrases.stream()
                .map(phrase -> destinationField + ":\"" + escapePhrase(phrase) + "\"")
                .collect(Collectors.joining(" OR "));
        if (phrases.size() > 1) {
            query = "(" + query + ")";
        }

        LinkedHashMap<String, Object> solrQuery = new LinkedHashMap<>();
        solrQuery.put("q", query);
        solrQuery.put("defType", "lucene");
        return Map.copyOf(solrQuery);
    }

    private static String escapePhrase(String phrase) {
        return phrase.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
