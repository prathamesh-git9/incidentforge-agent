package dev.incidentforge.validation;

import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.domain.Artifacts.CommandRun;
import dev.incidentforge.domain.Artifacts.NewFile;
import dev.incidentforge.domain.Artifacts.PatchArtifact;
import dev.incidentforge.domain.Artifacts.ValidationReport;
import dev.incidentforge.evidence.Redactor;
import dev.incidentforge.patch.PatchWorkspace;
import dev.incidentforge.repo.RepositoryReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates a patch with the shortest real test that reproduces the incident:
 * <ol>
 *   <li>copy the allowlisted repository to a scratch workspace and add ONLY the reproduction test;
 *       it must fail (the bug is reproduced, not just described);</li>
 *   <li>apply the fix and run the repository's full test suite; it must pass.</li>
 * </ol>
 * Maven runs as a child process with a hard timeout and an environment stripped of credentials.
 * This is process isolation only, not a sandbox: see docs/TRUST-BOUNDARIES.md.
 */
@Component
public class ValidationRunner {

    private static final Pattern SECRET_ENV = Pattern.compile("(?i).*(TOKEN|SECRET|PASSWORD|API_KEY|CREDENTIAL).*");

    private final IncidentForgeProperties properties;

    public ValidationRunner(IncidentForgeProperties properties) {
        this.properties = properties;
    }

    /**
     * @param label unique per validation of a run (e.g. correction round + attempt) so a workspace
     *              is never reused or deleted while another validation might still reference it
     */
    public ValidationReport validate(String runId, String label, PatchArtifact artifact,
                                     Map<String, String> changedFiles, RepositoryReader repo) {
        Path workspace = properties.workDir().toAbsolutePath().normalize()
                .resolve(runId).resolve(label).resolve("repo");
        PatchWorkspace.copyRepository(repo.root(), workspace);

        Map<String, String> testsOnly = new LinkedHashMap<>();
        for (NewFile file : artifact.patch().newFiles()) {
            testsOnly.put(file.path(), changedFiles.get(file.path()));
        }
        PatchWorkspace.writeFiles(workspace, testsOnly);
        String reproClass = artifact.patch().reproductionTestClass();
        CommandRun before = maven(workspace, List.of("-Dtest=" + reproClass, "-Dsurefire.failIfNoSpecifiedTests=false",
                "test"));
        boolean compiled = !before.outputTail().contains("COMPILATION ERROR");
        boolean reproduced = !before.timedOut() && before.exitCode() != 0 && compiled
                && before.outputTail().contains(reproClass);

        PatchWorkspace.writeFiles(workspace, changedFiles);
        CommandRun after = maven(workspace, List.of("test"));
        boolean passed = !after.timedOut() && after.exitCode() == 0;

        String verdict;
        if (passed && reproduced) {
            verdict = "REPRODUCED_THEN_FIXED";
        } else if (passed) {
            verdict = "NOT_REPRODUCED";
        } else {
            verdict = after.timedOut() ? "TIMED_OUT" : "TESTS_FAILED";
        }
        return new ValidationReport(reproduced, before, after, passed && reproduced, verdict, artifact.attempt());
    }

    CommandRun maven(Path workspace, List<String> goals) {
        IncidentForgeProperties.Validation config = properties.validation();
        List<String> command = new ArrayList<>();
        command.add(mavenExecutable(config.mavenExecutable()));
        command.add("-B");
        command.add("--no-transfer-progress");
        command.add("-Dstyle.color=never");
        if (config.offline()) {
            command.add("-o");
        }
        command.addAll(goals);
        Path log = workspace.getParent().resolve("maven-" + goals.size() + "-" + System.nanoTime() + ".log");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().keySet().removeIf(name -> SECRET_ENV.matcher(name).matches()
                || name.startsWith("GITHUB_") || name.startsWith("ANTHROPIC_") || name.startsWith("INCIDENTFORGE_"));
        long started = System.nanoTime();
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            int exit = finished ? process.exitValue() : -1;
            return new CommandRun(List.copyOf(command), exit, !finished, durationMs,
                    tail(Files.readString(log, StandardCharsets.UTF_8), config.maxOutputChars()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run Maven for validation (is it on PATH?)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Validation interrupted", e);
        }
    }

    static String mavenExecutable(String configured) {
        if (configured != null && !configured.isBlank() && !"auto".equals(configured)) {
            return configured;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return windows ? "mvn.cmd" : "mvn";
    }

    private static String tail(String output, int maxChars) {
        String relevant = output.lines()
                .filter(l -> !l.startsWith("[INFO] Download") && !l.isBlank())
                .reduce(new StringBuilder(), (sb, l) -> sb.append(l).append('\n'), StringBuilder::append)
                .toString();
        String tail = relevant.length() <= maxChars ? relevant : relevant.substring(relevant.length() - maxChars);
        return Redactor.redact(tail).text();
    }
}
