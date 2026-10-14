package dev.incidentforge.pipeline.stages;

import dev.incidentforge.domain.Artifacts.Classification;
import dev.incidentforge.domain.Artifacts.Mitigation;
import dev.incidentforge.domain.EvidenceItem;
import dev.incidentforge.domain.EvidenceItem.Kind;
import dev.incidentforge.domain.RunStatus;
import dev.incidentforge.domain.Stage;
import dev.incidentforge.pipeline.ArtifactJson;
import dev.incidentforge.pipeline.StageHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Mitigation is decided separately from the permanent fix: stop the bleeding first (roll back,
 * disable a flag, skip a record), fix properly later. This stage is deterministic: it correlates
 * the onset with preceding deploys and flag changes and ranks them, preferring a change whose
 * files appear in the failing stack. The result is a recommendation for the on-call engineer —
 * IncidentForge has no production write access and never executes it.
 */
@Component
public class PlanMitigationStage implements StageHandler {

    private static final Pattern VERSION = Pattern.compile("^deploy (\\S+) commit (\\S+)");
    private static final Pattern FILE = Pattern.compile("([A-Z][A-Za-z0-9]+\\.java)");
    private static final Pattern POISON = Pattern.compile("partition=(\\d+) offset=(\\d+)");

    private final ArtifactJson json;

    public PlanMitigationStage(ArtifactJson json) {
        this.json = json;
    }

    @Override
    public Stage stage() {
        return Stage.PLAN_MITIGATION;
    }

    @Override
    public Outcome execute(Context context) {
        List<EvidenceItem> evidence = context.evidence();
        Instant onset = evidence.stream().filter(e -> e.kind() == Kind.METRIC).map(EvidenceItem::observedAt)
                .min(Comparator.naturalOrder())
                .orElse(evidence.stream().map(EvidenceItem::observedAt).max(Comparator.naturalOrder()).orElse(Instant.now()));
        String stack = evidence.stream().filter(e -> e.kind() == Kind.STACK_TRACE).map(EvidenceItem::summary)
                .findFirst().orElse("");

        List<EvidenceItem> candidates = evidence.stream()
                .filter(e -> e.kind() == Kind.DEPLOY || e.kind() == Kind.FEATURE_FLAG)
                .filter(e -> !e.observedAt().isAfter(onset) && e.observedAt().isAfter(onset.minus(Duration.ofHours(2))))
                .sorted(Comparator.comparing((EvidenceItem e) -> touchesStack(e, stack)).reversed()
                        .thenComparing(EvidenceItem::observedAt, Comparator.reverseOrder()))
                .toList();
        List<String> alternatives = new ArrayList<>();
        Optional<String> poison = evidence.stream().filter(e -> e.kind() == Kind.LOG_SIGNATURE)
                .map(e -> POISON.matcher(e.summary())).filter(Matcher::find)
                .map(m -> "partition " + m.group(1) + " offset " + m.group(2)).findFirst();
        poison.ifPresent(p -> alternatives.add("SKIP_POISON_OFFSET: advance the consumer group past " + p
                + " (stops the hot loop, but the event is lost unless it is captured for replay first)"));

        Mitigation mitigation;
        if (!candidates.isEmpty()) {
            EvidenceItem best = candidates.getFirst();
            for (EvidenceItem other : candidates.subList(1, candidates.size())) {
                alternatives.add(describe(other) + " (" + minutesBefore(other, onset) + " min before onset"
                        + (touchesStack(other, stack) ? ", touches failing code" : ", does not touch failing code")
                        + ")");
            }
            if (best.kind() == Kind.DEPLOY) {
                Matcher m = VERSION.matcher(best.summary());
                String version = m.find() ? m.group(1) : "the latest deploy";
                mitigation = new Mitigation("ROLLBACK_DEPLOY", version,
                        "Deploy " + version + " landed " + minutesBefore(best, onset) + " min before the onset"
                                + (touchesStack(best, stack) ? " and changed files that appear in the failing stack." : "."),
                        "Rolls back every change in " + version + "; the poison record is then handled by the previous "
                                + "release's error path.", List.of(best.evidenceId()), alternatives, false);
            } else {
                mitigation = new Mitigation("DISABLE_FEATURE_FLAG", best.summary().split(" ")[1],
                        "Flag changed " + minutesBefore(best, onset) + " min before the onset.",
                        "Disables the feature for all users until re-enabled.", List.of(best.evidenceId()),
                        alternatives, false);
            }
        } else if (poison.isPresent()) {
            mitigation = new Mitigation("SKIP_POISON_OFFSET", poison.get(), "No deploy or flag change precedes the onset.",
                    "The skipped event is lost unless captured for replay.", List.of(), List.of(), false);
        } else {
            mitigation = new Mitigation("NONE", null, "No change correlates with the onset.", "n/a", List.of(),
                    alternatives, false);
        }

        Builder outcome = Outcome.from(context.run());
        outcome.run().mitigationJson(json.write(mitigation));
        outcome.note("INFO", "mitigation recommendation (not executed): " + mitigation.action()
                + (mitigation.target() == null ? "" : " " + mitigation.target()));
        Classification classification = json.read(context.run().classificationJson(), Classification.class);
        if (classification == null || !classification.codeFixRecommended()
                || !"APPLICATION_CODE".equals(classification.category())) {
            return outcome.stop(RunStatus.NEEDS_HUMAN, "RCA and mitigation ready; classified as "
                    + (classification == null ? "UNKNOWN" : classification.category())
                    + " so no code change is proposed");
        }
        return outcome.advance(Stage.PROPOSE_FIX);
    }

    private static boolean touchesStack(EvidenceItem change, String stack) {
        Matcher m = FILE.matcher(change.summary());
        while (m.find()) {
            if (stack.contains(m.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static long minutesBefore(EvidenceItem item, Instant onset) {
        return Duration.between(item.observedAt(), onset).toMinutes();
    }

    private static String describe(EvidenceItem item) {
        return (item.kind() == Kind.DEPLOY ? "ROLLBACK_DEPLOY " : "DISABLE_FEATURE_FLAG ")
                + item.summary().split("\n")[0].split(":")[0] + " [" + item.evidenceId() + "]";
    }
}
