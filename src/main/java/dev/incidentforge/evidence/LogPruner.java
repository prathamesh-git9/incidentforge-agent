package dev.incidentforge.evidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic log pruning: collapse lines into signatures (level + logger + message with
 * numbers and ids normalized), count them, keep one redacted sample and the first stack trace,
 * and rank so errors and high-volume warnings survive the budget. Runs before any model sees
 * the data.
 */
public final class LogPruner {

    private static final Pattern REPEAT_SUFFIX = Pattern.compile("\\s*\\(\\d+ similar [^)]*\\)$");
    private static final Pattern HEX = Pattern.compile("\\b[0-9a-f]{12,}\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final int MAX_STACK_LINES = 10;

    public record Line(Instant timestamp, String level, String logger, String message, String stack) {
    }

    public record Signature(String level, String logger, String fingerprint, String sample, String stack,
                            long count, Instant firstSeen, Instant lastSeen) {
    }

    public record Result(List<Signature> kept, long rawLines, long rawBytes, int totalSignatures) {
    }

    private LogPruner() {
    }

    public static Result prune(List<Line> lines, int maxSignatures) {
        Map<String, Accumulator> byFingerprint = new LinkedHashMap<>();
        long bytes = 0;
        for (Line line : lines) {
            bytes += line.message().length() + (line.stack() == null ? 0 : line.stack().length()) + 48;
            String fingerprint = fingerprint(line);
            byFingerprint.computeIfAbsent(fingerprint, f -> new Accumulator(line, f)).add(line);
        }
        List<Signature> ranked = new ArrayList<>(byFingerprint.values().stream().map(Accumulator::toSignature).toList());
        ranked.sort(Comparator.comparingInt((Signature s) -> levelWeight(s.level())).reversed()
                .thenComparing(Comparator.comparingLong(Signature::count).reversed()));
        List<Signature> kept = ranked.subList(0, Math.min(maxSignatures, ranked.size()));
        return new Result(List.copyOf(kept), lines.size(), bytes, ranked.size());
    }

    static String fingerprint(Line line) {
        String message = REPEAT_SUFFIX.matcher(line.message()).replaceAll("");
        message = HEX.matcher(message).replaceAll("<hex>");
        message = NUMBER.matcher(message).replaceAll("#");
        if (message.length() > 160) {
            message = message.substring(0, 160);
        }
        return line.level() + "|" + line.logger() + "|" + message;
    }

    private static int levelWeight(String level) {
        return switch (level) {
            case "ERROR" -> 3;
            case "WARN" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    private static final class Accumulator {
        private final Line first;
        private final String fingerprint;
        private long count;
        private Instant lastSeen;
        private String stack;

        Accumulator(Line first, String fingerprint) {
            this.first = first;
            this.fingerprint = fingerprint;
        }

        void add(Line line) {
            count++;
            lastSeen = line.timestamp();
            if (stack == null && line.stack() != null) {
                stack = line.stack().lines().limit(MAX_STACK_LINES).reduce((a, b) -> a + "\n" + b).orElse(null);
            }
        }

        Signature toSignature() {
            return new Signature(first.level(), first.logger(), fingerprint, first.message(), stack, count,
                    first.timestamp(), lastSeen);
        }
    }
}
