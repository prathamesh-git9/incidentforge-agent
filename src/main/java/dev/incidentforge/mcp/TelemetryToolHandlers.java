package dev.incidentforge.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incidentforge.repo.RepositoryReader;
import dev.incidentforge.repo.ServiceCatalog;
import dev.incidentforge.telemetry.TelemetryWorld;
import dev.incidentforge.telemetry.TelemetryWorld.LogEntry;
import dev.incidentforge.telemetry.TelemetryWorld.Series;
import dev.incidentforge.telemetry.TelemetryWorld.Span;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Server-side implementations of the read-only MCP tools. These enforce their own limits
 * (service allowlist, window, page size, repository sandbox) independently of the agent-side
 * gateway, so a misbehaving client cannot widen what it can read.
 */
@Component
public class TelemetryToolHandlers {

    private static final List<String> LEVELS = List.of("DEBUG", "INFO", "WARN", "ERROR");

    private final Map<String, TelemetryWorld> worlds;
    private final ServiceCatalog services;
    private final ObjectMapper objectMapper;

    public TelemetryToolHandlers(List<TelemetryWorld> worlds, ServiceCatalog services, ObjectMapper objectMapper) {
        this.worlds = worlds.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                TelemetryWorld::service, w -> w));
        this.services = services;
        this.objectMapper = objectMapper;
    }

    private TelemetryWorld world(Map<String, Object> args) {
        return worlds.get((String) args.get("service"));
    }

    public Map<String, Function<Map<String, Object>, CallToolResult>> handlers() {
        return Map.of(
                "logs_search", this::logsSearch,
                "metrics_query", this::metricsQuery,
                "traces_search", this::tracesSearch,
                "deploys_list", this::deploysList,
                "flags_list", this::flagsList,
                "code_search", this::codeSearch,
                "code_read", this::codeRead);
    }

    CallToolResult logsSearch(Map<String, Object> args) {
        return guarded(args, true, () -> {
            Instant from = instant(args, "from");
            Instant to = instant(args, "to");
            int minLevel = LEVELS.indexOf((String) args.getOrDefault("minLevel", "INFO"));
            String query = (String) args.get("query");
            int cursor = intArg(args, "cursor", 0);
            int limit = intArg(args, "limit", 500);
            List<LogEntry> matched = world(args).logs().stream()
                    .filter(e -> !e.timestamp().isBefore(from) && e.timestamp().isBefore(to))
                    .filter(e -> LEVELS.indexOf(e.level()) >= minLevel)
                    .filter(e -> query == null || e.message().toLowerCase().contains(query.toLowerCase()))
                    .toList();
            int end = Math.min(matched.size(), cursor + limit);
            List<LogEntry> page = cursor >= matched.size() ? List.of() : matched.subList(cursor, end);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalMatched", matched.size());
            result.put("nextCursor", end < matched.size() ? end : null);
            result.put("entries", page);
            return result;
        });
    }

    CallToolResult metricsQuery(Map<String, Object> args) {
        return guarded(args, true, () -> {
            Instant from = instant(args, "from");
            Instant to = instant(args, "to");
            String metric = (String) args.get("metric");
            List<Series> series = world(args).metric(metric).stream()
                    .map(s -> new Series(s.labels(), s.points().stream()
                            .filter(p -> !p.timestamp().isBefore(from) && !p.timestamp().isAfter(to))
                            .toList()))
                    .toList();
            return Map.of("metric", metric, "stepSeconds", 60, "series", series);
        });
    }

    CallToolResult tracesSearch(Map<String, Object> args) {
        return guarded(args, true, () -> {
            Instant from = instant(args, "from");
            Instant to = instant(args, "to");
            String status = (String) args.getOrDefault("status", "ANY");
            int limit = intArg(args, "limit", 20);
            int minDurationMs = intArg(args, "minDurationMs", 0);
            List<Span> matched = world(args).spans().stream()
                    .filter(s -> !s.start().isBefore(from) && s.start().isBefore(to))
                    .filter(s -> "ANY".equals(status) || s.status().equals(status))
                    .filter(s -> s.durationMs() >= minDurationMs)
                    .toList();
            return Map.of("totalMatched", matched.size(), "spans", matched.stream().limit(limit).toList());
        });
    }

    CallToolResult deploysList(Map<String, Object> args) {
        return guarded(args, true, () -> {
            Instant since = instant(args, "since");
            return Map.of("deploys", world(args).deploys().stream().filter(d -> !d.at().isBefore(since)).toList());
        });
    }

    CallToolResult flagsList(Map<String, Object> args) {
        return guarded(args, true, () -> Map.of("flags", world(args).flags()));
    }

    CallToolResult codeSearch(Map<String, Object> args) {
        return guarded(args, false, () -> {
            RepositoryReader repo = services.repository((String) args.get("service"));
            return Map.of("matches", repo.search((String) args.get("query"), intArg(args, "limit", 20)));
        });
    }

    CallToolResult codeRead(Map<String, Object> args) {
        return guarded(args, false, () -> {
            RepositoryReader repo = services.repository((String) args.get("service"));
            int start = intArg(args, "startLine", 1);
            int end = intArg(args, "endLine", start + 199);
            return repo.read((String) args.get("path"), start, end);
        });
    }

    private interface Body {
        Object get();
    }

    private CallToolResult guarded(Map<String, Object> args, boolean telemetryTool, Body body) {
        String service = (String) args.get("service");
        if (services.find(service).isEmpty()) {
            return error("unknown or non-allowlisted service: " + service);
        }
        if (telemetryTool && !worlds.containsKey(service)) {
            return error("no telemetry source configured for service: " + service);
        }
        try {
            return CallToolResult.builder().addTextContent(objectMapper.writeValueAsString(body.get())).build();
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return error(e.getMessage());
        } catch (JsonProcessingException e) {
            return error("could not serialize result");
        }
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private static Instant instant(Map<String, Object> args, String name) {
        return Instant.parse((String) args.get(name));
    }

    private static int intArg(Map<String, Object> args, String name, int defaultValue) {
        Object value = args.get(name);
        return value instanceof Number n ? n.intValue() : defaultValue;
    }
}
