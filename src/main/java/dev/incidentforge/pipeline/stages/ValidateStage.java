package dev.incidentforge.pipeline.stages;

import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.domain.Artifacts.PatchArtifact;
import dev.incidentforge.domain.Artifacts.ValidationReport;
import dev.incidentforge.domain.RunStatus;
import dev.incidentforge.domain.Stage;
import dev.incidentforge.patch.PatchWorkspace;
import dev.incidentforge.pipeline.ArtifactJson;
import dev.incidentforge.pipeline.StageHandler;
import dev.incidentforge.repo.RepositoryReader;
import dev.incidentforge.repo.ServiceCatalog;
import dev.incidentforge.util.Hashing;
import dev.incidentforge.validation.ValidationRunner;
import org.springframework.stereotype.Component;

/** Runs the reproduce-then-fix validation and routes the run to a reviewer, back to the fixer, or to a human. */
@Component
public class ValidateStage implements StageHandler {

    private final ValidationRunner validator;
    private final ServiceCatalog services;
    private final ArtifactJson json;
    private final IncidentForgeProperties properties;

    public ValidateStage(ValidationRunner validator, ServiceCatalog services, ArtifactJson json,
                         IncidentForgeProperties properties) {
        this.validator = validator;
        this.services = services;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public Stage stage() {
        return Stage.VALIDATE;
    }

    @Override
    public Outcome execute(Context context) {
        Builder outcome = Outcome.from(context.run());
        PatchArtifact artifact = json.read(context.run().patchJson(), PatchArtifact.class);
        RepositoryReader repo = services.repository(context.run().service());
        PatchWorkspace.Rendered rendered = PatchWorkspace.render(artifact.patch(), repo);
        if (!Hashing.sha256(rendered.unifiedDiff()).equals(artifact.sha256())) {
            return outcome.stop(RunStatus.NEEDS_HUMAN, "repository changed since the patch was proposed; re-run");
        }
        String label = "c" + context.run().correctionRounds() + "-a" + artifact.attempt();
        ValidationReport report = validator.validate(context.run().id(), label, artifact, rendered.changedFiles(), repo);
        outcome.run().validationJson(json.write(report));
        String timing = " (before: exit " + report.beforeFix().exitCode() + " in " + report.beforeFix().durationMs()
                + " ms; after: exit " + report.afterFix().exitCode() + " in " + report.afterFix().durationMs() + " ms)";
        if (report.passed()) {
            outcome.run().fixFeedback(null);
            outcome.note("INFO", "validation " + report.verdict() + ": reproduction test "
                    + artifact.patch().reproductionTestClass() + " failed on the current code and the full suite "
                    + "passed with the patch" + timing);
            return outcome.stop(RunStatus.AWAITING_APPROVAL,
                    "validated patch ready for human review (approve with patch sha256 " + artifact.sha256() + ")");
        }
        String feedback = switch (report.verdict()) {
            case "NOT_REPRODUCED" -> "Your reproduction test passes on the unpatched code, so it does not reproduce the "
                    + "incident. Write a test that fails on the current code.";
            default -> "Validation " + report.verdict() + ". Maven output tail:\n" + report.afterFix().outputTail();
        };
        outcome.run().fixFeedback(feedback);
        if (artifact.attempt() < properties.pipeline().maxFixAttempts()) {
            outcome.note("WARN", "validation " + report.verdict() + timing + "; returning to fixer with feedback");
            return outcome.advance(Stage.PROPOSE_FIX);
        }
        return outcome.stop(RunStatus.NEEDS_HUMAN, "validation " + report.verdict() + " after "
                + artifact.attempt() + " attempt(s)" + timing);
    }
}
