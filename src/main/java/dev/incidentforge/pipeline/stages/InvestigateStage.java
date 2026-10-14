package dev.incidentforge.pipeline.stages;

import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.domain.Artifacts.Claim;
import dev.incidentforge.domain.Artifacts.Classification;
import dev.incidentforge.domain.Artifacts.Finding;
import dev.incidentforge.domain.Artifacts.RootCauseAnalysis;
import dev.incidentforge.domain.Artifacts.SuspectLocation;
import dev.incidentforge.domain.EvidenceItem;
import dev.incidentforge.domain.EvidenceItem.Kind;
import dev.incidentforge.domain.RunStatus;
import dev.incidentforge.domain.Stage;
import dev.incidentforge.llm.BoundedAgentLoop;
import dev.incidentforge.llm.ModelClient;
import dev.incidentforge.llm.PromptBuilder;
import dev.incidentforge.llm.SkillRegistry;
import dev.incidentforge.pipeline.ArtifactJson;
import dev.incidentforge.pipeline.StageHandler;
import dev.incidentforge.pipeline.TransientStageException;
import dev.incidentforge.repo.RepositoryReader;
import dev.incidentforge.repo.ServiceCatalog;
import dev.incidentforge.tools.SchemaValidator;
import dev.incidentforge.tools.ToolGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Parallel, focused investigations. Each investigator gets a deliberately pruned slice of the
 * evidence and a narrow tool scope (input control), runs under its own turn budget, and returns
 * a schema-validated finding. Consolidation is deterministic code, not another model call:
 * claims citing unknown evidence are rejected and the suspect location is verified against the
 * repository before it is trusted.
 */
@Component
public class InvestigateStage implements StageHandler {

    private record Investigator(String actor, String task, Predicate<EvidenceItem> evidence, Set<String> tools) {
    }

    private static final List<Investigator> INVESTIGATORS = List.of(
            new Investigator("timeline-investigator",
                    "Reconstruct the timeline: what changed first, what followed, and which signals are noise.",
                    e -> Set.of(Kind.METRIC, Kind.LOG_SIGNATURE, Kind.TRACE, Kind.CONTEXT_STATS).contains(e.kind()),
                    Set.of()),
            new Investigator("change-investigator",
                    "Correlate the onset with deploys and feature-flag changes; prefer changes whose files appear in the "
                            + "failing stack. Say which candidate changes you ruled out and why.",
                    e -> Set.of(Kind.DEPLOY, Kind.FEATURE_FLAG, Kind.METRIC, Kind.STACK_TRACE).contains(e.kind()),
                    Set.of("deploys_list")),
            new Investigator("code-path-investigator",
                    "Map the failure to the exact code path. Use code_search/code_read to find the handler responsible "
                            + "and report the suspect file, line and symbol.",
                    e -> e.kind() == Kind.STACK_TRACE || e.kind() == Kind.TRACE
                            || (e.kind() == Kind.LOG_SIGNATURE && !e.summary().startsWith("INFO")),
                    Set.of("code_search", "code_read")));

    private final BoundedAgentLoop loop;
    private final PromptBuilder prompts;
    private final SkillRegistry skills;
    private final SchemaValidator schemas;
    private final ServiceCatalog services;
    private final ArtifactJson json;
    private final IncidentForgeProperties properties;

