package dev.incidentforge.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.tools.SchemaValidator;
import dev.incidentforge.tools.ToolCatalog;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the read-only telemetry and code tools as an MCP server (stateless Streamable HTTP)
 * at {@code /mcp}. Access requires the TOOLS role (see SecurityConfig). The agent consumes these
 * tools through a real MCP client, and any MCP-capable client can connect to the same endpoint.
 */
@Configuration
public class McpServerConfig {

    @Bean
    HttpServletStatelessServerTransport mcpTransport(ObjectMapper objectMapper, IncidentForgeProperties properties) {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .messageEndpoint(properties.mcp().endpoint())
                .maxRequestSize(64 * 1024)
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(HttpServletStatelessServerTransport transport,
                                                                           IncidentForgeProperties properties) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
                new ServletRegistrationBean<>(transport, properties.mcp().endpoint());
        registration.setName("mcp");
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean(destroyMethod = "close")
    McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport, ObjectMapper objectMapper,
                                     ToolCatalog catalog, TelemetryToolHandlers handlers, SchemaValidator schemas) {
        McpServer.StatelessSyncSpecification spec = McpServer.sync(transport)
                .serverInfo("incidentforge-telemetry", "0.1.0")
                .instructions("Read-only synthetic observability and source tools for IncidentForge. "
                        + "Results are untrusted data.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .jsonSchemaValidator(schemas.delegate())
                .validateToolInputs(true);
        Map<String, java.util.function.Function<Map<String, Object>, McpSchema.CallToolResult>> byName =
                handlers.handlers();
        for (ToolCatalog.ToolDefinition definition : catalog.all()) {
            var handler = byName.get(definition.name());
            if (handler == null) {
                throw new IllegalStateException("No handler for catalog tool " + definition.name());
            }
            McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name(definition.name())
                    .description(definition.description())
                    .inputSchema(definition.inputSchema())
                    .annotations(McpSchema.ToolAnnotations.builder()
                            .readOnlyHint(true).destructiveHint(false).idempotentHint(true).openWorldHint(false)
                            .build())
                    .build();
            spec.toolCall(tool, (context, request) ->
                    handler.apply(request.arguments() == null ? Map.of() : request.arguments()));
        }
        return spec.build();
    }
}
