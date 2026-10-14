package dev.incidentforge.llm;

import java.util.Map;
import java.util.Optional;

/**
 * The fixture-specific half of the offline stand-in model: for each seeded incident, how the
 * scripted code-path investigator locates the suspect line and which edit plus reproduction test
 * the scripted fixer proposes. Everything else the offline model does (classification, timeline,
 * change correlation) is generic and driven by the evidence. A real model replaces all of this.
 */
final class OfflineScenarios {

    /** How the scripted code-path investigator finds the code. */
    enum Locate {
        /** code_search for the phrase in the hottest warning, then read around the match. */
        LOG_PHRASE,
        /** read around {@code code.filepath}/{@code code.lineno} from the trace evidence. */
        TRACE_HINT
    }

    record Scenario(String service, Locate locate, String suspectMarker, String suspectSymbol, String hypothesis,
                    String codeClaim, String search, String replace, String reproPath, String reproClass,
                    String reproTest, String summary, String risk) {
    }

    private OfflineScenarios() {
    }

    static Optional<Scenario> forService(String service) {
        return Optional.ofNullable(SCENARIOS.get(service));
    }

    // Text blocks strip common indentation; re-indent to the indentation used in the sample sources.
    static final String ORDERS_CATCH_BLOCK = """
            } catch (MalformedEventException e) {
                LOG.log(Level.WARNING, "malformed event partition={0} offset={1}; rewinding for retry",
                        record.partition(), record.offset());
                source.seek(record.offset());
                return applied;
            }""".indent(12).stripTrailing();

    static final String ORDERS_FIXED_CATCH_BLOCK = """
            } catch (MalformedEventException e) {
                // A malformed record fails identically on every redelivery (poison event):
                // dead-letter it and commit past it instead of rewinding the partition.
                LOG.log(Level.WARNING, "malformed event partition={0} offset={1}; dead-lettering",
                        record.partition(), record.offset());
                deadLetters.publish(record, "malformed: " + e.getMessage());
                source.commit(record.offset() + 1);
            }""".indent(12).stripTrailing();

    static final String ORDERS_REPRO_TEST = """
            package com.example.orders;

            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            import java.util.ArrayList;
            import java.util.List;
            import org.junit.jupiter.api.Test;

            /** Reproduces the incident: one malformed record must not stall the partition. */
            class PoisonEventReproTest {

                @Test
                void malformedEventIsDeadLetteredAndDoesNotBlockThePartition() {
                    InMemoryRecordSource source = new InMemoryRecordSource(List.of(
                            "orderId=A-1;customerId=C-1;amount=10.00;currency=EUR",
                            "orderId=A-2;customerId=C-2;amount=12,50;currency=EUR",
                            "orderId=A-3;customerId=C-3;amount=7.25;currency=EUR"));
                    OrderLedger ledger = new OrderLedger();
                    List<String> deadLettered = new ArrayList<>();
                    OrderEventProcessor processor = new OrderEventProcessor(source, new OrderEventParser(), ledger,
                            (record, reason) -> deadLettered.add(record.offset() + ":" + reason));

                    // Bounded: with a rewind-and-retry loop the partition never gets past offset 1.
                    for (int i = 0; i < 5; i++) {
                        processor.pollOnce();
                    }

                    assertEquals(3, source.committedOffset());
                    assertEquals(2, ledger.appliedCount());
                    assertEquals(1, deadLettered.size());
                    assertTrue(deadLettered.get(0).startsWith("1:malformed"));
                }
            }
            """;

    static final String CATALOG_LOAD_ALL = """
            // 2.4.0: review summary and the most helpful reviews on the product page
            List<Review> all = reviews.findByProductId(productId);
            double average = all.stream().mapToInt(Review::stars).average().orElse(0.0);
            List<Review> top = all.stream()
                    .sorted(Comparator.comparingInt(Review::helpfulVotes).reversed())
                    .limit(TOP_REVIEWS)
                    .toList();
            return new ProductDetail(product, all.size(), average, top);""".indent(8).stripTrailing();

    static final String CATALOG_PROJECTION = """
            // Aggregate in the database and fetch only the rows the page shows: loading every review
            // made the cost of one page view grow with the product's review count.
            ReviewStats stats = reviews.statsFor(productId);
            List<Review> top = reviews.topByHelpfulness(productId, TOP_REVIEWS);
            return new ProductDetail(product, stats.count(), stats.averageStars(), top);""".indent(8).stripTrailing();

    static final String CATALOG_REPRO_TEST = """
            package com.example.catalog;

            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            import org.junit.jupiter.api.Test;

            /**
             * Reproduces the incident without timing: rows materialized per page view must not grow with
             * the product's review count, and the summary must stay correct.
             */
            class ProductDetailRowsReadReproTest {

                @Test
                void pageViewReadsAConstantNumberOfReviewRows() {
                    FakeCatalog catalog = new FakeCatalog();
                    catalog.addProduct(new Product("P-0001", "Kettle", 4999));
                    catalog.addReviews("P-0001", 10_000);

                    ProductDetail detail = new ProductDetailService(catalog, catalog).detail("P-0001");

                    assertEquals(10_000, detail.reviewCount());
                    assertEquals(catalog.expectedAverage("P-0001"), detail.averageStars(), 1e-9);
                    assertEquals(3, detail.topReviews().size());
                    assertEquals(9_999, detail.topReviews().get(0).helpfulVotes());
                    assertTrue(catalog.rowsRead() <= 4, "review rows read for one page view: " + catalog.rowsRead());
                }
            }
            """;

