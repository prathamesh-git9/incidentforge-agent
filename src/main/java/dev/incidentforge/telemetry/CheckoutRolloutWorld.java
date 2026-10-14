package dev.incidentforge.telemetry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.stereotype.Component;

/**
 * Seeded bad-feature-rollout incident for the synthetic {@code checkout-api} service.
 *
 * <p>Story: at 14:02 the {@code checkout.new-tax-engine} flag ramps from 0% to 25%. No code is
 * deployed (the running version, 3.2.1, is three days old). The new engine's rate table only
 * covers migrated EU regions and has lost its legacy fallback, so quotes for Swiss carts
 * (region CH) in the flagged cohort fail with a NullPointerException. The error rate rises for CH
 * only; DE/FR stay flat.
 *
 * <p>Deliberate distractors: payment-gateway latency warnings that are just as frequent before the
 * onset as after it, an express-pay flag change 32 minutes earlier, and the most recent deploy,
 * which only touched the payment client. Every value is generated from a fixed seed.
 */
@Component
public class CheckoutRolloutWorld implements TelemetryWorld {

    public static final String SERVICE = "checkout-api";
    public static final long SEED = 20261014L;
    public static final String FLAG = "checkout.new-tax-engine";
    public static final Instant WINDOW_START = Instant.parse("2026-10-13T13:30:00Z");
    public static final Instant EXPRESS_PAY_FLAG = Instant.parse("2026-10-13T13:30:00Z");
    public static final Instant LOG_START = Instant.parse("2026-10-13T13:55:00Z");
    public static final Instant FLAG_RAMP = Instant.parse("2026-10-13T14:02:00Z");
    public static final Instant ONSET = Instant.parse("2026-10-13T14:02:20Z");
    public static final Instant DETECTED = Instant.parse("2026-10-13T14:35:00Z");

    public static final String V2_FILE = "src/main/java/com/example/checkout/TaxCalculatorV2.java";
    public static final String NPE_MESSAGE = "Cannot invoke \"java.lang.Integer.intValue()\" because the return value "
            + "of \"java.util.Map.get(Object)\" is null";
    public static final String STACK = """
            java.lang.NullPointerException: %s
            \tat com.example.checkout.TaxCalculatorV2.taxMinor(TaxCalculatorV2.java:23)
            \tat com.example.checkout.CheckoutService.quote(CheckoutService.java:22)
            \tat java.base/java.lang.Thread.run(Thread.java:1583)""".formatted(NPE_MESSAGE);

    private static final List<String> REGIONS = List.of("DE", "DE", "FR", "NL", "CH", "ES", "IE", "DE", "FR", "CH");

    private final List<LogEntry> logs;
    private final Map<String, List<Series>> metrics;
    private final List<Span> spans;

    public CheckoutRolloutWorld() {
        Random random = new Random(SEED);
        List<LogEntry> generatedLogs = new ArrayList<>();
        List<Span> generatedSpans = new ArrayList<>();
        generateTraffic(random, generatedLogs, generatedSpans);
        generatedLogs.sort((a, b) -> a.timestamp().compareTo(b.timestamp()));
        this.logs = Collections.unmodifiableList(generatedLogs);
        this.spans = Collections.unmodifiableList(generatedSpans);
        this.metrics = generateMetrics(random);
    }

    @Override
    public String service() {
        return SERVICE;
    }

    @Override
    public List<LogEntry> logs() {
        return logs;
    }

    @Override
    public List<Series> metric(String name) {
        return metrics.getOrDefault(name, List.of());
    }

    @Override
    public List<Span> spans() {
        return spans;
    }

    @Override
    public List<Deploy> deploys() {
        return List.of(new Deploy("3.2.1", Instant.parse("2026-10-10T11:00:00Z"), "e93f07c",
                "Payment gateway client: tighter read timeout (#512)",
                List.of("src/main/java/com/example/checkout/PaymentGatewayClient.java")));
    }

