package dev.incidentforge.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.incidentforge.config.IncidentForgeProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Claude adapter's request shape and error classification against a local fake of
 * the Messages API. No real key or network access is used.
 */
class AnthropicModelClientTest {

    private static final String KEY = "test-key-" + "not-a-real-key";

    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, String> captured = new ConcurrentHashMap<>();
    private volatile int status = 200;
    private volatile String responseBody;
    private HttpServer server;
    private AnthropicModelClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            captured.put("x-api-key", String.valueOf(exchange.getRequestHeaders().getFirst("x-api-key")));
            captured.put("anthropic-version", String.valueOf(exchange.getRequestHeaders().getFirst("anthropic-version")));
            captured.put("anthropic-beta", String.valueOf(exchange.getRequestHeaders().getFirst("anthropic-beta")));
            captured.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new AnthropicModelClient(new IncidentForgeProperties.Llm.Anthropic(KEY,
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5), 0, true));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsStageBudgetAndReplaysTurnsThenParsesTheReply() throws Exception {
        responseBody = message("end_turn", "{\"action\":\"final\",\"result\":{}}");

        ModelClient.Reply reply = client.complete(request());

        assertThat(reply.text()).isEqualTo("{\"action\":\"final\",\"result\":{}}");
        assertThat(reply.inputTokens()).isEqualTo(120);
        assertThat(reply.outputTokens()).isEqualTo(15);
        assertThat(captured.get("x-api-key")).isEqualTo(KEY);
        assertThat(captured.get("anthropic-version")).isNotBlank().isNotEqualTo("null");
        assertThat(captured.get("anthropic-beta")).contains("server-side-fallback-2026-07-01");
        JsonNode body = om.readTree(captured.get("body"));
        assertThat(body.path("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(2000);
        assertThat(body.path("system").asText()).isEqualTo("system prompt");
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(body.path("fallbacks").asText()).isEqualTo("default");
        assertThat(body.path("messages")).hasSize(3);
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("assistant");
    }

    @Test
    void rateLimitsAndOverloadAreTransientSoTheStageCanBeRetried() {
        status = 429;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}";
        assertThatThrownBy(() -> client.complete(request())).isInstanceOf(ModelClient.TransientModelException.class);

        status = 529;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}";
        assertThatThrownBy(() -> client.complete(request())).isInstanceOf(ModelClient.TransientModelException.class);
    }

    @Test
    void badRequestsAndRefusalsStopForAHuman() {
        status = 400;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}";
        assertThatThrownBy(() -> client.complete(request())).isInstanceOf(ModelClient.ModelCallException.class);

        status = 200;
        responseBody = message("refusal", "");
        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(ModelClient.ModelCallException.class)
                .hasMessageContaining("refusal");
    }

    @Test
    void refusesToStartWithoutAKey() {
        assertThatThrownBy(() -> new AnthropicModelClient(new IncidentForgeProperties.Llm.Anthropic("", "http://x",
                Duration.ofSeconds(1), 0, false))).isInstanceOf(IllegalStateException.class);
    }

    private static ModelClient.Request request() {
        return new ModelClient.Request("run-0000000000ac", "CLASSIFY", "classifier", "claude-sonnet-5-5", "low", 2000,
                Duration.ofSeconds(5), "system prompt", List.of(
                ModelClient.Turn.user("first"),
                new ModelClient.Turn(ModelClient.Role.ASSISTANT, "{\"action\":\"call_tools\"}", null),
                ModelClient.Turn.user("tool results")));
    }

    private static String message(String stopReason, String text) {
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                 "content":[{"type":"text","text":%s}],
                 "stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":120,"output_tokens":15}}"""
                .formatted(new ObjectMapper().valueToTree(text).toString(), stopReason);
    }
}
