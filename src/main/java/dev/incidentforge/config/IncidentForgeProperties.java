package dev.incidentforge.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunables live in application.yml; nothing here has a hidden default so the
 * effective budgets and allowlists are reviewable in one file.
 */
@ConfigurationProperties(prefix = "incidentforge")
public record IncidentForgeProperties(
        String bus,
        Path workDir,
        Map<String, ServiceProfile> services,
        Llm llm,
        Map<String, StageBudget> budgets,
        ContextLimits context,
        PatchRules patch,
        Validation validation,
        GitHub github,
        Mcp mcp,
        Pipeline pipeline,
        Security security) {

    /**
     * A service IncidentForge is allowed to investigate: the single repository it may patch (and
     * where in it), and what the deterministic collector fetches for it.
     */
    public record ServiceProfile(String domain, Path repository, String owner, String repositoryId,
                                 List<String> editablePrefixes, List<String> newFilePrefixes,
                                 List<String> metrics, TraceQuery traces) {
    }

    /** Which spans the collector asks for: errors, or slow spans regardless of status. */
    public record TraceQuery(String status, Integer minDurationMs) {
    }

    public record Llm(String provider, Anthropic anthropic) {

        public record Anthropic(String apiKey, String baseUrl, Duration timeout, int maxRetries,
                                boolean refusalFallback) {
        }
    }

    /** Per-stage model budget: the RCA stages get a cheaper model and fewer turns than the fix stage. */
    public record StageBudget(String model, String effort, int maxTurns, int maxOutputTokens,
                              int maxInputChars, Duration timeout) {
    }

    public record ContextLimits(Duration logLookback, Duration metricLookback, int pageSize, int maxLogLines,
                                int maxSignatures, int maxEvidenceChars, int maxToolResultChars) {
    }

    public record PatchRules(int maxFiles, int maxChangedLines, List<String> forbiddenPatterns) {
    }

    public record Validation(String mavenExecutable, Duration timeout, boolean offline, int maxOutputChars) {
    }

    public record GitHub(String mode, String apiUrl, String repository, String baseBranch, String token,
                         String pathPrefix, Duration timeout, Path localOutbox, int reconcileAttempts) {
    }

    public record Mcp(String endpoint, String clientUsername, String clientPassword, Duration requestTimeout) {
    }

    public record Pipeline(int maxFixAttempts, int maxCorrectionRounds, int maxToolCallsPerTurn,
                           Duration investigatorTimeout, int transientRetries, Duration transientBackoff) {
    }

    public record Security(Map<String, User> users) {

        public record User(String password, List<String> roles) {
        }
    }

    public StageBudget budget(String stage) {
        StageBudget budget = budgets.get(stage);
        if (budget == null) {
            throw new IllegalStateException("No budget configured for stage " + stage);
        }
        return budget;
    }
}
