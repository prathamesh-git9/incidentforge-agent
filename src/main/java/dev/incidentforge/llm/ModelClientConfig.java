package dev.incidentforge.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.config.IncidentForgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelClientConfig {

    @Bean
    @ConditionalOnMissingBean(ModelClient.class)
    ModelClient modelClient(IncidentForgeProperties properties, ObjectMapper objectMapper) {
        return switch (properties.llm().provider()) {
            case "offline" -> new OfflineModelClient(objectMapper);
            case "anthropic" -> new AnthropicModelClient(properties.llm().anthropic());
            default -> throw new IllegalStateException("Unsupported incidentforge.llm.provider: "
                    + properties.llm().provider() + " (expected offline or anthropic)");
        };
    }
}
