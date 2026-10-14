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

    private static final Pattern FRAME = Pattern.compile("at com\\.example\\.orders\\.(\\w+)\\.(\\w+)\\((\\w+\\.java):(\\d+)\\)");

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
