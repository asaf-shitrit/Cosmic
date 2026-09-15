package bot.residents.market;

import java.util.HashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Prices that drift with supply and demand, per resident and per item, across wakes.
 *
 * <p>Each item carries a multiplier on its {@link BasePrices base price}. At every wake the resident
 * compares what it listed last time with what sold ({@link #observe}):
 * <ul>
 *   <li>sold at least {@link #SELL_OUT} of it: demand outran supply, raise by {@link #STEP_UP}</li>
 *   <li>sold at least {@link #SOME_SALES}: nudge up by {@link #NUDGE_UP}</li>
 *   <li>sold at most {@link #NO_SALES}: too much supply at this price, lower by {@link #STEP_DOWN}</li>
 *   <li>otherwise drift {@link #REVERT} of the way back toward the base price</li>
 * </ul>
 * Other shops' listings are supply too: a cheaper competing listing pulls the multiplier down to just
 * under it, one {@link #STEP_DOWN} at a time at most. The multiplier stays within
 * {@link #MIN_MULTIPLIER}..{@link #MAX_MULTIPLIER}, so no feedback loop runs away.
 *
 * <p>Buying from players uses the same state: a resident pays at most {@link #BUY_RATIO} of its own
 * current selling price, which keeps a margin on everything it resells.
 */
public final class MarketPricing {
    public static final double MIN_MULTIPLIER = 0.6;
    public static final double MAX_MULTIPLIER = 1.7;
    static final double SELL_OUT = 0.75;
    static final double SOME_SALES = 0.35;
    static final double NO_SALES = 0.10;
    static final double STEP_UP = 0.10;
    static final double NUDGE_UP = 0.03;
    static final double STEP_DOWN = 0.07;
    static final double REVERT = 0.10;
    public static final double BUY_RATIO = 0.7;
    /** Listing prices are jittered by up to this fraction so two wakes don't print identical price lists. */
    static final double JITTER = 0.02;

    /** Per-item market memory for one resident. Serialised into {@code resident_state.market_json}. */
    public static final class State {
        private final Map<Integer, Double> multipliers = new HashMap<>();
        private final Map<Integer, Integer> listedUnits = new HashMap<>();

        public double multiplier(int itemId) {
            return multipliers.getOrDefault(itemId, 1.0);
        }

        public int listedUnits(int itemId) {
            return listedUnits.getOrDefault(itemId, 0);
        }

        public void setMultiplier(int itemId, double m) {
            multipliers.put(itemId, m);
        }

        public void setListedUnits(int itemId, int units) {
            if (units <= 0) {
                listedUnits.remove(itemId);
            } else {
                listedUnits.put(itemId, units);
            }
        }

        public Map<Integer, Double> multipliers() {
            return Map.copyOf(multipliers);
        }

        public Map<Integer, Integer> listed() {
            return Map.copyOf(listedUnits);
        }
    }

    private MarketPricing() {
    }

    /**
     * Updates {@code itemId}'s multiplier from one listing period.
     *
     * @param listedUnits              units the resident put up last time (0 if it didn't list it)
     * @param soldUnits                units of those that sold
     * @param cheapestCompetitorUnit   lowest unit price another shop lists it at, or non-positive if none
     */
    public static double observe(State state, CatalogItem item, int listedUnits, int soldUnits, int cheapestCompetitorUnit) {
        double m = state.multiplier(item.itemId());
        if (listedUnits > 0) {
            double sellThrough = Math.min(1.0, (double) soldUnits / listedUnits);
            if (sellThrough >= SELL_OUT) {
                m *= 1 + STEP_UP;
            } else if (sellThrough >= SOME_SALES) {
                m *= 1 + NUDGE_UP;
            } else if (sellThrough <= NO_SALES) {
                m *= 1 - STEP_DOWN;
            } else {
                m += (1.0 - m) * REVERT;
            }
        }
        if (cheapestCompetitorUnit > 0 && item.basePrice() > 0) {
            double competitor = (double) cheapestCompetitorUnit / item.basePrice();
            if (competitor < m) {
                m = Math.max(m * (1 - STEP_DOWN), competitor * 0.98);
            }
        }
        m = clamp(m);
        state.setMultiplier(item.itemId(), m);
        return m;
    }

    /** What the resident asks for one unit right now (before bundle rounding). */
    public static int unitPrice(State state, CatalogItem item, RandomGenerator rng) {
        double jitter = 1 + (rng.nextDouble() * 2 - 1) * JITTER;
        return BasePrices.roundNice(item.basePrice() * state.multiplier(item.itemId()) * jitter);
    }

    public static int bundlePrice(State state, CatalogItem item, RandomGenerator rng) {
        return BasePrices.roundNice((double) unitPrice(state, item, rng) * item.perBundle());
    }

    /** The most the resident will pay a player per unit: {@link #BUY_RATIO} of its current asking price, no jitter. */
    public static int fairBuyUnitPrice(State state, CatalogItem item) {
        // The epsilon keeps binary rounding (12000 * 0.7 = 8399.999...) from shaving a meso off.
        return (int) Math.floor(item.basePrice() * state.multiplier(item.itemId()) * BUY_RATIO + 1e-6);
    }

    static double clamp(double m) {
        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, m));
    }
}
