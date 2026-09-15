package bot.residents.market;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Chooses what a resident lists this wake. Deterministic for a given random source, so tests pin it.
 *
 * <ol>
 *   <li>Whatever the resident already carries from its catalog goes up first - unsold stock it took
 *       back to reprice, and items it bought from players - so bought goods actually recirculate.</li>
 *   <li>The remaining slots are filled by weighted sampling without replacement, weight = multiplier²:
 *       items that have been selling (multiplier above 1) come back more often, dead stock less.</li>
 * </ol>
 */
public final class StockSelector {
    public record Pick(CatalogItem item, int bundles, int pricePerBundle, int carriedBundles) {
        public int units() {
            return bundles * item.perBundle();
        }
    }

    private StockSelector() {
    }

    /**
     * @param carriedUnits item id to units already in the resident's inventory
     */
    public static List<Pick> select(List<CatalogItem> catalog, MarketPricing.State state, int maxListings,
                                    Map<Integer, Integer> carriedUnits, RandomGenerator rng) {
        List<Pick> picks = new ArrayList<>();
        Map<Integer, CatalogItem> byId = new LinkedHashMap<>();
        catalog.stream().sorted(Comparator.comparingInt(CatalogItem::itemId)).forEach(c -> byId.put(c.itemId(), c));

        List<Integer> carriedIds = new ArrayList<>(carriedUnits.keySet());
        carriedIds.sort(Integer::compare);
        for (int id : carriedIds) {
            CatalogItem item = byId.get(id);
            int units = carriedUnits.get(id);
            if (item == null || picks.size() >= maxListings || units < item.perBundle()) {
                continue;
            }
            int bundles = Math.min(item.maxBundles(), units / item.perBundle());
            picks.add(new Pick(item, bundles, MarketPricing.bundlePrice(state, item, rng), bundles));
            byId.remove(id);
        }

        List<CatalogItem> pool = new ArrayList<>(byId.values());
        while (picks.size() < maxListings && !pool.isEmpty()) {
            double total = 0;
            for (CatalogItem c : pool) {
                total += weight(state, c);
            }
            double roll = rng.nextDouble() * total;
            int chosen = pool.size() - 1;
            for (int i = 0; i < pool.size(); i++) {
                roll -= weight(state, pool.get(i));
                if (roll <= 0) {
                    chosen = i;
                    break;
                }
            }
            CatalogItem item = pool.remove(chosen);
            int min = Math.max(1, (item.maxBundles() + 1) / 2);
            int bundles = min + rng.nextInt(item.maxBundles() - min + 1);
            picks.add(new Pick(item, bundles, MarketPricing.bundlePrice(state, item, rng), 0));
        }
        return picks;
    }

    /** Extra catalog items (not already picked) to offer the LLM as alternatives, in a stable random order. */
    public static List<CatalogItem> alternatives(List<CatalogItem> catalog, List<Pick> picks, int count, RandomGenerator rng) {
        List<CatalogItem> pool = new ArrayList<>(catalog);
        pool.sort(Comparator.comparingInt(CatalogItem::itemId));
        pool.removeIf(c -> picks.stream().anyMatch(p -> p.item().itemId() == c.itemId()));
        List<CatalogItem> out = new ArrayList<>();
        while (out.size() < count && !pool.isEmpty()) {
            out.add(pool.remove(rng.nextInt(pool.size())));
        }
        return out;
    }

    private static double weight(MarketPricing.State state, CatalogItem item) {
        double m = state.multiplier(item.itemId());
        return m * m;
    }
}
