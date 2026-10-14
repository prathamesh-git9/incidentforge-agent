package dev.incidentforge.validation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.incidentforge.domain.Artifacts.FileEdit;
import dev.incidentforge.domain.Artifacts.NewFile;
import dev.incidentforge.domain.Artifacts.PatchArtifact;
import dev.incidentforge.domain.Artifacts.ProposedPatch;
import dev.incidentforge.domain.Artifacts.ValidationReport;
import dev.incidentforge.patch.PatchWorkspace;
import dev.incidentforge.repo.RepositoryReader;
import dev.incidentforge.support.TestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Runs the sample repository's real Maven build in scratch workspaces. */
class ValidationRunnerTest {

    private static final String PROCESSOR = "src/main/java/com/example/orders/OrderEventProcessor.java";
    private static final String CATCH_BODY =
            "                source.seek(record.offset());\n                return applied;";
    private static final String REPRO = """
            package com.example.orders;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import java.util.List;
            import org.junit.jupiter.api.Test;

            class PoisonReproTest {
                @Test
                void partitionKeepsMoving() {
                    InMemoryRecordSource source = new InMemoryRecordSource(List.of(
                            "orderId=A-1;customerId=C-1;amount=12,50;currency=EUR",
                            "orderId=A-2;customerId=C-1;amount=1.00;currency=EUR"));
                    OrderEventProcessor p = new OrderEventProcessor(source, new OrderEventParser(), new OrderLedger(),
                            (record, reason) -> { });
                    for (int i = 0; i < 3; i++) {
                        p.pollOnce();
                    }
                    assertEquals(2, source.committedOffset());
                }
            }
            """;

    private final ValidationRunner runner = new ValidationRunner(TestSupport.properties());
    private final RepositoryReader repo = new RepositoryReader(TestSupport.SAMPLE_REPO);

    @Test
    void reproducesBeforeTheFixAndPassesAfterIt() {
        ValidationReport report = validate(CATCH_BODY,
                "                source.commit(record.offset() + 1);", REPRO);

        assertThat(report.reproducedBeforeFix()).isTrue();
        assertThat(report.beforeFix().exitCode()).isNotZero();
        assertThat(report.afterFix().exitCode()).isZero();
        assertThat(report.verdict()).isEqualTo("REPRODUCED_THEN_FIXED");
        assertThat(report.passed()).isTrue();
    }

    @Test
    void aPatchThatDoesNotCompileFailsValidation() {
        ValidationReport report = validate(CATCH_BODY,
                "                source.commitOffset(record.offset() + 1);", REPRO);

        assertThat(report.passed()).isFalse();
        assertThat(report.verdict()).isEqualTo("TESTS_FAILED");
        assertThat(report.afterFix().outputTail()).contains("COMPILATION ERROR");
    }

    @Test
    void aTestThatPassesOnTheBuggyCodeDoesNotCountAsAReproduction() {
        String trivial = REPRO.replace("assertEquals(2, source.committedOffset());", "assertEquals(1, 1);");
        ValidationReport report = validate(CATCH_BODY,
                "                source.commit(record.offset() + 1);", trivial);

        assertThat(report.reproducedBeforeFix()).isFalse();
        assertThat(report.verdict()).isEqualTo("NOT_REPRODUCED");
        assertThat(report.passed()).isFalse();
    }

    @Test
    void childOutputInTheConsoleCodePageIsReadNotMisreportedAsMissingMaven() throws Exception {
        // Surefire prints "Test.method:16 » NullPointer ..." for a test *error*; a Windows child JVM
        // may emit '»' as the single cp1252 byte 0xBB, which is invalid UTF-8.
        java.nio.file.Path log = java.nio.file.Files.createTempFile("maven-output", ".log");
        byte[] prefix = "[ERROR]   TaxEngineFallbackReproTest.region:16 ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] suffix = " NullPointer Cannot invoke".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] bytes = new byte[prefix.length + 1 + suffix.length];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        bytes[prefix.length] = (byte) 0xBB;
        System.arraycopy(suffix, 0, bytes, prefix.length + 1, suffix.length);
        java.nio.file.Files.write(log, bytes);

        assertThat(ValidationRunner.readOutput(log)).contains("TaxEngineFallbackReproTest", "NullPointer");
        java.nio.file.Files.delete(log);
    }

    private ValidationReport validate(String search, String replace, String test) {
        ProposedPatch patch = new ProposedPatch("test", List.of(new FileEdit(PROCESSOR, search, replace)),
                List.of(new NewFile("src/test/java/com/example/orders/PoisonReproTest.java", test)),
                "PoisonReproTest", "none");
        PatchWorkspace.Rendered rendered = PatchWorkspace.render(patch, repo);
        PatchArtifact artifact = new PatchArtifact(patch, rendered.unifiedDiff(), "sha", rendered.changedLines(), 1);
        return runner.validate("test-" + UUID.randomUUID(), "a1", artifact, rendered.changedFiles(), repo);
    }
}
