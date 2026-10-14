package dev.incidentforge;

import static dev.incidentforge.support.TestSupport.awaitRun;
import static dev.incidentforge.support.TestSupport.evidence;
import static dev.incidentforge.support.TestSupport.submit;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incidentforge.telemetry.CatalogLatencyWorld;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Second fixture: a slow read path with no exception anywhere. The evidence has to connect a
 * route-specific latency jump to a query shape in code, while a recovering cache hit ratio and
 * cache-miss warnings invite the wrong fix.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"offline", "test"})
@AutoConfigureObservability
class SlowReadPathIncidentTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void routeLatencyIsTracedToALoadEverythingQueryAndFixedWithoutACache() {
        JsonNode run = awaitRun(rest, submit(rest, "catalog-slow-product-page.json"), "AWAITING_APPROVAL");

        // Evidence: one route regressed, a sibling route did not, and the cache recovered while latency did not.
        JsonNode latency = evidence(run, "METRIC", "http_latency_p95_ms route=/products/{id} rose from baseline");
        assertThat(latency.path("summary").asText()).contains("1 flat series");
        JsonNode cache = evidence(run, "METRIC", "cache_hit_ratio");
        assertThat(cache.path("summary").asText()).contains("no sustained anomaly", "(since recovered)");
        JsonNode trace = evidence(run, "TRACE", "GET /products/{id}");
        assertThat(trace.path("summary").asText()).contains("cache.product_hit=true",
                "db.statement=" + CatalogLatencyWorld.SLOW_SQL, "db.rows_returned=18877..41230",
                "code.filepath=" + CatalogLatencyWorld.DETAIL_FILE);
        evidence(run, "LOG_SIGNATURE", "slow query took");
        assertThat(run.path("evidence").toString()).doesNotContain("PROMPT_INJECTION_SUSPECTED", "REDACTED");

        // RCA: the suspect is the findByProductId call, verified against the repository.
        assertThat(run.at("/classification/category").asText()).isEqualTo("APPLICATION_CODE");
        assertThat(run.at("/rootCause/suspectLocation/path").asText()).isEqualTo(CatalogLatencyWorld.DETAIL_FILE);
        assertThat(run.at("/rootCause/suspectLocation/line").asInt()).isEqualTo(CatalogLatencyWorld.DETAIL_LINE);
        assertThat(run.at("/rootCause/suspectLocation/verified").asBoolean()).isTrue();
        assertThat(run.at("/rootCause/summary").asText()).contains("loads every review row", "cache is not on the slow path");
        assertThat(run.at("/rootCause/contributingChange").asText()).startsWith("deploy 2.4.0");
        assertThat(run.at("/rootCause/rejectedClaims").size()).isZero();
        // The cache is explicitly ruled out, citing the cache metric; the search flag is ruled out too.
        assertThat(claimsCiting(run, cache.path("evidenceId").asText()))
                .anySatisfy(s -> assertThat(s).contains("did not stay anomalous"));
        JsonNode searchFlag = evidence(run, "FEATURE_FLAG", "catalog.search-ranking-v3");
        assertThat(claimsCiting(run, searchFlag.path("evidenceId").asText()))
                .anySatisfy(s -> assertThat(s).startsWith("Ruled out"));

        // Mitigation is a separate recommendation; the permanent fix changes the query shape, not caching.
        assertThat(run.at("/mitigation/action").asText()).isEqualTo("ROLLBACK_DEPLOY");
        assertThat(run.at("/mitigation/target").asText()).isEqualTo("2.4.0");
        assertThat(run.at("/mitigation/executed").asBoolean()).isFalse();
        String diff = run.at("/patch/unifiedDiff").asText();
        assertThat(diff).contains("-        List<Review> all = reviews.findByProductId(productId);",
                "+        ReviewStats stats = reviews.statsFor(productId);",
                "+        List<Review> top = reviews.topByHelpfulness(productId, TOP_REVIEWS);",
                "assertTrue(catalog.rowsRead() <= 4");
        assertThat(diff.lines().filter(l -> l.startsWith("+")).toList())
                .noneMatch(l -> l.toLowerCase().contains("cache.") || l.contains("Cache<"));

        // Validated by a deterministic rows-read test: fails on the shipped code, passes with the fix.
        assertThat(run.at("/patch/patch/reproductionTestClass").asText()).isEqualTo("ProductDetailRowsReadReproTest");
        assertThat(run.at("/validation/verdict").asText()).isEqualTo("REPRODUCED_THEN_FIXED");
        assertThat(run.at("/validation/beforeFix/outputTail").asText()).contains("rows read for one page view");
        assertThat(run.path("toolCalls").findValuesAsText("decision")).containsOnly("ALLOWED");
    }

    static List<String> claimsCiting(JsonNode run, String evidenceId) {
        List<String> statements = new ArrayList<>();
        for (JsonNode claim : run.at("/rootCause/claims")) {
            for (JsonNode id : claim.path("evidenceIds")) {
                if (id.asText().equals(evidenceId)) {
                    statements.add(claim.path("statement").asText());
                }
            }
        }
        return statements;
    }
}
