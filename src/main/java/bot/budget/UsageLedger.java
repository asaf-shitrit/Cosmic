package bot.budget;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Windowed usage counters with hard caps: LLM calls per day and per hour, chat replies per hour,
 * mesos a resident pays humans per day. Counts persist through a {@link CounterStore} so a server
 * restart doesn't hand out a fresh daily allowance.
 *
 * <p>Consumption is check-and-add under one lock, and happens <em>before</em> the thing it pays for
 * (a failed LLM call still used its slot) - a cap that only counted successes would let a failing
 * dependency be retried without bound.
 */
public final class UsageLedger {
    public enum Window {
        HOUR(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH")),
        DAY(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        private final DateTimeFormatter format;

        Window(DateTimeFormatter format) {
            this.format = format;
        }
    }

    private final CounterStore store;
    private final Clock clock;
    private final ZoneId zone;

    public UsageLedger(CounterStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
        this.zone = clock.getZone();
    }

    /** The window id {@code counter} is being counted in right now, e.g. {@code 2026-09-15T14}. */
    public String windowId(Window window) {
        return ZonedDateTime.now(clock.withZone(zone)).format(window.format);
    }

    /**
     * Adds {@code amount} to the counter if the result stays at or under {@code cap}.
     *
     * @return false (and nothing added) if it would exceed the cap; a cap of 0 always refuses
     */
    public synchronized boolean tryConsume(String counter, Window window, long amount, long cap) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        String id = windowId(window);
        long used = store.get(counter, id);
        if (used + amount > cap) {
            return false;
        }
        store.add(counter, id, amount);
        return true;
    }

    /** Gives back {@code amount} consumed earlier in the current window (e.g. a purchase that failed). */
    public synchronized void refund(String counter, Window window, long amount) {
        String id = windowId(window);
        long used = store.get(counter, id);
        store.add(counter, id, -Math.min(used, amount));
    }

    /** Adds without a cap - for metrics such as tokens and cost. */
    public synchronized void record(String counter, Window window, long amount) {
        store.add(counter, windowId(window), amount);
    }

    public synchronized long used(String counter, Window window) {
        return store.get(counter, windowId(window));
    }

    /** Persistence for counters, keyed by counter name and window id. */
    public interface CounterStore {
        long get(String counter, String windowId);

        void add(String counter, String windowId, long delta);
    }

    /** For tests and for running without a database. */
    public static final class InMemoryStore implements CounterStore {
        private final java.util.Map<String, Long> values = new java.util.HashMap<>();

        @Override
        public synchronized long get(String counter, String windowId) {
            return values.getOrDefault(counter + "|" + windowId, 0L);
        }

        @Override
        public synchronized void add(String counter, String windowId, long delta) {
            values.merge(counter + "|" + windowId, delta, Long::sum);
        }
    }
}
