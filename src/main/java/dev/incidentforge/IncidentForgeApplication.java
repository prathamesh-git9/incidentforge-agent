package dev.incidentforge;

import dev.incidentforge.config.IncidentForgeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(IncidentForgeProperties.class)
public class IncidentForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncidentForgeApplication.class, args);
    }
}
