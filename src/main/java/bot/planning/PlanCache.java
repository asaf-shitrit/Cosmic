package bot.planning;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * LLM plans, reused across wakes and across bots with the same situation.
 *
 * <p>The key is what the plan actually depends on - role, job line, level band, map, and the persona's
 * traits and focus - and deliberately not the bot's name, so two residents with the same speciality
 * and temperament share one paid answer. Volatile inputs (this wake's sales, today's prices) are left
 * out of the key: a cached shop plan is stored in relative form and re-applied to current rule prices
 * by {@link ObjectiveCodec#adapt}, so it stays responsive to the market while the key stays stable.
 *
 * <p>Bounded (oldest entry evicted) and expiring by TTL against an injectable clock.
 */
public final class PlanCache {
    /** A validated plan plus, for shop plans, each listing's price as a factor of the rule price it was planned against. */
    public record Entry(Objective objective, Map<Integer, Double> priceFactors, long storedAtMs) {}

    private final Clock clock;
    private final long ttlMs;
    private final int maxEntries;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long hits;
    private long misses;

    public PlanCache(Clock clock, long ttlMs, int maxEntries) {
        this.clock = clock;
        this.ttlMs = ttlMs;
        this.maxEntries = maxEntries;
    }

    public static String key(PlanningContext ctx) {
        PlanningContext.Persona p = ctx.persona();
        String traits = p == null ? "" : String.join("+", p.traits().stream().sorted().toList());
        String focus = p == null ? "" : p.focus();
        if (ctx.role() == PlanningContext.Role.RESIDENT) {
            // A shop plan depends on what is sold (the focus names the speciality and its level band)
            // and who sells it, not on the shopkeeper's own level, job or room.
            return ctx.role() + "|" + focus + "|" + traits;
        }
        return ctx.role() + "|" + ctx.jobLine() + "|L" + ctx.levelBand() + "|m" + ctx.mapId() + "|" + focus + "|" + traits;
    }

    /**
     * Looks {@code key} up and re-validates the entry with {@code adapt}. Counts a hit only when an
     * unexpired entry exists <em>and</em> still fits; an entry that no longer fits is dropped.
     */
    public synchronized Optional<Objective> lookup(String key, Function<Entry, Optional<Objective>> adapt) {
        Entry e = entries.get(key);
        if (e != null && clock.millis() - e.storedAtMs() <= ttlMs) {
            Optional<Objective> adapted = adapt.apply(e);
            if (adapted.isPresent()) {
                hits++;
                return adapted;
            }
        }
        if (e != null) {
            entries.remove(key);
        }
        misses++;
        return Optional.empty();
    }

    public synchronized void put(String key, Objective objective, Map<Integer, Double> priceFactors) {
        entries.remove(key);
        entries.put(key, new Entry(objective, Map.copyOf(priceFactors), clock.millis()));
        Iterator<String> it = entries.keySet().iterator();
        while (entries.size() > maxEntries && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    public synchronized long hits() {
        return hits;
    }

    public synchronized long misses() {
        return misses;
    }

    public synchronized List<String> keys() {
        return List.copyOf(entries.keySet());
    }
}