    public InvestigateStage(BoundedAgentLoop loop, PromptBuilder prompts, SkillRegistry skills, SchemaValidator schemas,
                            ServiceCatalog services, ArtifactJson json, IncidentForgeProperties properties) {
        this.loop = loop;
        this.prompts = prompts;
        this.skills = skills;
        this.schemas = schemas;
        this.services = services;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public Stage stage() {
        return Stage.INVESTIGATE;
    }

    @Override
    public Outcome execute(Context context) {
        IncidentForgeProperties.StageBudget budget = properties.budget("investigate");
        String domain = services.require(context.run().service()).domain();
        Builder outcome = Outcome.from(context.run());
        List<Finding> findings = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int transientFailures = 0;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<Investigator, Future<BoundedAgentLoop.Outcome>> running = new java.util.LinkedHashMap<>();
            for (Investigator investigator : INVESTIGATORS) {
                running.put(investigator, executor.submit(() -> investigate(context, investigator, budget, domain)));
            }
            long deadline = System.nanoTime() + properties.pipeline().investigatorTimeout().toNanos();
            for (Map.Entry<Investigator, Future<BoundedAgentLoop.Outcome>> entry : running.entrySet()) {
                String actor = entry.getKey().actor();
                try {
                    BoundedAgentLoop.Outcome result = entry.getValue()
                            .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (result.deniedToolCalls() > 0) {
                        outcome.note("SECURITY", actor + " requested " + result.deniedToolCalls()
                                + " tool call(s) outside its scope; denied and audited");
                    }
                    if (result.status() == BoundedAgentLoop.Status.COMPLETED) {
                        Finding f = json.convert(result.result(), Finding.class);
                        findings.add(new Finding(actor, f.hypothesis(), f.suspectPath(), f.suspectLine(),
                                f.suspectSymbol(), f.claims(), f.confidence()));
                        outcome.note("INFO", actor + " finished in " + result.turns() + " turn(s) with "
                                + result.toolCalls().size() + " tool call(s)");
                    } else {
                        failures.add(actor + ": " + result.detail());
                    }
                } catch (TimeoutException e) {
                    entry.getValue().cancel(true);
                    failures.add(actor + ": timed out");
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof ModelClient.TransientModelException
                            || e.getCause() instanceof ToolGateway.ToolTransportException) {
                        transientFailures++;
                    }
                    failures.add(actor + ": " + e.getCause().getClass().getSimpleName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TransientStageException("investigation interrupted", e);
                }
            }
        }
        if (findings.isEmpty()) {
            if (transientFailures > 0) {
                throw new TransientStageException("all investigators failed; " + transientFailures + " transiently", null);
            }
            return outcome.stop(RunStatus.NEEDS_HUMAN, "no investigator produced a finding: " + failures);
        }
        RootCauseAnalysis rca = consolidate(context, findings, failures);
        outcome.run().rcaJson(json.write(rca));
        outcome.note("INFO", "RCA confidence " + rca.confidence() + "; suspect "
                + (rca.suspectLocation() == null ? "not located" : rca.suspectLocation().path() + ":"
                + rca.suspectLocation().line() + (rca.suspectLocation().verified() ? " (verified)" : " (unverified)"))
                + "; " + rca.claims().size() + " cited claims, " + rca.rejectedClaims().size() + " rejected");
        return outcome.advance(Stage.PLAN_MITIGATION);
    }

    private BoundedAgentLoop.Outcome investigate(Context context, Investigator investigator,
                                                 IncidentForgeProperties.StageBudget budget, String domain) {
        String boundary = PromptBuilder.newBoundary();
        String system = prompts.system(investigator.actor(), investigator.task(), skills.skill(domain, "investigate"),
                boundary, investigator.tools(), json.write(schemas.modelSchema("finding")));
        List<EvidenceItem> slice = context.evidence().stream().filter(investigator.evidence()).toList();
        String user = prompts.report(boundary, context.report()) + "\n\n"
                + prompts.evidence(boundary, slice, budget.maxInputChars() / 2);
        return loop.run(new BoundedAgentLoop.Spec(context.run().id(), context.run().service(), Stage.INVESTIGATE,
                investigator.actor(), budget, system, boundary, user, investigator.tools(), "finding",
                properties.pipeline().maxToolCallsPerTurn(), properties.context().maxToolResultChars()));
    }

    private RootCauseAnalysis consolidate(Context context, List<Finding> findings, List<String> failures) {
        Set<String> known = context.evidence().stream().map(EvidenceItem::evidenceId).collect(Collectors.toSet());
        List<Claim> accepted = new ArrayList<>();
        List<Claim> rejected = new ArrayList<>();
        for (Finding finding : findings) {
            for (Claim claim : finding.claims()) {
                boolean cited = !claim.evidenceIds().isEmpty() && known.containsAll(claim.evidenceIds());
                (cited ? accepted : rejected).add(claim);
            }
        }
        Finding code = findings.stream().filter(f -> f.investigator().equals("code-path-investigator")).findFirst()
                .orElse(null);
        Finding change = findings.stream().filter(f -> f.investigator().equals("change-investigator")).findFirst()
                .orElse(null);
        Finding timeline = findings.stream().filter(f -> f.investigator().equals("timeline-investigator")).findFirst()
                .orElse(null);

        SuspectLocation location = null;
        if (code != null && code.suspectPath() != null && code.suspectLine() != null) {
            RepositoryReader repo = services.repository(context.run().service());
            boolean verified = false;
            try {
                verified = repo.exists(code.suspectPath())
                        && code.suspectLine() <= repo.read(code.suspectPath(), 1, 1).totalLines();
            } catch (IllegalArgumentException e) {
                verified = false;
            }
            location = new SuspectLocation(code.suspectPath(), code.suspectLine(), code.suspectSymbol(), verified);
        }
        double confidence = findings.stream().mapToDouble(Finding::confidence).average().orElse(0);
        if (location == null || !location.verified()) {
            confidence *= 0.6;
        }
        if (!failures.isEmpty()) {
            confidence *= 0.85;
        }
        confidence = Math.round(Math.min(0.95, confidence) * 100) / 100.0;
        Classification classification = json.read(context.run().classificationJson(), Classification.class);
        String summary = code != null && location != null ? code.hypothesis()
                : timeline != null ? timeline.hypothesis() : findings.getFirst().hypothesis();
        return new RootCauseAnalysis(summary, classification == null ? "UNKNOWN" : classification.category(),
                confidence, location, change == null ? null : change.hypothesis(), accepted, rejected, findings,
                failures);
    }
}