    static final String CHECKOUT_UNBOXING = "        int rateBp = RATES_BP.get(region);";

    static final String CHECKOUT_FALLBACK = """
            Integer rateBp = RATES_BP.get(region);
            if (rateBp == null) {
                // Region not migrated to the new rate table yet: price it exactly as the legacy engine does.
                return legacy.taxMinor(region, amountMinor);
            }""".indent(8).stripTrailing();

    static final String CHECKOUT_REPRO_TEST = """
            package com.example.checkout;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import org.junit.jupiter.api.Test;

            /** Reproduces the incident: with the new tax engine on, a region outside its table must still quote. */
            class TaxEngineFallbackReproTest {

                private final LegacyTaxCalculator legacy = new LegacyTaxCalculator();
                private final CheckoutService flagOn =
                        new CheckoutService((flag, subject) -> true, legacy, new TaxCalculatorV2(legacy));

                @Test
                void regionOutsideTheNewTableIsPricedByTheLegacyEngine() {
                    assertEquals(new Quote(10_000, 810, 10_810), flagOn.quote(new Cart("c-1", "CH", 10_000), "u-1"));
                }

                @Test
                void migratedRegionsKeepTheNewEngineRates() {
                    assertEquals(new Quote(10_000, 2_100, 12_100), flagOn.quote(new Cart("c-2", "NL", 10_000), "u-2"));
                }
            }
            """;

    private static final Map<String, Scenario> SCENARIOS = Map.of(
            "orders-consumer", new Scenario("orders-consumer", Locate.LOG_PHRASE, ".seek(", "OrderEventProcessor.pollOnce",
                    "OrderEventProcessor.pollOnce catches MalformedEventException and seeks back to the same offset, so "
                            + "a record that can never parse is redelivered forever (hot loop, no progress).",
                    "The handler logs 'rewinding for retry' and calls source.seek(record.offset()) at line %d, "
                            + "re-queuing the same record.",
                    ORDERS_CATCH_BLOCK, ORDERS_FIXED_CATCH_BLOCK,
                    "src/test/java/com/example/orders/PoisonEventReproTest.java", "PoisonEventReproTest",
                    ORDERS_REPRO_TEST,
                    "Treat MalformedEventException as a poison record: dead-letter it with the reason and commit past "
                            + "it instead of seeking back to the same offset.",
                    "Malformed events are no longer retried; they are preserved in the dead-letter sink for replay "
                            + "after the data is corrected."),
            "catalog-api", new Scenario("catalog-api", Locate.TRACE_HINT, "findByProductId(", "ProductDetailService.detail",
                    "ProductDetailService.detail loads every review row (SELECT * FROM reviews WHERE product_id = ?) and "
                            + "aggregates in Java, so one page view costs O(reviews): products with tens of thousands of "
                            + "reviews take seconds. The slow spans are product-cache hits, so a cache is not on the slow "
                            + "path; warming or adding one would hide the cold path, not remove it.",
                    "The slow spans run ProductDetailService.detail, which calls findByProductId at line %d and returns "
                            + "tens of thousands of rows per request.",
                    CATALOG_LOAD_ALL, CATALOG_PROJECTION,
                    "src/test/java/com/example/catalog/ProductDetailRowsReadReproTest.java",
                    "ProductDetailRowsReadReproTest", CATALOG_REPRO_TEST,
                    "Use the existing aggregate projection (statsFor) and the indexed top-N query (topByHelpfulness) on "
                            + "the product page instead of loading every review row.",
                    "Query shape only; page output is unchanged (the reproduction test checks count, average and top "
                            + "reviews). No cache added: the cold path itself is fixed."),
            "checkout-api", new Scenario("checkout-api", Locate.TRACE_HINT, "RATES_BP.get(region)", "TaxCalculatorV2.taxMinor",
                    "TaxCalculatorV2.taxMinor unboxes RATES_BP.get(region) without the legacy fallback its contract "
                            + "requires, so every region missing from the new table (CH here) throws a NullPointerException "
                            + "for customers in the checkout.new-tax-engine cohort.",
                    "Failing quotes stop at TaxCalculatorV2.taxMinor line %d, where a missing rate is unboxed.",
                    CHECKOUT_UNBOXING, CHECKOUT_FALLBACK,
                    "src/test/java/com/example/checkout/TaxEngineFallbackReproTest.java", "TaxEngineFallbackReproTest",
                    CHECKOUT_REPRO_TEST,
                    "Restore the legacy-engine fallback in TaxCalculatorV2 for regions that are not in the new rate table.",
                    "Unmigrated regions are priced exactly as before the rollout. Disabling the flag (the separate "
                            + "mitigation) is still the immediate step; this fix makes re-enabling it safe."));
}
