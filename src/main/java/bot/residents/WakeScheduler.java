package bot.residents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * When each resident is next due to wake. Pure bookkeeping over an injected time and random source,
 * so the whole schedule is unit-testable; {@code ResidentDirector} owns the clock and actually starts
 * sessions.
 *
 * <ul>
 *   <li><b>Sleep</b>: after a session ends, the next wake is {@code sleepMin..sleepMax} later, jittered.</li>
 *   <li><b>Stagger</b>: at most {@code maxAwake} at once, and at least {@code wakeGapMs} between two
 *       wake starts, so due residents queue instead of logging in together.</li>
 *   <li><b>Catch-up after startup</b>: merchants close when the server stops, so after a start every
 *       resident is scheduled once within {@code startupDelay..catchUpWindow}, spread evenly in random
 *       order - a gentle refill, not a login storm. The gap rule still applies on top.</li>
 *   <li><b>Deferral</b>: a resident that is due but can't start (no human online, no budget slot) simply
 *       stays due. When the blocker clears, the gap rule releases them one at a time.</li>
 * </ul>
 */
public final class WakeScheduler {
    public record Settings(long sleepMinMs, long sleepMaxMs, long sessionMinMs, long sessionMaxMs, int maxAwake, long wakeGapMs,
                           long startupDelayMs, long catchUpWindowMs) {
        public Settings {
            if (sleepMinMs > sleepMaxMs || sessionMinMs > sessionMaxMs || maxAwake < 0 || wakeGapMs < 0
                    || startupDelayMs > catchUpWindowMs) {
                throw new IllegalArgumentException("inconsistent wake settings");
            }
        }
    }

    private final Settings settings;
    private final RandomGenerator rng;
    private final Map<String, Long> nextWakeAt = new HashMap<>();
    private final List<String> awake = new ArrayList<>();
    private long lastWakeStartedAt = Long.MIN_VALUE / 2;

    public WakeScheduler(Settings settings, RandomGenerator rng) {
        this.settings = settings;
        this.rng = rng;
    }

    /** Schedules every resident's first wake inside the catch-up window after {@code now}. */
    public synchronized void startup(List<String> residents, long now) {
        List<String> order = new ArrayList<>(residents);
        Collections.sort(order);
        // Seeded shuffle through the injected RNG, not Collections.shuffle's own randomness.
        for (int i = order.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Collections.swap(order, i, j);
        }
        long span = settings.catchUpWindowMs() - settings.startupDelayMs();
        int n = order.size();
        for (int i = 0; i < n; i++) {
            long offset = n <= 1 ? 0 : span * i / (n - 1);
            nextWakeAt.put(order.get(i), now + settings.startupDelayMs() + offset);
        }
    }

    /**
     * The resident that should start now, if any: the one due longest, provided fewer than
     * {@code maxAwake} are awake and the gap since the last wake start has passed. Doesn't mark it
     * awake - call {@link #started} once its session has actually begun.
     */
    public synchronized Optional<String> nextDue(long now) {
        if (awake.size() >= settings.maxAwake() || now - lastWakeStartedAt < settings.wakeGapMs()) {
            return Optional.empty();
        }
        String best = null;
        long bestAt = Long.MAX_VALUE;
        for (Map.Entry<String, Long> e : nextWakeAt.entrySet()) {
            if (awake.contains(e.getKey()) || e.getValue() > now) {
                continue;
            }
            if (e.getValue() < bestAt || (e.getValue() == bestAt && e.getKey().compareTo(best) < 0)) {
                best = e.getKey();
                bestAt = e.getValue();
            }
        }
        return Optional.ofNullable(best);
    }

    /** @return this session's wall-clock length */
    public synchronized long started(String resident, long now) {
        awake.add(resident);
        lastWakeStartedAt = now;
        return settings.sessionMinMs() + (long) (rng.nextDouble() * (settings.sessionMaxMs() - settings.sessionMinMs()));
    }

    /** @return when the resident will next wake */
    public synchronized long ended(String resident, long now) {
        awake.remove(resident);
        long sleep = settings.sleepMinMs() + (long) (rng.nextDouble() * (settings.sleepMaxMs() - settings.sleepMinMs()));
        long at = now + sleep;
        nextWakeAt.put(resident, at);
        return at;
    }

    /** A wake that couldn't start at all (e.g. login failed): try again after a short sleep, not a full one. */
    public synchronized long retryLater(String resident, long now, long delayMs) {
        awake.remove(resident);
        long at = now + delayMs;
        nextWakeAt.put(resident, at);
        return at;
    }

    public synchronized int awakeCount() {
        return awake.size();
    }

    public synchronized List<String> awake() {
        return List.copyOf(awake);
    }

    public synchronized Long nextWakeAt(String resident) {
        return nextWakeAt.get(resident);
    }

    /** Residents due at {@code now} that are not awake - the queue a blocker is holding back. */
    public synchronized int dueCount(long now) {
        return (int) nextWakeAt.entrySet().stream().filter(e -> e.getValue() <= now && !awake.contains(e.getKey())).count();
    }

    /** Residents due within {@code horizonMs} and not awake, soonest first - candidates for one batched planning call. */
    public synchronized List<String> dueWithin(long now, long horizonMs) {
        return nextWakeAt.entrySet().stream()
                .filter(e -> e.getValue() <= now + horizonMs && !awake.contains(e.getKey()))
                .sorted(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();
    }
}
