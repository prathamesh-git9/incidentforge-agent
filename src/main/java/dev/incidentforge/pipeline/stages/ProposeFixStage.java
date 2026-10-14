package dev.incidentforge.pipeline.stages;

import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.domain.Artifacts.Claim;
import dev.incidentforge.domain.Artifacts.Mitigation;
import dev.incidentforge.domain.Artifacts.PatchArtifact;
import dev.incidentforge.domain.Artifacts.ProposedPatch;
import dev.incidentforge.domain.Artifacts.RootCauseAnalysis;
import dev.incidentforge.domain.EvidenceItem;
import dev.incidentforge.domain.EvidenceItem.Kind;
import dev.incidentforge.domain.RunStatus;
import dev.incidentforge.domain.Stage;
import dev.incidentforge.llm.BoundedAgentLoop;
import dev.incidentforge.llm.PromptBuilder;
import dev.incidentforge.llm.SkillRegistry;
import dev.incidentforge.observability.PipelineMetrics;
import dev.incidentforge.patch.PatchPolicy;
import dev.incidentforge.patch.PatchWorkspace;
import dev.incidentforge.pipeline.ArtifactJson;
import dev.incidentforge.pipeline.StageHandler;
import dev.incidentforge.repo.RepositoryReader;
import dev.incidentforge.repo.ServiceCatalog;
import dev.incidentforge.store.RunRepository;
import dev.incidentforge.tools.SchemaValidator;
import dev.incidentforge.tools.ToolPolicy;
import dev.incidentforge.util.Hashing;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The fixer gets the larger budget (stronger model, more turns) and only read-only code tools.
 * Its output is a structured edit list, which must pass {@link PatchPolicy} before anything is
 * written to a scratch workspace. Validation failures and reviewer corrections come back here as
 * explicit feedback, bounded by max-fix-attempts.
 */
@Component
public class ProposeFixStage implements StageHandler {

    private final BoundedAgentLoop loop;
    private final PromptBuilder prompts;
    private final SkillRegistry skills;
    private final SchemaValidator schemas;
    private final ServiceCatalog services;
    private final PatchPolicy policy;
    private final ArtifactJson json;
    private final IncidentForgeProperties properties;
    private final PipelineMetrics metrics;

