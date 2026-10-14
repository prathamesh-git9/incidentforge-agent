package dev.incidentforge.patch;

import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.domain.Artifacts.FileEdit;
import dev.incidentforge.domain.Artifacts.NewFile;
import dev.incidentforge.domain.Artifacts.ProposedPatch;
import dev.incidentforge.repo.RepositoryReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deterministic gate between a model-proposed patch and anything that executes or publishes it.
 * SECURITY violations (paths outside the allowlist, build/CI files, dangerous APIs) stop the run
 * for a human immediately; QUALITY violations may be fed back to the fixer within its budget.
 */
@Component
public class PatchPolicy {

    public enum Severity { SECURITY, QUALITY }

    public record Violation(Severity severity, String message) {
    }

    private final IncidentForgeProperties.PatchRules rules;

    public PatchPolicy(IncidentForgeProperties properties) {
        this.rules = properties.patch();
    }

    public List<Violation> check(ProposedPatch patch, RepositoryReader repo) {
        List<Violation> violations = new ArrayList<>();
        if (patch.edits().isEmpty()) {
            violations.add(new Violation(Severity.QUALITY, "patch changes no production code"));
        }
        Set<String> paths = new HashSet<>();
        for (FileEdit edit : patch.edits()) {
            checkPath(edit.path(), rules.editablePrefixes(), violations);
            paths.add(edit.path());
            checkContent(edit.path(), edit.replace(), violations);
            if (isSafe(edit.path())) {
                if (!repo.exists(edit.path())) {
                    violations.add(new Violation(Severity.QUALITY, "edit target does not exist: " + edit.path()));
                } else {
                    int occurrences = countOccurrences(repo.readAll(edit.path()), normalize(edit.search()));
                    if (occurrences != 1) {
                        violations.add(new Violation(Severity.QUALITY, "search text must occur exactly once in "
                                + edit.path() + " (found " + occurrences + ")"));
                    }
                }
            }
        }
        for (NewFile file : patch.newFiles()) {
            checkPath(file.path(), rules.newFilePrefixes(), violations);
            paths.add(file.path());
            checkContent(file.path(), file.content(), violations);
            if (isSafe(file.path()) && repo.exists(file.path())) {
                violations.add(new Violation(Severity.QUALITY, "new file already exists: " + file.path()));
            }
            String expectedName = patch.reproductionTestClass() + ".java";
            if (!file.path().endsWith("/" + expectedName) || !file.content().contains("@Test")) {
                violations.add(new Violation(Severity.QUALITY,
                        "new file must be the reproduction test " + expectedName + " containing @Test"));
            }
        }
        if (patch.newFiles().isEmpty()) {
            violations.add(new Violation(Severity.QUALITY, "a reproduction test is required"));
        }
        if (paths.size() > rules.maxFiles()) {
            violations.add(new Violation(Severity.QUALITY, "patch touches " + paths.size() + " files (max "
                    + rules.maxFiles() + ")"));
        }
        return violations;
    }

    public List<Violation> checkSize(int changedLines) {
        if (changedLines > rules.maxChangedLines()) {
            return List.of(new Violation(Severity.QUALITY, "patch changes " + changedLines + " lines (max "
                    + rules.maxChangedLines() + ")"));
        }
        return List.of();
    }

    private void checkPath(String path, List<String> allowedPrefixes, List<Violation> violations) {
        if (!isSafe(path)) {
            violations.add(new Violation(Severity.SECURITY, "unsafe path: " + path));
            return;
        }
        if (!path.endsWith(".java")) {
            violations.add(new Violation(Severity.SECURITY, "only Java sources may be changed: " + path));
        }
        if (allowedPrefixes.stream().noneMatch(path::startsWith)) {
            violations.add(new Violation(Severity.SECURITY, "path outside the allowlisted area: " + path));
        }
    }

    private void checkContent(String path, String text, List<Violation> violations) {
        for (String forbidden : rules.forbiddenPatterns()) {
            if (text != null && text.contains(forbidden)) {
                violations.add(new Violation(Severity.SECURITY, "forbidden construct '" + forbidden + "' in " + path));
            }
        }
    }

    private static boolean isSafe(String path) {
        try {
            RepositoryReader.normalize(path);
            return !path.startsWith(".") && !path.contains("/.");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
