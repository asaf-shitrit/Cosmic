package bot.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * The server-wide cap on real bot clients connected at once, shared by every kind of in-server bot:
 * FM residents, ambient locals and summoned companions. A bot holds a {@link Lease} from before its
 * first login packet until its connection is closed, so a bot still logging in or still tearing down
 * counts - the same rule {@code BotPartySupervisor} uses for its own threads.
 *
 * <p><b>Why one budget.</b> Every one of these is a full client over loopback, a {@code Character} in
 * player storage and a thread; the user wants a small, fixed ceiling on that no matter which feature
 * asked for the bot. Separate per-feature caps add up past the ceiling.
 *
 * <p><b>Companion reserve.</b> Background kinds (residents, ambient locals) may only use
 * {@code cap - companionReserve} slots, so a player can still summon party members while the FM is
 * busy. Companions may use every slot: they are the one kind a human explicitly asked for.
 *
 * <p><b>External usage.</b> Until {@code SummonedBot} acquires leases itself (it is owned by other work
 * and not modified here), its live bots are counted through {@link #setExternalUsage}, so the cap is
 * honoured before the merge too.
 */
public final class BotBudget {
    private static final Logger log = LoggerFactory.getLogger(BotBudget.class);

    public enum Kind { RESIDENT, AMBIENT, COMPANION }

    private static volatile BotBudget shared;

    private final int cap;
    private final int companionReserve;
    private final Map<Long, Lease> leases = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private volatile IntSupplier externalUsage = () -> 0;

    public BotBudget(int cap, int companionReserve) {
        if (cap < 0 || companionReserve < 0) {
            throw new IllegalArgumentException("cap and reserve must be non-negative");
        }
        this.cap = cap;
        this.companionReserve = Math.min(companionReserve, cap);
    }

    /** The process-wide budget, created on first use with the given limits (later limits are ignored). */
    public static BotBudget shared(int cap, int companionReserve) {
        BotBudget b = shared;
        if (b == null) {
            synchronized (BotBudget.class) {
                if (shared == null) {
                    shared = new BotBudget(cap, companionReserve);
                }
                b = shared;
            }
        }
        return b;
    }

    /** Counts bots this budget doesn't lease out itself (see class javadoc). Must be cheap and thread-safe. */
    public void setExternalUsage(IntSupplier supplier) {
        this.externalUsage = supplier == null ? () -> 0 : supplier;
    }

    /**
     * @return a lease, or empty if taking one would exceed the cap (or eat into the companion reserve
     *         for a background kind)
     */
    public Optional<Lease> tryAcquire(Kind kind, String botName) {
        Lease lease;
        int total;
        synchronized (this) {
            total = usedLocked();
            int limit = kind == Kind.COMPANION ? cap : cap - companionReserve;
            if (total >= limit) {
                log.debug("Bot budget denied {} {}: {}/{} in use (limit for this kind {})", kind, botName, total, cap, limit);
                return Optional.empty();
            }
            lease = new Lease(this, nextId.getAndIncrement(), kind, botName);
            leases.put(lease.id, lease);
            total++;
        }
        log.info("Bot budget: {} {} acquired a slot ({}/{} connected bots, {} external)", kind, botName, total, cap,
                externalUsage.getAsInt());
        return Optional.of(lease);
    }

    private void release(Lease lease) {
        int total;
        synchronized (this) {
            if (leases.remove(lease.id) == null) {
                return;
            }
            total = usedLocked();
        }
        log.info("Bot budget: {} {} released its slot ({}/{} connected bots)", lease.kind, lease.botName, total, cap);
    }

    private int usedLocked() {
        return leases.size() + Math.max(0, externalUsage.getAsInt());
    }

    public synchronized int inUse() {
        return usedLocked();
    }

    public synchronized int inUse(Kind kind) {
        return (int) leases.values().stream().filter(l -> l.kind == kind).count();
    }

    public int cap() {
        return cap;
    }

    public synchronized List<String> holders() {
        List<String> names = new ArrayList<>();
        for (Lease l : leases.values()) {
            names.add(l.kind + ":" + l.botName);
        }
        return names;
    }

    /** One connected bot's slot. Closing it twice is harmless. */
    public static final class Lease implements AutoCloseable {
        private final BotBudget budget;
        private final long id;
        private final Kind kind;
        private final String botName;

        private Lease(BotBudget budget, long id, Kind kind, String botName) {
            this.budget = budget;
            this.id = id;
            this.kind = kind;
            this.botName = botName;
        }

        public Kind kind() {
            return kind;
        }

        public String botName() {
            return botName;
        }

        @Override
        public void close() {
            budget.release(this);
        }
    }
}
