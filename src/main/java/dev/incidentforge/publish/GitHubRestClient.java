package dev.incidentforge.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Minimal GitHub REST client with a deliberately tiny surface: read refs/files/PRs, create a
 * branch, write a file to that branch, open a DRAFT pull request. There is no merge, delete,
 * review-approval or settings method, and {@link #send} refuses any endpoint outside the
 * allowlist below, so no code path in this application can merge or rewrite the default branch.
 */
public class GitHubRestClient {

    private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$");
    private static final Pattern ALLOWED_SUFFIX = Pattern.compile(
            "^/(git/ref/heads/[A-Za-z0-9._/-]+|git/refs|contents/[A-Za-z0-9._/-]+(\\?ref=[A-Za-z0-9._/-]+)?|pulls(\\?[A-Za-z0-9=&:%._/-]+)?)$");

    public record FileContent(String content, String sha) {
    }

    public record PullRef(int number, String url, boolean draft) {
    }

    /** The request may or may not have been applied by GitHub (timeout, connection reset, 5xx). */
    public static class UncertainWriteException extends RuntimeException {
        public UncertainWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final String apiUrl;
    private final String repository;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public GitHubRestClient(String apiUrl, String repository, String token, Duration timeout, ObjectMapper objectMapper) {
        if (repository == null || !REPOSITORY.matcher(repository).matches()) {
            throw new IllegalArgumentException("incidentforge.github.repository must be owner/name");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("incidentforge.github.mode=github requires INCIDENTFORGE_GITHUB_TOKEN");
        }
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.repository = repository;
        this.token = token;
        this.timeout = timeout;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String repository() {
        return repository;
    }

    public Optional<String> refSha(String branch) {
        Response r = send("GET", "/git/ref/heads/" + branch, null);
        if (r.status() == 404) {
            return Optional.empty();
        }
        expect(r, 200);
        return Optional.of(r.body().path("object").path("sha").asText());
    }

    /** Returns false if the branch already exists (422), which callers treat as success. */
    public boolean createBranch(String branch, String sha) {
        Response r = send("POST", "/git/refs", Map.of("ref", "refs/heads/" + branch, "sha", sha));
        if (r.status() == 422) {
            return false;
        }
        expect(r, 201);
        return true;
    }

    public Optional<FileContent> file(String path, String ref) {
        Response r = send("GET", "/contents/" + path + "?ref=" + ref, null);
        if (r.status() == 404) {
            return Optional.empty();
        }
        expect(r, 200);
        String encoded = r.body().path("content").asText().replace("\n", "");
        String content = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        return Optional.of(new FileContent(content, r.body().path("sha").asText()));
    }

    public void putFile(String path, String branch, String content, String sha, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("branch", branch);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        if (sha != null) {
            body.put("sha", sha);
        }
        Response r = send("PUT", "/contents/" + path, body);
        if (r.status() != 200 && r.status() != 201) {
            expect(r, 201);
        }
    }

    public Optional<PullRef> findPullByHead(String branch) {
        String owner = repository.substring(0, repository.indexOf('/'));
        String head = URLEncoder.encode(owner + ":" + branch, StandardCharsets.UTF_8);
        Response r = send("GET", "/pulls?state=all&head=" + head, null);
        expect(r, 200);
        JsonNode first = r.body().path(0);
        if (first.isMissingNode()) {
            return Optional.empty();
        }
        return Optional.of(new PullRef(first.path("number").asInt(), first.path("html_url").asText(),
                first.path("draft").asBoolean()));
    }

    public PullRef createDraftPull(String title, String head, String base, String body) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", title);
        request.put("head", head);
        request.put("base", base);
        request.put("body", body);
        request.put("draft", true);
        request.put("maintainer_can_modify", false);
        Response r = send("POST", "/pulls", request);
        expect(r, 201);
        return new PullRef(r.body().path("number").asInt(), r.body().path("html_url").asText(),
                r.body().path("draft").asBoolean());
    }

    private record Response(int status, JsonNode body) {
    }

    private Response send(String method, String suffix, Object body) {
        if (!(method.equals("GET") || method.equals("POST") || method.equals("PUT"))
                || !ALLOWED_SUFFIX.matcher(suffix).matches() || suffix.contains("merge") || suffix.contains("..")) {
            throw new IllegalArgumentException("GitHub endpoint not permitted: " + method + " " + suffix);
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiUrl + "/repos/" + repository + suffix))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "incidentforge-agent");
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500 && !method.equals("GET")) {
                throw new UncertainWriteException("GitHub returned " + response.statusCode() + " for " + method, null);
            }
            JsonNode json = response.body() == null || response.body().isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
            return new Response(response.statusCode(), json);
        } catch (IOException e) {
            // Includes HttpTimeoutException: for writes we cannot know whether GitHub applied the request.
            throw new UncertainWriteException("GitHub " + method + " did not complete: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncertainWriteException("interrupted", e);
        }
    }

    private static void expect(Response r, int status) {
        if (r.status() != status) {
            throw new IllegalStateException("GitHub returned HTTP " + r.status() + " (expected " + status + "): "
                    + r.body().path("message").asText());
        }
    }
}
