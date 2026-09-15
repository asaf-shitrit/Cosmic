package bot.residents.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketPricingTest {
    private static final CatalogItem WHITE_POTION = new CatalogItem(2000002, "White Potion", Speciality.POTIONS, 30, 320, 20, 10);
    private static final CatalogItem SCROLL = new CatalogItem(2040002, "Scroll for Helmet for DEF 10%", Speciality.SCROLLS, 0, 10_000, 1, 3);

    @Test
    void sellingOutRaisesThePriceAcrossWakes() {
        MarketPricing.State state = new MarketPricing.State();
        Random rng = new Random(1);
        int before = MarketPricing.unitPrice(state, WHITE_POTION, rng);
        MarketPricing.observe(state, WHITE_POTION, 200, 200, 0);
        MarketPricing.observe(state, WHITE_POTION, 200, 180, 0);
        int after = MarketPricing.unitPrice(state, WHITE_POTION, rng);
        assertTrue(after > before, before + " -> " + after);
        assertEquals(1.21, state.multiplier(WHITE_POTION.itemId()), 1e-9);
    }

    @Test
    void unsoldStockLowersThePriceAndTheFloorHolds() {
        MarketPricing.State state = new MarketPricing.State();
        for (int wake = 0; wake < 30; wake++) {
            MarketPricing.observe(state, SCROLL, 3, 0, 0);
        }
        assertEquals(MarketPricing.MIN_MULTIPLIER, state.multiplier(SCROLL.itemId()), 1e-9);
        for (int wake = 0; wake < 30; wake++) {
            MarketPricing.observe(state, SCROLL, 3, 3, 0);
        }
        assertEquals(MarketPricing.MAX_MULTIPLIER, state.multiplier(SCROLL.itemId()), 1e-9);
    }

    @Test
    void middlingSalesRevertTowardBaseAndNotListingChangesNothing() {
        MarketPricing.State state = new MarketPricing.State();
        state.setMultiplier(SCROLL.itemId(), 1.5);
        MarketPricing.observe(state, SCROLL, 10, 2, 0);  // 20% sell-through
        assertEquals(1.45, state.multiplier(SCROLL.itemId()), 1e-9);
        MarketPricing.observe(state, SCROLL, 0, 0, 0);
        assertEquals(1.45, state.multiplier(SCROLL.itemId()), 1e-9);
    }

    @Test
    void cheaperCompetingSupplyPullsThePriceDownGradually() {
        MarketPricing.State state = new MarketPricing.State();
        MarketPricing.observe(state, WHITE_POTION, 0, 0, 160);     // competitor at half our base
        assertEquals(0.93, state.multiplier(WHITE_POTION.itemId()), 1e-9, "one step down, not straight to the competitor");
        MarketPricing.observe(state, WHITE_POTION, 0, 0, 400);     // pricier competitor is no pressure
        assertEquals(0.93, state.multiplier(WHITE_POTION.itemId()), 1e-9);
    }

    @Test
    void residentsPayLessThanTheyAsk() {
        MarketPricing.State state = new MarketPricing.State();
        state.setMultiplier(SCROLL.itemId(), 1.2);
        int fair = MarketPricing.fairBuyUnitPrice(state, SCROLL);
        assertEquals(8_400, fair);
        assertTrue(fair < MarketPricing.unitPrice(state, SCROLL, new Random(2)));
    }

    @Test
    void basePricesAnchorOnNpcShopsAndScrollRates() {
        assertEquals(320, BasePrices.unitPrice(Speciality.POTIONS, "White Potion", 0, 320, 160));
        assertEquals(10_000, BasePrices.unitPrice(Speciality.SCROLLS, "Scroll for Helmet for DEF 10%", 0, -1, 1));
        assertEquals(50_000, BasePrices.unitPrice(Speciality.SCROLLS, "Scroll for One-Handed Sword for ATT 60%", 0, -1, 1));
        assertEquals(28_000, BasePrices.unitPrice(Speciality.EQUIPS, "Some Helm", 30, -1, 500));
        assertEquals(1_500, BasePrices.unitPrice(Speciality.ORES, "Mithril Ore", 0, -1, 300));
        assertEquals(96, BasePrices.roundNice(96.4));
        assertEquals(335, BasePrices.roundNice(333));
        assertEquals(1_250, BasePrices.roundNice(1_234));
        assertEquals(28_000, BasePrices.roundNice(27_900));
    }

    @Test
    void stockSelectionIsDeterministicListsCarriedStockFirstAndRespectsLimits() {
        List<CatalogItem> catalog = List.of(
                WHITE_POTION,
                new CatalogItem(2000000, "Red Potion", Speciality.POTIONS, 10, 50, 50, 10),
                new CatalogItem(2000001, "Orange Potion", Speciality.POTIONS, 20, 160, 50, 10),
                new CatalogItem(2000003, "Blue Potion", Speciality.POTIONS, 20, 200, 50, 10),
                new CatalogItem(2000006, "Mana Elixir", Speciality.POTIONS, 40, 620, 20, 5));
        MarketPricing.State state = new MarketPricing.State();

        List<StockSelector.Pick> a = StockSelector.select(catalog, state, 3, Map.of(2000006, 45), new Random(42));
        List<StockSelector.Pick> b = StockSelector.select(catalog, state, 3, Map.of(2000006, 45), new Random(42));
        assertEquals(a, b);
        assertEquals(3, a.size());
        assertEquals(2000006, a.get(0).item().itemId(), "carried stock goes up first");
        assertEquals(2, a.get(0).bundles(), "45 units at 20 per bundle is 2 whole bundles");
        for (StockSelector.Pick p : a) {
            assertTrue(p.bundles() >= 1 && p.bundles() <= p.item().maxBundles());
        }
        assertEquals(3, a.stream().map(p -> p.item().itemId()).distinct().count());
    }

    @Test
    void itemsThatSellComeBackMoreOften() {
        List<CatalogItem> catalog = List.of(WHITE_POTION, new CatalogItem(2000000, "Red Potion", Speciality.POTIONS, 10, 50, 50, 10));
        MarketPricing.State state = new MarketPricing.State();
        state.setMultiplier(WHITE_POTION.itemId(), 1.7);
        state.setMultiplier(2000000, 0.6);
        Random rng = new Random(9);
        int white = 0;
        for (int i = 0; i < 2_000; i++) {
            if (StockSelector.select(catalog, state, 1, Map.of(), rng).get(0).item() == WHITE_POTION) {
                white++;
            }
        }
        assertTrue(white > 1_700, "weight 2.89 vs 0.36 should pick the seller ~89% of the time, got " + white);
    }
}