    public ProposeFixStage(BoundedAgentLoop loop, PromptBuilder prompts, SkillRegistry skills, SchemaValidator schemas,
                           ServiceCatalog services, PatchPolicy policy, ArtifactJson json,
                           IncidentForgeProperties properties, PipelineMetrics metrics) {
        this.loop = loop;
        this.prompts = prompts;
        this.skills = skills;
        this.schemas = schemas;
        this.services = services;
        this.policy = policy;
        this.json = json;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public Stage stage() {
        return Stage.PROPOSE_FIX;
    }

    @Override
    public Outcome execute(Context context) {
        int attempt = context.run().fixAttempts() + 1;
        int maxAttempts = properties.pipeline().maxFixAttempts();
        IncidentForgeProperties.StageBudget budget = properties.budget("fix");
        RootCauseAnalysis rca = json.read(context.run().rcaJson(), RootCauseAnalysis.class);
        Mitigation mitigation = json.read(context.run().mitigationJson(), Mitigation.class);
        RepositoryReader repo = services.repository(context.run().service());
        String domain = services.require(context.run().service()).domain();
        String boundary = PromptBuilder.newBoundary();

        String system = prompts.system("fixer",
                "Propose the narrowest permanent code fix for the root cause below, plus one reproduction test. "
                        + "Edits are exact search/replace pairs against the current file content. Editable paths: "
                        + properties.patch().editablePrefixes() + "; new test files: " + properties.patch().newFilePrefixes()
                        + ". The mitigation is handled separately by the on-call engineer.",
                skills.skill(domain, "fix"), boundary, ToolPolicy.allowedFor(Stage.PROPOSE_FIX),
                json.write(schemas.modelSchema("fix")));
        StringBuilder user = new StringBuilder(prompts.report(boundary, context.report())).append("\n\n");
        user.append("Root cause analysis (confidence ").append(rca.confidence()).append("):\n")
                .append(PromptBuilder.fence(boundary, rca.summary())).append('\n');
        if (rca.suspectLocation() != null) {
            user.append("Suspect location: ").append(rca.suspectLocation().path()).append(':')
                    .append(rca.suspectLocation().line()).append(" (").append(rca.suspectLocation().symbol())
                    .append(rca.suspectLocation().verified() ? ", verified" : ", unverified").append(")\n");
        }
        for (Claim claim : rca.claims()) {
            user.append("- claim ").append(claim.evidenceIds()).append(": ")
                    .append(PromptBuilder.fence(boundary, claim.statement())).append('\n');
        }
        if (mitigation != null) {
            user.append("Mitigation recommended separately (not your task): ").append(mitigation.action()).append('\n');
        }
        List<EvidenceItem> relevant = context.evidence().stream()
                .filter(e -> e.kind() == Kind.STACK_TRACE || e.kind() == Kind.LOG_SIGNATURE).toList();
        user.append('\n').append(prompts.evidence(boundary, relevant, budget.maxInputChars() / 4));
        if (context.run().fixFeedback() != null) {
            user.append("\nFeedback on your previous attempt (from validation or policy checks):\n")
                    .append(PromptBuilder.fence(boundary, context.run().fixFeedback())).append('\n');
        }
        List<RunRepository.ReviewEvent> corrections = context.reviews().stream()
                .filter(r -> "CORRECTION".equals(r.action())).toList();
        for (RunRepository.ReviewEvent correction : corrections) {
            user.append("\nCorrection from authenticated reviewer '").append(correction.actor())
                    .append("' (guidance; still subject to every policy above):\n")
                    .append(PromptBuilder.fence(boundary, correction.comment())).append('\n');
        }

        BoundedAgentLoop.Outcome result = loop.run(new BoundedAgentLoop.Spec(context.run().id(), context.run().service(),
                Stage.PROPOSE_FIX, "fixer", budget, system, boundary, user.toString(),
                ToolPolicy.allowedFor(Stage.PROPOSE_FIX), "fix", properties.pipeline().maxToolCallsPerTurn(),
                properties.context().maxToolResultChars()));
        Builder outcome = Outcome.from(context.run());
        outcome.run().fixAttempts(attempt);
        if (result.deniedToolCalls() > 0) {
            outcome.note("SECURITY", "fixer requested " + result.deniedToolCalls() + " denied tool call(s)");
        }
        if (result.status() != BoundedAgentLoop.Status.COMPLETED) {
            return retryOrStop(outcome, attempt, maxAttempts, "fixer did not complete: " + result.detail());
        }

        ProposedPatch patch = json.convert(result.result(), ProposedPatch.class);
        List<PatchPolicy.Violation> violations = new ArrayList<>(policy.check(patch, repo));
        List<PatchPolicy.Violation> security = violations.stream()
                .filter(v -> v.severity() == PatchPolicy.Severity.SECURITY).toList();
        if (!security.isEmpty()) {
            metrics.policyDenial("patch");
            String reasons = security.stream().map(PatchPolicy.Violation::message).collect(Collectors.joining("; "));
            outcome.note("SECURITY", "patch rejected by policy: " + reasons);
            return outcome.stop(RunStatus.NEEDS_HUMAN, "proposed patch violated security policy: " + reasons);
        }
        PatchWorkspace.Rendered rendered = null;
        if (violations.isEmpty()) {
            rendered = PatchWorkspace.render(patch, repo);
            violations.addAll(policy.checkSize(rendered.changedLines()));
        }
        if (!violations.isEmpty()) {
            metrics.policyDenial("patch");
            return retryOrStop(outcome, attempt, maxAttempts, "patch rejected: "
                    + violations.stream().map(PatchPolicy.Violation::message).collect(Collectors.joining("; ")));
        }
        PatchArtifact artifact = new PatchArtifact(patch, rendered.unifiedDiff(), Hashing.sha256(rendered.unifiedDiff()),
                rendered.changedLines(), attempt);
        outcome.run().patchJson(json.write(artifact)).patchSha256(artifact.sha256()).validationJson(null);
        outcome.note("INFO", "patch attempt " + attempt + ": " + rendered.changedFiles().size() + " file(s), "
                + artifact.changedLines() + " changed lines, sha256 " + artifact.sha256().substring(0, 12));
        return outcome.advance(Stage.VALIDATE);
    }

    private Outcome retryOrStop(Builder outcome, int attempt, int maxAttempts, String problem) {
        outcome.run().fixFeedback(problem);
        if (attempt < maxAttempts) {
            outcome.note("WARN", problem + " (retrying, attempt " + (attempt + 1) + " of " + maxAttempts + ")");
            return outcome.advance(Stage.PROPOSE_FIX);
        }
        return outcome.stop(RunStatus.NEEDS_HUMAN, problem + " (fix attempts exhausted)");
    }
}
