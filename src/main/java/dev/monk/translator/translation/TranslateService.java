package dev.monk.translator.translation;

import dev.monk.translator.api.TranslateEnvelope;
import dev.monk.translator.api.TranslateEnvelope.ConfigMetadata;
import dev.monk.translator.api.TranslateEnvelope.Diagnostics;
import dev.monk.translator.api.TranslateEnvelope.TargetMetadata;
import dev.monk.translator.api.TranslateRequest;
import dev.monk.translator.config.ConfigSnapshot;
import dev.monk.translator.config.FileBackedConfigRepository;
import dev.monk.translator.config.MaterialConfig.BackendKind;
import dev.monk.translator.config.MaterialResolver;
import dev.monk.translator.config.MaterialResolver.ResolvedMaterials;
import dev.monk.translator.config.MaterialResolver.SelectedMaterial;
import dev.monk.translator.problem.ProblemCode;
import dev.monk.translator.problem.TranslatorException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates the S01 phrase-text DSL subset into backend-native query envelopes.
 */
@ApplicationScoped
public class TranslateService {
    private final FileBackedConfigRepository repository;
    private final MaterialResolver fixedResolver;
    private final DslValidator validator;
    private final ElasticsearchEmitter elasticsearchEmitter;
    private final SolrEmitter solrEmitter;

    @Inject
    public TranslateService(
            FileBackedConfigRepository repository,
            DslValidator validator,
            ElasticsearchEmitter elasticsearchEmitter,
            SolrEmitter solrEmitter) {
        this.repository = repository;
        this.fixedResolver = null;
        this.validator = validator;
        this.elasticsearchEmitter = elasticsearchEmitter;
        this.solrEmitter = solrEmitter;
    }

    public TranslateService(
            MaterialResolver fixedResolver,
            DslValidator validator,
            ElasticsearchEmitter elasticsearchEmitter,
            SolrEmitter solrEmitter) {
        this.repository = null;
        this.fixedResolver = fixedResolver;
        this.validator = validator;
        this.elasticsearchEmitter = elasticsearchEmitter;
        this.solrEmitter = solrEmitter;
    }

    public TranslateEnvelope translate(TranslateRequest request) {
        DslValidator.ValidatedTextQuery textQuery = validator.validatePhraseTextLeaf(request);
        ResolvedMaterials resolved = resolver().resolve(request.materialTypes());
        FieldBinding fieldBinding = resolveFieldBinding(resolved, textQuery.logicalField());

        Map<String, Object> backendQuery = switch (resolved.backend()) {
            case ELASTICSEARCH -> elasticsearchEmitter.emitPhraseText(fieldBinding.destinationField(), textQuery.phrases());
            case SOLR -> solrEmitter.emitPhraseText(fieldBinding.destinationField(), textQuery.phrases());
        };

        return new TranslateEnvelope(
                request.id(),
                request.name(),
                resolved.backend().jsonValue(),
                targetMetadata(resolved),
                new ConfigMetadata(resolved.configVersion(), resolved.configHash(), resolved.loadedAt()),
                backendQuery,
                List.of(),
                new Diagnostics(
                        resolved.materials().stream().map(SelectedMaterial::name).toList(),
                        textQuery.logicalField(),
                        fieldBinding.destinationField(),
                        fieldBinding.fieldType(),
                        textQuery.phrases().size()));
    }

    private MaterialResolver resolver() {
        if (fixedResolver != null) {
            return fixedResolver;
        }
        return new MaterialResolver(repository.activeSnapshot());
    }

    private static FieldBinding resolveFieldBinding(ResolvedMaterials resolved, String logicalField) {
        FieldBinding binding = null;
        for (SelectedMaterial material : resolved.materials()) {
            if (material.mapping() == null) {
                throw new TranslatorException(
                        ProblemCode.CONFIG_SEMANTIC_INVALID,
                        "Material '" + material.name() + "' has no active mapping document",
                        Map.of("materialType", material.name()));
            }
            ConfigSnapshot.MappingField mappingField = material.mapping().rootField(logicalField);
            if (mappingField == null) {
                throw new TranslatorException(
                        ProblemCode.UNKNOWN_FIELD,
                        "Unknown root query field: " + logicalField,
                        Map.of("field", logicalField, "materialType", material.name()));
            }
            validateMappingField(material, logicalField, mappingField);
            if (!supportsPhraseText(mappingField.type())) {
                throw new TranslatorException(
                        ProblemCode.UNSUPPORTED_QUERY_SEMANTICS,
                        "Field '" + logicalField + "' does not support phrase-text queries",
                        Map.of(
                                "field", logicalField,
                                "fieldType", mappingField.type(),
                                "materialType", material.name()));
            }

            FieldBinding candidate = new FieldBinding(mappingField.destinationField(), mappingField.type());
            if (binding == null) {
                binding = candidate;
            } else if (!binding.equals(candidate)) {
                throw new TranslatorException(
                        ProblemCode.CONFIG_SEMANTIC_INVALID,
                        "Selected material mappings disagree for field '" + logicalField + "'",
                        Map.of("field", logicalField));
            }
        }
        if (binding == null) {
            throw new TranslatorException(ProblemCode.INTERNAL_ERROR, "No material mappings were selected");
        }
        return binding;
    }

    private static void validateMappingField(
            SelectedMaterial material,
            String logicalField,
            ConfigSnapshot.MappingField mappingField) {
        if (isBlank(mappingField.type()) || isBlank(mappingField.destinationField())) {
            throw new TranslatorException(
                    ProblemCode.CONFIG_SEMANTIC_INVALID,
                    "Material '" + material.name() + "' mapping for field '" + logicalField + "' is incomplete",
                    Map.of("field", logicalField, "materialType", material.name()));
        }
    }

    private static List<TargetMetadata> targetMetadata(ResolvedMaterials resolved) {
        ArrayList<TargetMetadata> targets = new ArrayList<>();
        for (SelectedMaterial material : resolved.materials()) {
            if (material.target() == null || isBlank(material.target().targetName(material.backend()))) {
                throw new TranslatorException(
                        ProblemCode.CONFIG_SEMANTIC_INVALID,
                        "Material '" + material.name() + "' has incomplete backend target metadata",
                        Map.of("materialType", material.name()));
            }
            targets.add(new TargetMetadata(
                    material.name(),
                    material.backend().jsonValue(),
                    material.backend() == BackendKind.ELASTICSEARCH ? material.target().index() : null,
                    material.backend() == BackendKind.SOLR ? material.target().core() : null,
                    material.mappingFile()));
        }
        return List.copyOf(targets);
    }

    private static boolean supportsPhraseText(String fieldType) {
        return "string".equals(fieldType) || "freetext".equals(fieldType);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record FieldBinding(String destinationField, String fieldType) {
    }
}
