package dev.incidentforge.pipeline.stages;

import dev.incidentforge.domain.Artifacts.Claim;
import dev.incidentforge.domain.Artifacts.FileEdit;
import dev.incidentforge.domain.Artifacts.Mitigation;
import dev.incidentforge.domain.Artifacts.PatchArtifact;
import dev.incidentforge.domain.Artifacts.PublishResult;
import dev.incidentforge.domain.Artifacts.RootCauseAnalysis;
import dev.incidentforge.domain.Artifacts.ValidationReport;
import dev.incidentforge.domain.EvidenceItem;
import dev.incidentforge.domain.RunRecord;
import dev.incidentforge.domain.RunStatus;
import dev.incidentforge.domain.Stage;
import dev.incidentforge.observability.PipelineMetrics;
import dev.incidentforge.patch.PatchWorkspace;
import dev.incidentforge.pipeline.ArtifactJson;
import dev.incidentforge.pipeline.StageHandler;
import dev.incidentforge.publish.GitHubRestClient;
import dev.incidentforge.publish.PullRequestPublisher;
import dev.incidentforge.repo.RepositoryReader;
import dev.incidentforge.repo.ServiceCatalog;
import dev.incidentforge.util.Hashing;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Runs only after a reviewer approved a specific patch hash. Re-renders the patch and refuses to
 * publish if it no longer hashes to what was approved, then opens a draft PR (or writes a local
 * bundle). It never merges; the PR stays a draft for the owning team.
 */
@Component
public class PublishStage implements StageHandler {

    private final PullRequestPublisher publisher;
    private final ServiceCatalog services;
    private final ArtifactJson json;
    private final PipelineMetrics metrics;

    public PublishStage(PullRequestPublisher publisher, ServiceCatalog services, ArtifactJson json,
                        PipelineMetrics metrics) {
        this.publisher = publisher;
        this.services = services;
        this.json = json;
        this.metrics = metrics;
    }

    @Override
    public Stage stage() {
        return Stage.PUBLISH;
    }

    @Override
    public Outcome execute(Context context) {
        RunRecord run = context.run();
        Builder outcome = Outcome.from(run);
        if (run.approvedBy() == null || run.approvedPatchSha256() == null
                || !run.approvedPatchSha256().equals(run.patchSha256())) {
            outcome.note("SECURITY", "publish requested without a matching human approval");
            return outcome.stop(RunStatus.NEEDS_HUMAN, "no approval matches the current patch; nothing published");
        }
        PatchArtifact artifact = json.read(run.patchJson(), PatchArtifact.class);
        RepositoryReader repo = services.repository(run.service());
        PatchWorkspace.Rendered rendered = PatchWorkspace.render(artifact.patch(), repo);
        if (!Hashing.sha256(rendered.unifiedDiff()).equals(run.approvedPatchSha256())) {
            outcome.note("SECURITY", "rendered patch no longer matches the approved hash");
            return outcome.stop(RunStatus.NEEDS_HUMAN, "repository changed after approval; re-run before publishing");
        }
        Map<String, String> originals = new LinkedHashMap<>();
        for (FileEdit edit : artifact.patch().edits()) {
            originals.put(edit.path(), repo.readAll(edit.path()));
        }
        String title = abbreviate("IncidentForge: " + artifact.patch().summary(), 90);
        PullRequestPublisher.Request request = new PullRequestPublisher.Request(run.id(), title, body(context, artifact),
                "incidentforge/" + run.id(), rendered.changedFiles(), originals, rendered.unifiedDiff(),
                artifact.sha256());
        PublishResult result;
        try {
            result = publisher.publish(request);
        } catch (PullRequestPublisher.PublishConflictException e) {
            metrics.publish("conflict");
            return outcome.stop(RunStatus.NEEDS_HUMAN, "publish conflict: " + e.getMessage());
        } catch (GitHubRestClient.UncertainWriteException e) {
            result = new PublishResult("github", "UNCERTAIN", null, request.branch(), null, e.getMessage());
        }
        outcome.run().publishJson(json.write(result));
        metrics.publish(result.outcome().toLowerCase());
        if ("PUBLISHED".equals(result.outcome())) {
            outcome.note("INFO", "draft published (" + result.mode() + "): " + result.url() + " - " + result.note());
            return outcome.stop(RunStatus.PUBLISHED, "draft PR published for human review; not merged");
        }
        return outcome.stop(RunStatus.PUBLISH_UNCERTAIN, "publish outcome unknown: " + result.note()
                + " (POST /api/runs/" + run.id() + "/publish/retry reconciles safely)");
    }

    private String body(Context context, PatchArtifact artifact) {
        RunRecord run = context.run();
        RootCauseAnalysis rca = json.read(run.rcaJson(), RootCauseAnalysis.class);
        Mitigation mitigation = json.read(run.mitigationJson(), Mitigation.class);
        ValidationReport validation = json.read(run.validationJson(), ValidationReport.class);
        StringBuilder sb = new StringBuilder();
        sb.append("> Draft generated by IncidentForge for incident `").append(run.incidentId()).append("` (run `")
                .append(run.id()).append("`). Approved for publication by `").append(run.approvedBy())
                .append("`; review before merging. IncidentForge cannot merge.\n\n");
        sb.append("## Root cause (confidence ").append(rca.confidence()).append(")\n").append(rca.summary()).append("\n\n");
        if (rca.suspectLocation() != null) {
            sb.append("Suspect: `").append(rca.suspectLocation().path()).append(':').append(rca.suspectLocation().line())
                    .append("` (").append(rca.suspectLocation().symbol()).append(")\n\n");
        }
        if (rca.contributingChange() != null) {
            sb.append("Contributing change: ").append(rca.contributingChange()).append("\n\n");
        }
        sb.append("### Evidence-backed claims\n");
        for (Claim claim : rca.claims()) {
            sb.append("- ").append(claim.statement()).append(" ").append(claim.evidenceIds()).append('\n');
        }
        sb.append("\n### Evidence timeline\n| id | kind | source | observed | summary |\n|---|---|---|---|---|\n");
        for (EvidenceItem e : context.evidence()) {
            sb.append("| ").append(e.evidenceId()).append(" | ").append(e.kind()).append(" | ").append(e.sourceTool())
                    .append(" | ").append(e.observedAt()).append(" | ")
                    .append(abbreviate(e.summary().split("\n")[0], 140).replace("|", "\\|"))
                    .append(e.flags().isEmpty() ? "" : " _" + e.flags() + "_").append(" |\n");
        }
        if (mitigation != null) {
            sb.append("\n## Mitigation (recommended separately, not executed)\n").append(mitigation.action())
                    .append(mitigation.target() == null ? "" : " `" + mitigation.target() + "`").append(": ")
                    .append(mitigation.rationale()).append("\n");
        }
        sb.append("\n## Change\n").append(artifact.patch().summary()).append("\n\nRisk: ")
                .append(artifact.patch().riskNotes()).append("\n\n");
        sb.append("## Validation\n- Verdict: `").append(validation.verdict()).append("`\n- Reproduction (`")
                .append(artifact.patch().reproductionTestClass()).append("` on unpatched code): exit ")
                .append(validation.beforeFix().exitCode()).append(" (expected to fail)\n- Full suite with patch: exit ")
                .append(validation.afterFix().exitCode()).append("\n- Patch sha256: `").append(artifact.sha256())
                .append("`\n");
        return sb.toString();
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}
