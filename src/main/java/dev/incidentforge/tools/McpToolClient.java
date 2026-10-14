package dev.incidentforge.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.config.IncidentForgeProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * MCP client used by the agent. It connects lazily (after the web server is up) to the MCP
 * endpoint with its own least-privilege credential (role TOOLS: read-only tools, nothing else).
 */
@Component
public class McpToolClient implements DisposableBean {

    public record RawResult(String text, boolean isError) {
    }

    private final ApplicationContext context;
    private final IncidentForgeProperties properties;
    private final ObjectMapper objectMapper;
    private McpSyncClient client;

    public McpToolClient(ApplicationContext context, IncidentForgeProperties properties, ObjectMapper objectMapper) {
        this.context = context;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public RawResult call(String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client().callTool(new McpSchema.CallToolRequest(tool, arguments));
        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
        return new RawResult(text, Boolean.TRUE.equals(result.isError()));
    }

    public java.util.List<String> listToolNames() {
        return client().listTools().tools().stream().map(McpSchema.Tool::name).sorted().toList();
    }

    private synchronized McpSyncClient client() {
        if (client == null) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            IncidentForgeProperties.Mcp mcp = properties.mcp();
            String credentials = mcp.clientUsername() + ":" + mcp.clientPassword();
            String authorization = "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder("http://127.0.0.1:" + port)
                    .endpoint(mcp.endpoint())
                    .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                    .httpRequestCustomizer((builder, method, uri, body, ctx) ->
                            builder.header("Authorization", authorization))
                    .build();
            McpSyncClient created = McpClient.sync(transport)
                    .requestTimeout(mcp.requestTimeout())
                    .clientInfo(new McpSchema.Implementation("incidentforge-agent", "0.1.0"))
                    .build();
            created.initialize();
            client = created;
        }
        return client;
    }

    @Override
    public synchronized void destroy() {
        if (client != null) {
            client.closeGracefully();
        }
    }
}
