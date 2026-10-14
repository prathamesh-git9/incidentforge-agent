package dev.incidentforge.domain;

import java.util.List;

/** Typed stage artifacts. Model-produced ones are schema-validated before they are deserialized. */
public final class Artifacts {

    private Artifacts() {
    }

    public record Classification(String category, boolean codeFixRecommended, double confidence,
                                 String rationale, List<String> evidenceIds) {
    }

    public record Claim(String statement, List<String> evidenceIds) {
    }

    public record SuspectLocation(String path, int line, String symbol, boolean verified) {
    }

    /** Output of one focused investigator. */
    public record Finding(String investigator, String hypothesis, String suspectPath, Integer suspectLine,
                          String suspectSymbol, List<Claim> claims, double confidence) {
    }

    /** Deterministic consolidation of the investigators' findings. */
    public record RootCauseAnalysis(String summary, String category, double confidence,
                                    SuspectLocation suspectLocation, String contributingChange,
                                    List<Claim> claims, List<Claim> rejectedClaims,
                                    List<Finding> findings, List<String> investigatorFailures) {
    }

    /** Mitigation is recommended, never executed: production writes are outside the agent's authority. */
    public record Mitigation(String action, String target, String rationale, String risk,
                             List<String> evidenceIds, List<String> alternatives, boolean executed) {
    }

    public record FileEdit(String path, String search, String replace) {
    }

    public record NewFile(String path, String content) {
    }

    public record ProposedPatch(String summary, List<FileEdit> edits, List<NewFile> newFiles,
                                String reproductionTestClass, String riskNotes) {

        public ProposedPatch {
            edits = edits == null ? List.of() : List.copyOf(edits);
            newFiles = newFiles == null ? List.of() : List.copyOf(newFiles);
        }
    }

    /** A policy-approved patch with its rendered diff; the hash is what a reviewer approves. */
    public record PatchArtifact(ProposedPatch patch, String unifiedDiff, String sha256, int changedLines,
                                int attempt) {
    }

    public record CommandRun(List<String> command, int exitCode, boolean timedOut, long durationMs,
                             String outputTail) {
    }

    /** Reproduce-then-fix: the reproduction test must fail before the fix and the full suite pass after. */
    public record ValidationReport(boolean reproducedBeforeFix, CommandRun beforeFix, CommandRun afterFix,
                                   boolean passed, String verdict, int attempt) {
    }

    public record PublishResult(String mode, String outcome, String url, String branch, Integer number,
                                String note) {
    }
}
