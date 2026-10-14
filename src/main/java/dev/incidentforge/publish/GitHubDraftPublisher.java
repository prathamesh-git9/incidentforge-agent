package dev.incidentforge.publish;

import dev.incidentforge.domain.Artifacts.PublishResult;
import dev.incidentforge.publish.GitHubRestClient.FileContent;
import dev.incidentforge.publish.GitHubRestClient.PullRef;
import dev.incidentforge.publish.GitHubRestClient.UncertainWriteException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Opens a draft PR on the single allowlisted repository. Every step is idempotent and keyed on
 * a deterministic branch name ({@code incidentforge/<runId>}), so a redelivered PUBLISH command or
 * a retry after an uncertain write converges on one branch and one PR:
 * <ul>
 *   <li>an existing PR for the branch is reused;</li>
 *   <li>files already at the approved content are not rewritten;</li>
 *   <li>a remote file that differs from both the validated base and the approved content is a
 *       conflict (someone else changed it) and stops publication;</li>
 *   <li>if the PR-create response is lost, the publisher reconciles by looking the PR up by head
 *       branch before reporting PUBLISH_UNCERTAIN.</li>
 * </ul>
 */
public class GitHubDraftPublisher implements PullRequestPublisher {

    private final GitHubRestClient github;
    private final String baseBranch;
    private final String pathPrefix;
    private final int reconcileAttempts;
    private final Duration reconcileBackoff;

    public GitHubDraftPublisher(GitHubRestClient github, String baseBranch, String pathPrefix, int reconcileAttempts,
                                Duration reconcileBackoff) {
        this.github = github;
        this.baseBranch = baseBranch;
        this.pathPrefix = pathPrefix == null ? "" : pathPrefix;
        this.reconcileAttempts = reconcileAttempts;
        this.reconcileBackoff = reconcileBackoff;
    }

    @Override
    public PublishResult publish(Request request) {
        Optional<PullRef> existing = github.findPullByHead(request.branch());
        if (existing.isPresent()) {
            return published(existing.get(), request.branch(), "pull request already existed (idempotent re-delivery)");
        }
        String baseSha = github.refSha(baseBranch)
                .orElseThrow(() -> new IllegalStateException("base branch not found: " + baseBranch));
        if (github.refSha(request.branch()).isEmpty()) {
            github.createBranch(request.branch(), baseSha);
        }
        for (Map.Entry<String, String> entry : request.changedFiles().entrySet()) {
            String remotePath = pathPrefix + entry.getKey();
            String approved = entry.getValue();
            Optional<FileContent> current = github.file(remotePath, request.branch());
            if (current.isPresent() && same(current.get().content(), approved)) {
                continue;
            }
            String validatedBase = request.originalFiles().get(entry.getKey());
            boolean matchesBase = validatedBase == null ? current.isEmpty()
                    : current.isPresent() && same(current.get().content(), validatedBase);
            if (!matchesBase) {
                throw new PublishConflictException("remote " + remotePath
                        + " differs from the content the patch was validated against; re-run the investigation");
            }
            github.putFile(remotePath, request.branch(), withTrailingNewline(approved),
                    current.map(FileContent::sha).orElse(null), "IncidentForge: " + request.title());
        }
        try {
            PullRef created = github.createDraftPull(request.title(), request.branch(), baseBranch, request.body());
            return published(created, request.branch(), "draft pull request created");
        } catch (UncertainWriteException e) {
            return reconcile(request.branch(), e);
        }
    }

    private PublishResult reconcile(String branch, UncertainWriteException cause) {
        for (int attempt = 1; attempt <= reconcileAttempts; attempt++) {
            sleep(reconcileBackoff.multipliedBy(attempt));
            try {
                Optional<PullRef> found = github.findPullByHead(branch);
                if (found.isPresent()) {
                    return published(found.get(), branch,
                            "create response was lost (" + cause.getMessage() + "); PR found by head branch on reconcile");
                }
            } catch (RuntimeException lookupFailure) {
                // keep trying within the bounded number of attempts
            }
        }
        return new PublishResult("github", "UNCERTAIN", null, branch, null,
                "create outcome unknown after " + reconcileAttempts + " lookups; retry publish to reconcile again");
    }

    private static PublishResult published(PullRef pull, String branch, String note) {
        if (!pull.draft()) {
            note = note + "; WARNING: PR is not marked draft";
        }
        return new PublishResult("github", "PUBLISHED", pull.url(), branch, pull.number(), note);
    }

    private static boolean same(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String s) {
        String n = s.replace("\r\n", "\n");
        while (n.endsWith("\n")) {
            n = n.substring(0, n.length() - 1);
        }
        return n;
    }

    private static String withTrailingNewline(String s) {
        return s.endsWith("\n") ? s : s + "\n";
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
