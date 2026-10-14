package dev.incidentforge.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.incidentforge.domain.Artifacts.PublishResult;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the GitHub publisher against an in-process fake of the GitHub REST API. */
class GitHubDraftPublisherTest {

    private static final String REPO = "acme/orders-consumer";
    private static final String FILE = "src/main/java/com/example/orders/OrderEventProcessor.java";
    private static final String TEST = "src/test/java/com/example/orders/PoisonEventReproTest.java";

    private final ObjectMapper om = new ObjectMapper();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, String>> branches = new HashMap<>();
    private final List<ObjectNode> pulls = new CopyOnWriteArrayList<>();
    private volatile boolean loseCreateResponse;
    private HttpServer server;
    private GitHubDraftPublisher publisher;

    @BeforeEach
    void start() throws IOException {
        branches.put("main", new HashMap<>(Map.of(FILE, "original\n")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        GitHubRestClient client = new GitHubRestClient("http://127.0.0.1:" + server.getAddress().getPort(), REPO,
                "test-token-not-real", Duration.ofMillis(800), om);
        publisher = new GitHubDraftPublisher(client, "main", "", 3, Duration.ofMillis(300));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void createsBranchCommitsAndOpensADraftWithoutAnyMergeCall() {
        PublishResult result = publisher.publish(request());

        assertThat(result.outcome()).isEqualTo("PUBLISHED");
        assertThat(result.number()).isEqualTo(1);
        assertThat(branches.get("incidentforge/run-0000000000ab")).containsEntry(FILE, "fixed\n").containsKey(TEST);
        assertThat(branches.get("main")).containsEntry(FILE, "original\n");
        assertThat(pulls.getFirst().path("draft").asBoolean()).isTrue();
        assertThat(requests).noneMatch(r -> r.contains("merge"));
        assertThat(requests).anyMatch(r -> r.startsWith("POST /repos/" + REPO + "/pulls"));
    }

    @Test
    void lostCreateResponseIsReconciledInsteadOfOpeningASecondPr() {
        loseCreateResponse = true;

        PublishResult first = publisher.publish(request());
        assertThat(first.outcome()).isEqualTo("PUBLISHED");
        assertThat(first.note()).contains("reconcile");

        loseCreateResponse = false;
        PublishResult redelivered = publisher.publish(request());
        assertThat(redelivered.note()).contains("already existed");
        assertThat(pulls).hasSize(1);
        assertThat(requests.stream().filter(r -> r.startsWith("PUT ")).count()).isEqualTo(2);
    }

    @Test
    void remoteDriftSinceValidationStopsPublication() {
        branches.get("main").put(FILE, "someone else changed this\n");

        assertThatThrownBy(() -> publisher.publish(request()))
                .isInstanceOf(PullRequestPublisher.PublishConflictException.class);
        assertThat(pulls).isEmpty();
    }

    @Test
    void clientSurfaceHasNoMergeOrDeleteCapability() {
        assertThat(Arrays.stream(GitHubRestClient.class.getDeclaredMethods()).map(Method::getName))
                .noneMatch(n -> n.toLowerCase().contains("merge") || n.toLowerCase().contains("delete")
                        || n.toLowerCase().contains("approve"));
        assertThatThrownBy(() -> new GitHubRestClient("http://x", "not a repo", "t", Duration.ofSeconds(1), om))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PullRequestPublisher.Request request() {
        return new PullRequestPublisher.Request("run-0000000000ab", "IncidentForge: fix", "body",
                "incidentforge/run-0000000000ab", Map.of(FILE, "fixed", TEST, "class T {}"),
                Map.of(FILE, "original"), "diff", "sha");
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        requests.add(method + " " + path + (query == null ? "" : "?" + query));
        String prefix = "/repos/" + REPO;
        assertThat(ex.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-token-not-real");
        String rest = path.substring(prefix.length());
        JsonNode body = method.equals("GET") ? null : om.readTree(ex.getRequestBody());
        if (method.equals("GET") && rest.startsWith("/git/ref/heads/")) {
            String branch = rest.substring("/git/ref/heads/".length());
            if (!branches.containsKey(branch)) {
                respond(ex, 404, om.createObjectNode().put("message", "Not Found"));
            } else {
                ObjectNode ref = om.createObjectNode();
                ref.putObject("object").put("sha", "sha-" + branch.hashCode());
                respond(ex, 200, ref);
            }
        } else if (method.equals("POST") && rest.equals("/git/refs")) {
            String branch = body.path("ref").asText().substring("refs/heads/".length());
            if (branches.containsKey(branch)) {
                respond(ex, 422, om.createObjectNode().put("message", "Reference already exists"));
            } else {
                branches.put(branch, new HashMap<>(branches.get("main")));
                respond(ex, 201, om.createObjectNode());
            }
        } else if (rest.startsWith("/contents/")) {
            String file = rest.substring("/contents/".length());
            if (method.equals("GET")) {
                String ref = URLDecoder.decode(query.substring("ref=".length()), StandardCharsets.UTF_8);
                String content = branches.get(ref).get(file);
                if (content == null) {
                    respond(ex, 404, om.createObjectNode().put("message", "Not Found"));
                } else {
                    respond(ex, 200, om.createObjectNode().put("sha", "blob-" + content.hashCode()).put("content",
                            Base64.getMimeEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))));
                }
            } else {
                String content = new String(Base64.getDecoder().decode(body.path("content").asText()),
                        StandardCharsets.UTF_8);
                branches.get(body.path("branch").asText()).put(file, content);
                respond(ex, 201, om.createObjectNode());
            }
        } else if (method.equals("GET") && rest.equals("/pulls")) {
            String head = URLDecoder.decode(query.replaceAll(".*head=", ""), StandardCharsets.UTF_8);
            ArrayNode found = om.createArrayNode();
            pulls.stream().filter(p -> ("acme:" + p.path("head").asText()).equals(head)).forEach(found::add);
            respond(ex, 200, found);
        } else if (method.equals("POST") && rest.equals("/pulls")) {
            ObjectNode pr = om.createObjectNode().put("number", pulls.size() + 1)
                    .put("html_url", "https://github.example/" + REPO + "/pull/" + (pulls.size() + 1))
                    .put("draft", body.path("draft").asBoolean()).put("head", body.path("head").asText());
            pulls.add(pr);
            if (loseCreateResponse) {
                sleep(1500); // the PR exists, but the client times out before it hears about it
            }
            respond(ex, 201, pr);
        } else {
            respond(ex, 404, om.createObjectNode().put("message", "unexpected " + method + " " + path));
        }
    }

    private void respond(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = om.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // client already gave up (lost-response scenario)
        } finally {
            ex.close();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
