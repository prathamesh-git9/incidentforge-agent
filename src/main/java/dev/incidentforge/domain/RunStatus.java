package dev.incidentforge.domain;

public enum RunStatus {
    /** A stage command is queued or executing. */
    IN_PROGRESS,
    /** RCA, validated patch and evidence are ready; nothing leaves the system without a reviewer. */
    AWAITING_APPROVAL,
    /** The agent stopped on purpose (budget, policy, non-code root cause, failed validation). */
    NEEDS_HUMAN,
    /** A reviewer approved; the draft PR is being created. */
    PUBLISHING,
    /** A GitHub write may or may not have happened; reconciliation is required before retrying. */
    PUBLISH_UNCERTAIN,
    PUBLISHED,
    REJECTED,
    /** Retries exhausted or a poison command was dead-lettered. */
    FAILED;

    public boolean isTerminal() {
        return this == PUBLISHED || this == REJECTED || this == FAILED;
    }
}
