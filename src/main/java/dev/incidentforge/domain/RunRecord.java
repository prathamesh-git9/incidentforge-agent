package dev.incidentforge.domain;

import java.time.Instant;

/** Durable state of one investigation run. Stage artifacts are stored as JSON documents. */
public record RunRecord(
        String id,
        String incidentId,
        String service,
        RunStatus status,
        Stage stage,
        int version,
        int fixAttempts,
        int correctionRounds,
        String classificationJson,
        String rcaJson,
        String mitigationJson,
        String patchJson,
        String patchSha256,
        String validationJson,
        String fixFeedback,
        String publishJson,
        String approvedBy,
        String approvedPatchSha256,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Copy-on-write changes; the repository applies them with an optimistic version check. */
    public static final class Builder {
        private final RunRecord base;
        private RunStatus status;
        private Stage stage;
        private int fixAttempts;
        private int correctionRounds;
        private String classificationJson;
        private String rcaJson;
        private String mitigationJson;
        private String patchJson;
        private String patchSha256;
        private String validationJson;
        private String fixFeedback;
        private String publishJson;
        private String approvedBy;
        private String approvedPatchSha256;
        private String failureReason;

        private Builder(RunRecord base) {
            this.base = base;
            this.status = base.status;
            this.stage = base.stage;
            this.fixAttempts = base.fixAttempts;
            this.correctionRounds = base.correctionRounds;
            this.classificationJson = base.classificationJson;
            this.rcaJson = base.rcaJson;
            this.mitigationJson = base.mitigationJson;
            this.patchJson = base.patchJson;
            this.patchSha256 = base.patchSha256;
            this.validationJson = base.validationJson;
            this.fixFeedback = base.fixFeedback;
            this.publishJson = base.publishJson;
            this.approvedBy = base.approvedBy;
            this.approvedPatchSha256 = base.approvedPatchSha256;
            this.failureReason = base.failureReason;
        }

        public Builder status(RunStatus value) { this.status = value; return this; }
        public Builder stage(Stage value) { this.stage = value; return this; }
        public Builder fixAttempts(int value) { this.fixAttempts = value; return this; }
        public Builder correctionRounds(int value) { this.correctionRounds = value; return this; }
        public Builder classificationJson(String value) { this.classificationJson = value; return this; }
        public Builder rcaJson(String value) { this.rcaJson = value; return this; }
        public Builder mitigationJson(String value) { this.mitigationJson = value; return this; }
        public Builder patchJson(String value) { this.patchJson = value; return this; }
        public Builder patchSha256(String value) { this.patchSha256 = value; return this; }
        public Builder validationJson(String value) { this.validationJson = value; return this; }
        public Builder fixFeedback(String value) { this.fixFeedback = value; return this; }
        public Builder publishJson(String value) { this.publishJson = value; return this; }
        public Builder approvedBy(String value) { this.approvedBy = value; return this; }
        public Builder approvedPatchSha256(String value) { this.approvedPatchSha256 = value; return this; }
        public Builder failureReason(String value) { this.failureReason = value; return this; }

        public RunRecord build() {
            return new RunRecord(base.id, base.incidentId, base.service, status, stage, base.version, fixAttempts,
                    correctionRounds, classificationJson, rcaJson, mitigationJson, patchJson, patchSha256,
                    validationJson, fixFeedback, publishJson, approvedBy, approvedPatchSha256, failureReason,
                    base.createdAt, Instant.now());
        }
    }
}
