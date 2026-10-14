package dev.incidentforge.pipeline.stages;

import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.domain.Artifacts.Classification;
import dev.incidentforge.domain.EvidenceItem;
import dev.incidentforge.domain.RunStatus;
import dev.incidentforge.domain.Stage;
import dev.incidentforge.llm.BoundedAgentLoop;
import dev.incidentforge.llm.PromptBuilder;
import dev.incidentforge.llm.SkillRegistry;
import dev.incidentforge.pipeline.ArtifactJson;
import dev.incidentforge.pipeline.StageHandler;
import dev.incidentforge.repo.ServiceCatalog;
import dev.incidentforge.tools.SchemaValidator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Classification before any code change: is this the service's own code, a dependency,
 * infrastructure, configuration or the network — and is a PR even the right outcome?
 * Small budget (cheaper model, two turns, no tools).
 */
@Component
public class ClassifyStage implements StageHandler {

    private final BoundedAgentLoop loop;
    private final PromptBuilder prompts;
    private final SkillRegistry skills;
    private final SchemaValidator schemas;
    private final ServiceCatalog services;
    private final ArtifactJson json;
    private final IncidentForgeProperties properties;

    public ClassifyStage(BoundedAgentLoop loop, PromptBuilder prompts, SkillRegistry skills, SchemaValidator schemas,
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
        return Stage.CLASSIFY;
    }

    @Override
    public Outcome execute(Context context) {
        IncidentForgeProperties.StageBudget budget = properties.budget("classify");
        String boundary = PromptBuilder.newBoundary();
        String domain = services.require(context.run().service()).domain();
        String system = prompts.system("classifier",
                "Classify the incident before any code change is considered: choose the category of the root cause and "
                        + "decide whether a code change in this service is warranted.",
                skills.skill(domain, "classify"), boundary, Set.of(), json.write(schemas.modelSchema("classification")));
        String user = prompts.report(boundary, context.report()) + "\n\n"
                + prompts.evidence(boundary, context.evidence(), budget.maxInputChars() / 2);

        BoundedAgentLoop.Outcome result = loop.run(new BoundedAgentLoop.Spec(context.run().id(), context.run().service(),
                Stage.CLASSIFY, "classifier", budget, system, boundary, user, Set.of(), "classification",
                properties.pipeline().maxToolCallsPerTurn(), properties.context().maxToolResultChars()));
        Builder outcome = Outcome.from(context.run());
        if (result.status() != BoundedAgentLoop.Status.COMPLETED) {
            return outcome.stop(RunStatus.NEEDS_HUMAN, "classification did not complete: " + result.detail());
        }
        Classification classification = json.convert(result.result(), Classification.class);
        Set<String> known = context.evidence().stream().map(EvidenceItem::evidenceId).collect(Collectors.toSet());
        List<String> cited = classification.evidenceIds().stream().filter(known::contains).toList();
        if (cited.isEmpty()) {
            return outcome.stop(RunStatus.NEEDS_HUMAN, "classification cited no existing evidence; refusing to act on it");
        }
        classification = new Classification(classification.category(), classification.codeFixRecommended(),
                classification.confidence(), classification.rationale(), cited);
        outcome.run().classificationJson(json.write(classification));
        outcome.note("INFO", "classified as " + classification.category() + " (confidence "
                + classification.confidence() + ", code fix " + (classification.codeFixRecommended() ? "recommended"
                : "not recommended") + ") citing " + String.join(",", cited));
        return outcome.advance(Stage.INVESTIGATE);
    }
}
