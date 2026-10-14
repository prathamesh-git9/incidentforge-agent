package dev.incidentforge.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.stereotype.Component;

/**
 * Seeded slow-read-path incident for the synthetic {@code catalog-api} service.
 *
 * <p>Story: release 2.4.0 (09:30) adds a review summary and the three most helpful reviews to the
 * product page. {@code ProductDetailService.detail} does this by loading <em>every</em> review row
 * and aggregating in Java. Ordinary products are unaffected; products with tens of thousands of
 * reviews now take ~2.4 s per page view, so the route's p95 jumps while {@code /search} stays flat.
 *
 * <p>Deliberate distractors: the restart empties the product cache, so the cache hit ratio dips
 * and "cache miss" warnings appear, but the ratio recovers by 09:50 while latency stays high, and the
 * slow spans are product-cache <em>hits</em>. A search-ranking flag ramp 21 minutes earlier touches
 * unrelated code. Every value is generated from a fixed seed.
 */
@Component
public class CatalogLatencyWorld implements TelemetryWorld {

    public static final String SERVICE = "catalog-api";
    public static final long SEED = 20261013L;
    public static final Instant WINDOW_START = Instant.parse("2026-10-13T09:00:00Z");
    public static final Instant FLAG_RAMP = Instant.parse("2026-10-13T09:10:00Z");
    public static final Instant LOG_START = Instant.parse("2026-10-13T09:25:00Z");
    public static final Instant DEPLOY_240 = Instant.parse("2026-10-13T09:30:00Z");
    public static final Instant ONSET = Instant.parse("2026-10-13T09:31:00Z");
    public static final Instant CACHE_RECOVERED = Instant.parse("2026-10-13T09:50:00Z");
    public static final Instant DETECTED = Instant.parse("2026-10-13T10:05:00Z");

    public static final String DETAIL_FILE = "src/main/java/com/example/catalog/ProductDetailService.java";
    /** Line of {@code reviews.findByProductId(productId)} in the sample repository. */
    public static final int DETAIL_LINE = 24;
    public static final String SLOW_SQL = "SELECT * FROM reviews WHERE product_id = ?";

    /** Popular products and their review counts; everything else has a few dozen reviews. */
    static final Map<String, Integer> HOT_PRODUCTS = Map.of("P-0001", 41_230, "P-0007", 25_310, "P-0002", 18_877);

    private final List<LogEntry> logs;
    private final Map<String, List<Series>> metrics;
    private final List<Span> spans;

    public CatalogLatencyWorld() {
        Random random = new Random(SEED);
        this.logs = Collections.unmodifiableList(generateLogs(random));
        this.metrics = generateMetrics(random);
        this.spans = Collections.unmodifiableList(generateSpans(random));
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
        return List.of(
                new Deploy("2.3.9", Instant.parse("2026-10-11T15:00:00Z"), "4c1a9e2",
                        "Price-range filter on search (#329)",
                        List.of("src/main/java/com/example/catalog/SearchService.java")),
                new Deploy("2.4.0", DEPLOY_240, "b71d4e0",
                        "Product page shows review summary and most helpful reviews (#341)",
                        List.of(DETAIL_FILE, "src/main/java/com/example/catalog/ProductDetail.java")));
    }

    @Override
    public List<Flag> flags() {
        return List.of(new Flag("catalog.search-ranking-v3", true, 100, FLAG_RAMP,
                "Learning-to-rank model v3 for search results (ramped 50% -> 100%)",
                List.of("src/main/java/com/example/catalog/SearchRanker.java")));
    }

    private static List<String> hotProducts() {
        return HOT_PRODUCTS.keySet().stream().sorted().toList();
    }

