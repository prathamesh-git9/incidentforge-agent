package dev.incidentforge.tools;

import dev.incidentforge.domain.Stage;
import java.util.Map;
import java.util.Set;

/**
 * Stage-scoped tool authorization. This lives in application code, not in a prompt: a model
 * can ask for any tool name, but only these (all read-only) are ever executed, and only in the
 * stage that needs them. There is no write tool (no flag changes, no git, no merge) at all.
 */
public final class ToolPolicy {

    private static final Map<Stage, Set<String>> ALLOWED = Map.of(
            Stage.COLLECT_CONTEXT, Set.of("logs_search", "metrics_query", "traces_search", "deploys_list", "flags_list"),
            Stage.INVESTIGATE, Set.of("logs_search", "traces_search", "deploys_list", "code_search", "code_read"),
            Stage.PROPOSE_FIX, Set.of("code_search", "code_read"));

    private ToolPolicy() {
    }

    public static Set<String> allowedFor(Stage stage) {
        return ALLOWED.getOrDefault(stage, Set.of());
    }
}
