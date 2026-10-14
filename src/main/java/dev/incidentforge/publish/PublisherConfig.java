package dev.incidentforge.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.config.IncidentForgeProperties;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PublisherConfig {

    @Bean
    @ConditionalOnMissingBean(PullRequestPublisher.class)
    PullRequestPublisher pullRequestPublisher(IncidentForgeProperties properties, ObjectMapper objectMapper) {
        IncidentForgeProperties.GitHub github = properties.github();
        return switch (github.mode()) {
            case "local" -> new LocalDraftPublisher(github.localOutbox(), objectMapper);
            case "github" -> new GitHubDraftPublisher(
                    new GitHubRestClient(github.apiUrl(), github.repository(), github.token(), github.timeout(),
                            objectMapper),
                    github.baseBranch(), github.pathPrefix(), github.reconcileAttempts(), Duration.ofSeconds(1));
            default -> throw new IllegalStateException("Unsupported incidentforge.github.mode: " + github.mode());
        };
    }
}
