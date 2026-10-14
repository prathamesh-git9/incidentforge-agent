package dev.incidentforge.support;

import dev.incidentforge.config.IncidentForgeProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

public final class TestSupport {

    public static final Path SAMPLE_REPO = Path.of("sample-repo", "orders-consumer");

    private TestSupport() {
    }

    /** Binds the real application.yml + offline + test profiles, without starting Spring. */
    public static IncidentForgeProperties properties() {
        StandardEnvironment env = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        try {
            for (String file : List.of("application.yml", "application-offline.yml", "application-test.yml")) {
                loader.load(file, new ClassPathResource(file)).forEach(ps -> env.getPropertySources().addFirst(ps));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Binder.get(env).bind("incidentforge", IncidentForgeProperties.class).get();
    }

    public static void await(String description, Duration timeout, Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + description);
            }
        }
        throw new AssertionError("timed out after " + timeout + " waiting for " + description);
    }

    public static String sampleIncident() {
        try {
            return Files.readString(Path.of("samples", "incidents", "orders-consumer-lag.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
