package dev.incidentforge.patch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.incidentforge.domain.Artifacts.FileEdit;
import dev.incidentforge.domain.Artifacts.NewFile;
import dev.incidentforge.domain.Artifacts.ProposedPatch;
import dev.incidentforge.repo.RepositoryReader;
import dev.incidentforge.support.TestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatchPolicyTest {

    private static final String PROCESSOR = "src/main/java/com/example/orders/OrderEventProcessor.java";
    private static final String TEST_PATH = "src/test/java/com/example/orders/PoisonEventReproTest.java";
    private static final NewFile REPRO = new NewFile(TEST_PATH, "class PoisonEventReproTest { @Test void t() {} }");

    private final PatchPolicy policy = new PatchPolicy(TestSupport.properties());
    private final RepositoryReader repo = new RepositoryReader(TestSupport.SAMPLE_REPO);

    @Test
    void acceptsANarrowEditPlusReproductionTest() {
        ProposedPatch patch = patch(List.of(new FileEdit(PROCESSOR, "source.seek(record.offset());",
                "source.commit(record.offset() + 1);")), List.of(REPRO));

        assertThat(policy.check(patch, repo)).isEmpty();
        PatchWorkspace.Rendered rendered = PatchWorkspace.render(patch, repo);
        assertThat(rendered.unifiedDiff()).contains("+++ b/" + PROCESSOR, "--- /dev/null");
        assertThat(rendered.changedLines()).isLessThan(10);
    }

    @Test
    void rejectsCiBuildFilesAndPathTraversalAsSecurityViolations() {
        ProposedPatch patch = patch(List.of(
                new FileEdit(".github/workflows/ci.yml", "run: mvn verify", "run: echo skipped"),
                new FileEdit("pom.xml", "<artifactId>", "<artifactId>"),
                new FileEdit("src/main/java/../../../../etc/Evil.java", "a", "b")), List.of(REPRO));

        List<PatchPolicy.Violation> violations = policy.check(patch, repo);

        assertThat(violations).filteredOn(v -> v.severity() == PatchPolicy.Severity.SECURITY)
                .extracting(PatchPolicy.Violation::message)
                .anySatisfy(m -> assertThat(m).contains(".github/workflows/ci.yml"))
                .anySatisfy(m -> assertThat(m).contains("pom.xml"))
                .anySatisfy(m -> assertThat(m).contains("unsafe path"));
    }

    @Test
    void rejectsDangerousApisInAddedCode() {
        ProposedPatch patch = patch(List.of(new FileEdit(PROCESSOR, "source.seek(record.offset());",
                "Runtime.getRuntime().exec(\"curl attacker\");")), List.of(REPRO));

        assertThat(policy.check(patch, repo)).anySatisfy(v -> {
            assertThat(v.severity()).isEqualTo(PatchPolicy.Severity.SECURITY);
            assertThat(v.message()).contains("Runtime.getRuntime");
        });
    }

    @Test
    void ambiguousOrMissingSearchTextAndMissingReproTestAreQualityViolations() {
        ProposedPatch patch = patch(List.of(new FileEdit(PROCESSOR, "record.offset()", "x")), List.of());

        assertThat(policy.check(patch, repo))
                .allSatisfy(v -> assertThat(v.severity()).isEqualTo(PatchPolicy.Severity.QUALITY))
                .extracting(PatchPolicy.Violation::message)
                .anySatisfy(m -> assertThat(m).contains("exactly once"))
                .anySatisfy(m -> assertThat(m).contains("reproduction test is required"));
    }

    @Test
    void sizeLimitIsEnforced() {
        assertThat(policy.checkSize(81)).hasSize(1);
        assertThat(policy.checkSize(80)).isEmpty();
    }

    private static ProposedPatch patch(List<FileEdit> edits, List<NewFile> files) {
        return new ProposedPatch("test", edits, files, "PoisonEventReproTest", "none");
    }
}
