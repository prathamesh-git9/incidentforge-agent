package dev.incidentforge.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.incidentforge.repo.RepositoryReader;
import dev.incidentforge.support.TestSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The synthetic incident must stay consistent with the sample repository: every application stack
 * frame has to point at a real line that does what the frame says, otherwise the "symptom to code"
 * link the demo shows would be fiction.
 */
class SeededIncidentConsistencyTest {

    private static final Pattern FRAME = Pattern.compile("at com\\.example\\.\\w+\\.(\\w+)\\.(\\w+)\\((\\w+\\.java):(\\d+)\\)");

    @Test
    void stackFramesPointAtRealLinesInTheSampleRepository() {
        RepositoryReader repo = new RepositoryReader(TestSupport.SAMPLE_REPO);
        Matcher m = FRAME.matcher(SyntheticTelemetry.POISON_STACK);
        int frames = 0;
        while (m.find()) {
            frames++;
            String path = "src/main/java/com/example/orders/" + m.group(3);
            int line = Integer.parseInt(m.group(4));
            String text = repo.read(path, line, line).content();
            switch (m.group(2) + ":" + line) {
                case "parseAmount:42" -> assertThat(text).contains("throw new MalformedEventException");
                case "parseAmount:36" -> assertThat(text).contains("new BigDecimal(amount)");
                case "parse:30" -> assertThat(text).contains("parseAmount(");
                case "pollOnce:42" -> assertThat(text).contains("parser.parse(record.value())");
                default -> throw new AssertionError("unexpected frame " + m.group());
            }
        }
        assertThat(frames).isEqualTo(4);
    }

    @Test
    void catalogSlowSpansPointAtTheLoadAllQueryAndOnlyHotProductsAreSlow() {
        RepositoryReader repo = new RepositoryReader(java.nio.file.Path.of("sample-repo", "catalog-api"));
        CatalogLatencyWorld world = new CatalogLatencyWorld();

        assertThat(repo.read(CatalogLatencyWorld.DETAIL_FILE, CatalogLatencyWorld.DETAIL_LINE,
                CatalogLatencyWorld.DETAIL_LINE).content()).contains("reviews.findByProductId(productId)");
        assertThat(world.spans()).filteredOn(s -> s.durationMs() >= 1000).isNotEmpty().allSatisfy(s -> {
            assertThat(s.attributes()).containsEntry("code.lineno", String.valueOf(CatalogLatencyWorld.DETAIL_LINE))
                    .containsEntry("cache.product_hit", "true");
            assertThat(Integer.parseInt(s.attributes().get("db.rows_returned"))).isGreaterThan(10_000);
            assertThat(s.start()).isAfterOrEqualTo(CatalogLatencyWorld.ONSET);
        });
        assertThat(world.spans()).filteredOn(s -> s.durationMs() < 1000)
                .allSatisfy(s -> assertThat(s.attributes()).doesNotContainKey("db.rows_returned"));
        assertThat(new CatalogLatencyWorld().logs()).isEqualTo(world.logs());
    }

    @Test
    void checkoutStackFramesPointAtRealLinesAndOnlyFlaggedSwissQuotesFail() {
        RepositoryReader repo = new RepositoryReader(java.nio.file.Path.of("sample-repo", "checkout-api"));
        Matcher m = FRAME.matcher(CheckoutRolloutWorld.STACK);
        int frames = 0;
        while (m.find()) {
            frames++;
            String path = "src/main/java/com/example/checkout/" + m.group(3);
            int line = Integer.parseInt(m.group(4));
            String text = repo.read(path, line, line).content();
            switch (m.group(2) + ":" + line) {
                case "taxMinor:23" -> assertThat(text).contains("RATES_BP.get(region)");
                case "quote:22" -> assertThat(text).contains("calculator.taxMinor(");
                default -> throw new AssertionError("unexpected frame " + m.group());
            }
        }
        assertThat(frames).isEqualTo(2);

        CheckoutRolloutWorld world = new CheckoutRolloutWorld();
        assertThat(world.logs()).filteredOn(e -> e.level().equals("ERROR")).isNotEmpty().allSatisfy(e -> {
            assertThat(e.message()).contains("region=CH engine=v2");
            assertThat(e.timestamp()).isAfterOrEqualTo(CheckoutRolloutWorld.FLAG_RAMP);
        });
        assertThat(world.logs()).anySatisfy(e -> assertThat(e.message()).contains("region=CH engine=legacy"));
        assertThat(world.deploys()).allSatisfy(d -> assertThat(d.at()).isBefore(CheckoutRolloutWorld.FLAG_RAMP
                .minus(java.time.Duration.ofDays(2))));
    }

    @Test
    void worldIsDeterministicAndShowsTheHotLoopOnlyOnPartitionThree() {
        SyntheticTelemetry a = new SyntheticTelemetry();
        SyntheticTelemetry b = new SyntheticTelemetry();
        assertThat(a.logs()).isEqualTo(b.logs());
        assertThat(a.logs()).anySatisfy(e -> assertThat(e.message())
                .contains("offset=" + (SyntheticTelemetry.POISON_OFFSET - 1)).contains("partition=3"));
        assertThat(a.logs().stream().filter(e -> e.message().contains("rewinding for retry")))
                .allSatisfy(e -> assertThat(e.timestamp()).isAfterOrEqualTo(SyntheticTelemetry.ONSET));
        assertThat(a.logs().stream().anyMatch(e -> e.message().contains("partition=3 offset="
                + (SyntheticTelemetry.POISON_OFFSET + 1)))).isFalse();
    }
}
