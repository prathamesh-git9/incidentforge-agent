package dev.incidentforge.pipeline;

import dev.incidentforge.domain.EvidenceItem;
import dev.incidentforge.domain.IncidentReport;
import dev.incidentforge.domain.RunRecord;
import dev.incidentforge.domain.RunStatus;
import dev.incidentforge.domain.Stage;
import dev.incidentforge.store.RunRepository;
import java.util.ArrayList;
import java.util.List;

/**
 * One node of the fixed pipeline. A handler reads explicit state (the run record, report,
 * evidence timeline and reviewer feedback) and returns the next state; it never writes to the
 * database itself, so the processor can commit state, evidence and the next command atomically.
 */
public interface StageHandler {

    Stage stage();

    Outcome execute(Context context);

    record Context(RunRecord run, IncidentReport report, List<EvidenceItem> evidence,
                   List<RunRepository.ReviewEvent> reviews) {
    }

    record Note(String kind, String message) {
    }

    record Outcome(RunRecord next, List<EvidenceItem> newEvidence, List<Note> notes) {

        public static Builder from(RunRecord run) {
            return new Builder(run);
        }
    }

    final class Builder {
        private final RunRecord.Builder run;
        private final List<EvidenceItem> evidence = new ArrayList<>();
        private final List<Note> notes = new ArrayList<>();

        Builder(RunRecord current) {
            this.run = current.toBuilder();
        }

        public RunRecord.Builder run() {
            return run;
        }

        public Builder evidence(List<EvidenceItem> items) {
            evidence.addAll(items);
            return this;
        }

        public Builder note(String kind, String message) {
            notes.add(new Note(kind, message));
            return this;
        }

        public Outcome advance(Stage next) {
            run.status(RunStatus.IN_PROGRESS).stage(next);
            return build();
        }

        public Outcome stop(RunStatus status, String reason) {
            run.status(status).stage(null);
            if (reason != null) {
                run.failureReason(reason);
                notes.add(new Note(status == RunStatus.AWAITING_APPROVAL ? "READY" : "STOP", reason));
            }
            return build();
        }

        public Outcome build() {
            return new Outcome(run.build(), List.copyOf(evidence), List.copyOf(notes));
        }
    }
}