    private static List<LogEntry> generateLogs(Random random) {
        List<LogEntry> out = new ArrayList<>();
        List<String> hot = hotProducts();
        for (Instant t = LOG_START; t.isBefore(DETECTED); t = t.plusSeconds(1)) {
            for (int i = 0; i < 2; i++) {
                out.add(new LogEntry(t.plusMillis(random.nextInt(900)), "INFO", "c.e.c.AccessLog",
                        "GET /products/P-" + (1000 + random.nextInt(8000)) + " 200 in " + (40 + random.nextInt(100)) + "ms",
                        null));
            }
            out.add(new LogEntry(t.plusMillis(random.nextInt(900)), "INFO", "c.e.c.AccessLog",
                    "GET /search 200 in " + (50 + random.nextInt(40)) + "ms", null));
            long second = Duration.between(LOG_START, t).toSeconds();
            if (!t.isBefore(ONSET) && second % 3 == 0) {
                String product = hot.get((int) (second / 3 % hot.size()));
                int rows = HOT_PRODUCTS.get(product);
                long queryMs = 2_100 + random.nextInt(200);
                out.add(new LogEntry(t.plusMillis(100), "WARN", "c.e.c.ReviewRepository",
                        "slow query took " + queryMs + "ms rows=" + rows + " sql=" + SLOW_SQL, null));
                out.add(new LogEntry(t.plusMillis(120 + queryMs), "WARN", "c.e.c.SlowRequestFilter",
                        "slow request GET /products/{id} productId=" + product + " took " + (queryMs + 180) + "ms",
                        null));
            }
            if (!t.isBefore(ONSET) && t.isBefore(CACHE_RECOVERED) && second % 60 == 0) {
                long minutes = Duration.between(ONSET, t).toMinutes();
                out.add(new LogEntry(t, "WARN", "c.e.c.ProductCache",
                        "product cache miss ratio " + String.format(java.util.Locale.ROOT, "%.2f",
                                Math.max(0.06, 0.45 - minutes * 0.02)) + " over the last 60s (warming)", null));
            }
        }
        for (int i = 0; i < 4; i++) {
            out.add(new LogEntry(FLAG_RAMP.plusSeconds(15L * 60 + i * 40L), "WARN", "c.e.c.SearchRanker",
                    "ranking v3 fell back to v2 for one query (model cache warming)", null));
        }
        out.add(new LogEntry(DEPLOY_240, "INFO", "c.e.c.Application",
                "starting catalog-api version=2.4.0 commit=b71d4e0 (rolling restart)", null));
        out.add(new LogEntry(DEPLOY_240.plusSeconds(2), "INFO", "c.e.c.ProductCache",
                "product cache cleared on startup (0 entries)", null));
        out.sort((a, b) -> a.timestamp().compareTo(b.timestamp()));
        return out;
    }

    private static Map<String, List<Series>> generateMetrics(Random random) {
        Map<String, List<Series>> out = new LinkedHashMap<>();
        List<MetricPoint> product = new ArrayList<>();
        List<MetricPoint> search = new ArrayList<>();
        List<MetricPoint> cache = new ArrayList<>();
        for (Instant t = WINDOW_START; !t.isAfter(DETECTED); t = t.plusSeconds(60)) {
            boolean slow = !t.isBefore(ONSET);
            product.add(new MetricPoint(t, slow ? 2_300 + random.nextInt(200) : 110 + random.nextInt(20)));
            search.add(new MetricPoint(t, 70 + random.nextInt(20)));
            double ratio;
            if (t.isBefore(ONSET) || !t.isBefore(CACHE_RECOVERED)) {
                ratio = 0.93 + random.nextInt(3) / 100.0;
            } else {
                double progress = (double) Duration.between(ONSET, t).toMinutes()
                        / Duration.between(ONSET, CACHE_RECOVERED).toMinutes();
                ratio = 0.55 + 0.38 * progress;
            }
            cache.add(new MetricPoint(t, Math.round(ratio * 100) / 100.0));
        }
        out.put("http_latency_p95_ms", List.of(
                new Series(Map.of("route", "/products/{id}"), product),
                new Series(Map.of("route", "/search"), search)));
        out.put("cache_hit_ratio", List.of(new Series(Map.of("cache", "product"), cache)));
        return Collections.unmodifiableMap(out);
    }

    private static List<Span> generateSpans(Random random) {
        List<Span> out = new ArrayList<>();
        List<String> hot = hotProducts();
        int n = 0;
        for (Instant t = LOG_START; t.isBefore(DETECTED); t = t.plusSeconds(10)) {
            out.add(new Span(TelemetryWorld.traceId(random), "GET /products/{id}", t.plusMillis(random.nextInt(500)),
                    60 + random.nextInt(80), "OK", Map.of("http.route", "/products/{id}",
                    "product.id", "P-" + (1000 + random.nextInt(8000)), "cache.product_hit", "true")));
            if (!t.isBefore(ONSET)) {
                String product = hot.get(n++ % hot.size());
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("http.route", "/products/{id}");
                attributes.put("product.id", product);
                attributes.put("cache.product_hit", "true");
                attributes.put("db.statement", SLOW_SQL);
                attributes.put("db.rows_returned", String.valueOf(HOT_PRODUCTS.get(product)));
                attributes.put("code.function", "ProductDetailService.detail");
                attributes.put("code.filepath", DETAIL_FILE);
                attributes.put("code.lineno", String.valueOf(DETAIL_LINE));
                out.add(new Span(TelemetryWorld.traceId(random), "GET /products/{id}", t.plusMillis(random.nextInt(500)),
                        2_250 + random.nextInt(250), "OK", Collections.unmodifiableMap(attributes)));
            }
        }
        return out;
    }
}
