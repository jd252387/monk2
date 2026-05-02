package dev.monk.translator.problem;

/**
 * Stable machine-readable domain codes that later HTTP Problem Details mappers can expose.
 */
public enum ProblemCode {
    EMPTY_MATERIAL_TYPES,
    UNKNOWN_MATERIAL_TYPE,
    MIXED_BACKEND_MATERIAL_TYPES,
    CONFIG_LOAD_FAILED,
    CONFIG_SCHEMA_INVALID,
    CONFIG_SEMANTIC_INVALID,
    VALIDATION_ERROR,
    UNKNOWN_FIELD,
    UNSUPPORTED_QUERY_SEMANTICS,
    INTERNAL_ERROR
}
