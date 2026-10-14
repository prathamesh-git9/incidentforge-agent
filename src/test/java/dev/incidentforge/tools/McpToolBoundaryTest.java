package dev.incidentforge.tools;

import static org.assertj.core.api.Assertions.assertThat;

import dev.incidentforge.domain.Stage;
import dev.incidentforge.store.AuditRepository;
import dev.incidentforge.tools.ToolGateway.CallContext;
import dev.incidentforge.tools.ToolGateway.Decision;
import dev.incidentforge.tools.ToolGateway.ToolCallResult;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"offline", "test"})
class McpToolBoundaryTest {

    private static final String RUN = "run-0000000000aa";
    private static final String PROCESSOR = "src/main/java/com/example/orders/OrderEventProcessor.java";

    @Autowired
    McpToolClient mcp;
    @Autowired
    ToolGateway gateway;
    @Autowired
    AuditRepository audit;
    @Autowired
    TestRestTemplate rest;

    @Test
    void realMcpClientDiscoversOnlyTheReadOnlyCatalog() {
        assertThat(mcp.listToolNames()).containsExactly("code_read", "code_search", "deploys_list", "flags_list",
                "logs_search", "metrics_query", "traces_search");
    }

    @Test
    void mcpEndpointRequiresTheToolsRole() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> body = new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", headers);

        assertThat(rest.exchange("/mcp", HttpMethod.POST, body, String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(rest.withBasicAuth("reporter", "demo-reporter-only")
                .exchange("/mcp", HttpMethod.POST, body, String.class).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void gatewayAuthorizesValidatesAndAuditsEveryCall() {
        CallContext fixer = new CallContext(RUN, "orders-consumer", Stage.PROPOSE_FIX, "fixer",
                Set.of("code_read", "code_search"));
        CallContext collector = new CallContext(RUN, "orders-consumer", Stage.COLLECT_CONTEXT, "collector", null);

        ToolCallResult merge = gateway.call(fixer, "github_merge_pull_request", Map.of("number", 1));
        ToolCallResult wrongStage = gateway.call(collector, "code_read", Map.of("service", "orders-consumer",
                "path", PROCESSOR));
        ToolCallResult traversal = gateway.call(fixer, "code_read", Map.of("service", "orders-consumer",
                "path", "../../../../.ssh/id_rsa"));
        ToolCallResult otherService = gateway.call(fixer, "code_read", Map.of("service", "payments-api",
                "path", PROCESSOR));
        ToolCallResult outOfScope = gateway.call(fixer, "logs_search", Map.of("service", "orders-consumer",
                "from", "2026-10-12T10:00:00Z", "to", "2026-10-12T11:00:00Z"));
        ToolCallResult allowed = gateway.call(fixer, "code_read", Map.of("service", "orders-consumer",
                "path", PROCESSOR, "startLine", 45, "endLine", 52));

        assertThat(merge.decision()).isEqualTo(Decision.DENIED);
        assertThat(merge.reason()).contains("unknown tool");
        assertThat(wrongStage.reason()).contains("not permitted in stage COLLECT_CONTEXT");
        assertThat(traversal.reason()).contains("schema");
        assertThat(otherService.reason()).contains("service must be the run's service");
        assertThat(outOfScope.reason()).contains("not permitted in stage PROPOSE_FIX");
        assertThat(allowed.decision()).isEqualTo(Decision.ALLOWED);
        assertThat(allowed.text()).contains("source.seek(record.offset());");
        assertThat(allowed.sha256()).hasSize(64);

        assertThat(audit.toolCalls(RUN)).hasSize(6)
                .filteredOn(c -> c.decision().equals("DENIED")).hasSize(5);
    }

    @Test
    void serverEnforcesItsOwnLimitsEvenWhenTheGatewayIsBypassed() {
        McpToolClient.RawResult unknownService = mcp.call("flags_list", Map.of("service", "payments-api"));
        McpToolClient.RawResult escape = mcp.call("code_read", Map.of("service", "orders-consumer",
                "path", "src/main/java/com/example/orders/../../../../../../pom.java"));

        assertThat(unknownService.isError()).isTrue();
        assertThat(unknownService.text()).contains("non-allowlisted service");
        assertThat(escape.isError()).isTrue();
    }
}
