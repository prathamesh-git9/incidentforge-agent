package dev.incidentforge.domain;

/**
 * The fixed pipeline skeleton. The order is decided in code, never by a model:
 * deterministic collection first, classification before any code change, mitigation as a
 * separate decision from the permanent fix, and validation before a human sees a patch.
 */
public enum Stage {
    COLLECT_CONTEXT,
    CLASSIFY,
    INVESTIGATE,
    PLAN_MITIGATION,
    PROPOSE_FIX,
    VALIDATE,
    PUBLISH
}
