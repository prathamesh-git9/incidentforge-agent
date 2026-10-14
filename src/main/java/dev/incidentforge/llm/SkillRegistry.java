package dev.incidentforge.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Domain knowledge is kept outside the fixed pipeline skeleton: each service domain contributes
 * short per-stage guidance files ({@code skills/<domain>/<stage>.md}). Only the incident's own
 * domain is loaded, which keeps prompts small.
 */
@Component
public class SkillRegistry {

    private static final Pattern SAFE = Pattern.compile("^[a-z0-9-]{1,40}$");

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String skill(String domain, String stage) {
        if (!SAFE.matcher(domain).matches() || !SAFE.matcher(stage).matches()) {
            return "";
        }
        return cache.computeIfAbsent(domain + "/" + stage, key -> {
            ClassPathResource resource = new ClassPathResource("skills/" + key + ".md");
            if (!resource.exists()) {
                return "";
            }
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                return "";
            }
        });
    }
}
