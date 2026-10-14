package dev.incidentforge.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with the committed demo passwords once anything real is wired in
 * (a paid model provider or real GitHub publishing).
 */
@Component
public class StartupGuard implements InitializingBean {

    private final IncidentForgeProperties properties;

    public StartupGuard(IncidentForgeProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        boolean realIntegrations = !"offline".equals(properties.llm().provider())
                || !"local".equals(properties.github().mode());
        boolean demoPasswords = properties.security().users().values().stream()
                .anyMatch(u -> u.password() != null && u.password().startsWith("demo-"));
        if (realIntegrations && demoPasswords) {
            throw new IllegalStateException("Demo passwords are not allowed with a real model provider or GitHub "
                    + "publishing; set INCIDENTFORGE_*_PASSWORD environment variables");
        }
        if (!properties.mcp().clientPassword().equals(properties.security().users().get(properties.mcp().clientUsername())
                .password())) {
            throw new IllegalStateException("incidentforge.mcp.client-password must match the TOOLS user password");
        }
    }
}
