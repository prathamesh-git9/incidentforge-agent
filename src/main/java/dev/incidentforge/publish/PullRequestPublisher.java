package dev.incidentforge.publish;

import dev.incidentforge.domain.Artifacts.PublishResult;
import java.util.Map;

/**
 * Publishes an already-approved patch for human review. Implementations create drafts only and
 * must be idempotent: publishing the same run twice must not create a second PR.
 */
public interface PullRequestPublisher {

    /**
     * @param changedFiles  path -> full new content
     * @param originalFiles path -> content the patch was validated against (absent for new files)
     */
    record Request(String runId, String title, String body, String branch, Map<String, String> changedFiles,
                   Map<String, String> originalFiles, String unifiedDiff, String patchSha256) {
    }

    PublishResult publish(Request request);

    /** Thrown when the remote repository no longer matches what was validated; never retried blindly. */
    class PublishConflictException extends RuntimeException {
        public PublishConflictException(String message) {
            super(message);
        }
    }
}