    @Override
    public List<Flag> flags() {
        return List.of(
                new Flag("checkout.express-pay", true, 100, EXPRESS_PAY_FLAG,
                        "Express-pay button on the cart page (ramped 50% -> 100%)",
                        List.of("src/main/java/com/example/checkout/ExpressPayButton.java")),
                new Flag(FLAG, true, 25, FLAG_RAMP, "New tax engine for quotes (ramped 0% -> 25%)",
                        List.of(V2_FILE, "src/main/java/com/example/checkout/CheckoutService.java")));
    }

    private static void generateTraffic(Random random, List<LogEntry> logs, List<Span> spans) {
        int cart = 4_000;
        for (Instant t = LOG_START; t.isBefore(DETECTED); t = t.plusSeconds(2)) {
            for (int i = 0; i < 3; i++) {
                Instant at = t.plusMillis(random.nextInt(1_900));
                String region = REGIONS.get(random.nextInt(REGIONS.size()));
                boolean flagged = !at.isBefore(FLAG_RAMP) && random.nextInt(4) == 0;
                String cartId = "c-" + cart++;
                if (flagged && region.equals("CH")) {
                    logs.add(new LogEntry(at, "ERROR", "c.e.c.QuoteController",
                            "quote failed cart=" + cartId + " region=CH engine=v2", STACK));
                    Map<String, String> attributes = new LinkedHashMap<>();
                    attributes.put("http.route", "/checkout/quote");
                    attributes.put("checkout.region", "CH");
                    attributes.put("feature_flag." + FLAG, "true");
                    attributes.put("service.version", "3.2.1");
                    attributes.put("exception.type", "java.lang.NullPointerException");
                    attributes.put("code.function", "TaxCalculatorV2.taxMinor");
                    attributes.put("code.filepath", V2_FILE);
                    attributes.put("code.lineno", "23");
                    spans.add(new Span(TelemetryWorld.traceId(random), "POST /checkout/quote", at,
                            4 + random.nextInt(6), "ERROR", Collections.unmodifiableMap(attributes)));
                } else {
                    logs.add(new LogEntry(at, "INFO", "c.e.c.QuoteController", "quote ok cart=" + cartId
                            + " region=" + region + " engine=" + (flagged ? "v2" : "legacy"), null));
                }
            }
            if (t.getEpochSecond() % 44 == 0) {
                logs.add(new LogEntry(t.plusMillis(300), "WARN", "c.e.c.PaymentGatewayClient",
                        "gateway latency " + (810 + random.nextInt(90)) + "ms above 800ms threshold", null));
            }
        }
    }

    private static Map<String, List<Series>> generateMetrics(Random random) {
        Map<String, List<Series>> out = new LinkedHashMap<>();
        List<Series> errors = new ArrayList<>();
        for (String region : List.of("CH", "DE", "FR")) {
            List<MetricPoint> points = new ArrayList<>();
            for (Instant t = WINDOW_START; !t.isAfter(DETECTED); t = t.plusSeconds(60)) {
                double value = 0.1 + random.nextInt(3) / 10.0;
                if (region.equals("CH") && t.isAfter(FLAG_RAMP)) {
                    value = 23.0 + random.nextInt(40) / 10.0;
                }
                points.add(new MetricPoint(t, Math.round(value * 10) / 10.0));
            }
            errors.add(new Series(Map.of("region", region, "version", "3.2.1"), points));
        }
        out.put("error_rate_percent", List.copyOf(errors));
        List<MetricPoint> gateway = new ArrayList<>();
        for (Instant t = WINDOW_START; !t.isAfter(DETECTED); t = t.plusSeconds(60)) {
            gateway.add(new MetricPoint(t, 700 + random.nextInt(90)));
        }
        out.put("payment_latency_p95_ms", List.of(new Series(Map.of("provider", "gateway-a"), gateway)));
        return Collections.unmodifiableMap(out);
    }
}
