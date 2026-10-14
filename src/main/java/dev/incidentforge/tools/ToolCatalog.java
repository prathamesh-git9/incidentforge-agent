package dev.incidentforge.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for tool contracts. The MCP server advertises these schemas and the
 * agent-side {@link ToolGateway} validates against the same schemas before a call leaves the agent.
 */
@Component
public class ToolCatalog {

    public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
    }

    private final Map<String, ToolDefinition> tools;

    public ToolCatalog(ObjectMapper objectMapper) throws IOException {
        try (InputStream in = new ClassPathResource("tools/catalog.json").getInputStream()) {
            List<ToolDefinition> definitions = objectMapper.readValue(in, new TypeReference<>() {
            });
            Map<String, ToolDefinition> byName = new LinkedHashMap<>();
            definitions.forEach(d -> byName.put(d.name(), d));
            this.tools = Map.copyOf(byName);
        }
    }

    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> all() {
        return tools.values().stream().sorted((a, b) -> a.name().compareTo(b.name())).toList();
    }
}
