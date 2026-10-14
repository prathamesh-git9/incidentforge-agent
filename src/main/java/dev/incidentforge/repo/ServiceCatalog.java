package dev.incidentforge.repo;

import dev.incidentforge.config.IncidentForgeProperties;
import dev.incidentforge.config.IncidentForgeProperties.ServiceProfile;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Allowlist of investigable services, each mapped to exactly one patchable repository. */
@Component
public class ServiceCatalog {

    private final Map<String, ServiceProfile> services;
    private final Map<String, RepositoryReader> readers = new ConcurrentHashMap<>();

    public ServiceCatalog(IncidentForgeProperties properties) {
        this.services = Map.copyOf(properties.services());
    }

    public Optional<ServiceProfile> find(String service) {
        return Optional.ofNullable(service).map(services::get);
    }

    public ServiceProfile require(String service) {
        return find(service).orElseThrow(() -> new IllegalArgumentException("Service is not allowlisted: " + service));
    }

    public RepositoryReader repository(String service) {
        ServiceProfile profile = require(service);
        return readers.computeIfAbsent(service, s -> new RepositoryReader(profile.repository()));
    }
}
