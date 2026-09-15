package bot.residents;

import bot.budget.BotBudget;
import bot.budget.HumanPresence;
import bot.planning.Decision;
import bot.planning.PlanningServices;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the FM residents: decides who wakes when, plans their wakes, and starts and reaps their threads.
 *
 * <p><b>Cadence.</b> A single ticker thread, every {@link #TICK_MS}. Each tick: reap overdue bots; if
 * scale-to-zero applies (no human online) do nothing else; otherwise plan the residents due soon - all
 * of them in one planner batch, on a separate planning thread so an LLM round trip never stalls the
 * ticker - and start at most one resident whose plan is ready, if {@link WakeScheduler} and the shared
 * {@link BotBudget} both allow it.
 *
 * <p><b>Restarts.</b> Merchants close when the server stops, so {@link WakeScheduler#startup} spreads
 * every resident's first wake over the catch-up window and the wake gap spaces the logins. Stock the
 * closed merchants held is waiting at Fredrick, and the session collects it first.
 *
 * <p><b>Locking.</b> Nothing here holds a lock while touching game state; the scheduler and maps below
 * are internally synchronized or concurrent.
 */
public final class ResidentDirector {
    private static final Logger log = LoggerFactory.getLogger(ResidentDirector.class);

    private static final long TICK_MS = 2000;
    private static final long LOGIN_DEADLINE_MS = 60_000;
    /** On top of a session's own length and its internal hard deadline. */
    private static final long OVERDUE_GRACE_MS = 6 * 60_000L;
    private static final long STOP_GRACE_MS = 5000;
    private static final long PLAN_STALE_MS = 10 * 60_000L;
    private static final long RETRY_AFTER_FAILED_WAKE_MS = 10 * 60_000L;
    private static final long BLOCKED_LOG_EVERY_MS = 60_000;

    private static ResidentDirector instance;

    private record PlannedWake(ShopAssessment assessment, Decision decision, long plannedAt) {}

    private final ResidentConfig config;
    private final PlanningServices planning;
    private final ResidentServices services;
    private final Map<String, ResidentProfile> cast = new LinkedHashMap<>();
    private final WakeScheduler scheduler;
    private final Map<String, ResidentBot> running = new ConcurrentHashMap<>();
    private final Map<String, PlannedWake> plans = new ConcurrentHashMap<>();
    private final AtomicBoolean planningInFlight = new AtomicBoolean();
    private final SplittableRandom rng = new SplittableRandom();
    private final ScheduledExecutorService ticker;
    private final ExecutorService planningThread;
    private final ExecutorService chatThread;
    private volatile boolean shutDown;
    private Boolean lastHumanState;
    private long lastBlockedLogAt;

    private ResidentDirector(ResidentConfig config, List<ResidentProfile> profiles, Path memoryRoot) {
        this.config = config;
        this.planning = PlanningServices.shared(YamlConfig.config.server);
        this.chatThread = Executors.newSingleThreadExecutor(daemon("resident-chat"));
        this.services = new ResidentServices(config, planning.gateway(), planning.ledger(), chatThread, memoryRoot);
        profiles.forEach(p -> {
            cast.put(p.name(), p);
            HumanPresence.registerBot(p.name());
        });
        this.scheduler = new WakeScheduler(config.wakeSettings(), new SplittableRandom());
        this.planningThread = Executors.newSingleThreadExecutor(daemon("resident-planner"));
        this.ticker = Executors.newSingleThreadScheduledExecutor(daemon("resident-director"));
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** Called once the server is online. Does nothing unless {@code USE_RESIDENTS} is on. */
    public static synchronized void startIfEnabled() {
        ResidentConfig config = ResidentConfig.from(YamlConfig.config.server);
        if (!config.enabled() || instance != null) {
            return;
        }
        Path memoryRoot = Path.of(config.memoryDir());
        List<ResidentProfile> profiles = ResidentCast.load(Files.isDirectory(memoryRoot) ? memoryRoot : null, config.castSize());
        if (profiles.isEmpty()) {
            log.warn("Residents enabled but no resident profile could be loaded");
            return;
        }
        instance = new ResidentDirector(config, profiles, Files.isDirectory(memoryRoot) ? memoryRoot : null);
        instance.start();
    }

    public static void shutdownIfRunning() {
        ResidentDirector d;
        synchronized (ResidentDirector.class) {
            d = instance;
            instance = null;
        }
        if (d != null) {
            d.shutdown();
        }
    }

    private void start() {
        long now = System.currentTimeMillis();
        scheduler.startup(new ArrayList<>(cast.keySet()), now);
        ticker.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        log.info("Residents started: {} residents ({}), up to {} awake, first wakes over the next {}s, sleep {}-{}s, sessions {}-{}s, "
                        + "scale-to-zero {}, memory {}", cast.size(), String.join(", ", cast.keySet()), config.maxAwake(),
                config.catchUpWindowMs() / 1000, config.sleepMinMs() / 1000, config.sleepMaxMs() / 1000,
                config.sessionMinMs() / 1000, config.sessionMaxMs() / 1000, config.requireHuman() ? "on" : "off",
                services.memoryRoot() == null ? "absent" : services.memoryRoot());
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            reapOverdue(now);
            boolean human = HumanPresence.humansOnline() > 0;
            if (lastHumanState == null || lastHumanState != human) {
                lastHumanState = human;
                log.info(human ? "A human is online: residents active ({} due, {} awake)"
                                : config.requireHuman() ? "No human online: residents stay asleep ({} due, {} awake); open shops stay open"
                                : "No human online, scale-to-zero is off: residents stay active ({} due, {} awake)",
                        scheduler.dueCount(now), scheduler.awakeCount());
            }
            if (config.requireHuman() && !human) {
                return;
            }
            planAhead(now);
            startDue(now);
        } catch (Throwable t) {
            // A throw would cancel the scheduled task and silently stop every resident.
            log.error("Resident director tick failed", t);
        }
    }

    /** Plans every resident due within the next batch window that has no fresh plan, as one batch. */
    private void planAhead(long now) {
        if (planningInFlight.get()) {
            return;
        }
        long horizon = Math.max(60_000, config.wakeGapMs() * Math.max(1, config.planMaxBatch()));
        List<String> toPlan = new ArrayList<>();
        for (String name : scheduler.dueWithin(now, horizon)) {
            PlannedWake plan = plans.get(name);
            if (!running.containsKey(name) && (plan == null || now - plan.plannedAt() > PLAN_STALE_MS)) {
                toPlan.add(name);
            }
            if (toPlan.size() >= Math.max(1, config.planMaxBatch())) {
                break;
            }
        }
        if (toPlan.isEmpty() || !planningInFlight.compareAndSet(false, true)) {
            return;
        }
        planningThread.execute(() -> {
            try {
                List<ShopAssessment> assessments = new ArrayList<>();
                for (String name : toPlan) {
                    ResidentProfile p = cast.get(name);
                    assessments.add(ShopAssessment.assess(p, config, new ResidentMemory(services.memoryRoot(), name), rng.split(),
                            System.currentTimeMillis()));
                }
                List<Decision> decisions = planning.planner().planBatch(assessments.stream().map(a -> a.context).toList());
                for (int i = 0; i < assessments.size(); i++) {
                    plans.put(toPlan.get(i), new PlannedWake(assessments.get(i), decisions.get(i), System.currentTimeMillis()));
                }
                log.info("Planned {} resident wake(s) in one batch: {} [planner stats {}]", toPlan.size(), toPlan, planning.planner().stats());
            } catch (Throwable t) {
                log.warn("Planning resident wakes {} failed", toPlan, t);
            } finally {
                planningInFlight.set(false);
            }
        });
    }

    private void startDue(long now) {
        Optional<String> due = scheduler.nextDue(now);
        if (due.isEmpty() || shutDown) {
            return;
        }
        String name = due.get();
        PlannedWake plan = plans.get(name);
        if (plan == null) {
            return;         // its plan is still being made
        }
        Optional<BotBudget.Lease> lease = planning.budget().tryAcquire(BotBudget.Kind.RESIDENT, name);
        if (lease.isEmpty()) {
            if (now - lastBlockedLogAt > BLOCKED_LOG_EVERY_MS) {
                lastBlockedLogAt = now;
                log.info("Resident {} is due but the bot budget is full ({}/{}: {})", name, planning.budget().inUse(),
                        planning.budget().cap(), planning.budget().holders());
            }
            return;
        }
        long sessionMs = scheduler.started(name, now);
        plans.remove(name);
        ResidentBot bot = new ResidentBot(cast.get(name), plan.assessment(), plan.decision(), services, lease.get(), sessionMs, this::onExit);
        running.put(name, bot);
        Thread t = new Thread(bot, "resident-" + name);
        t.setDaemon(true);
        try {
            t.start();
        } catch (OutOfMemoryError e) {
            running.remove(name);
            lease.get().close();
            scheduler.retryLater(name, now, RETRY_AFTER_FAILED_WAKE_MS);
            log.warn("Couldn't start a thread for resident {}", name, e);
            return;
        }
        log.info("Resident {} waking for {}s (awake {}/{}, bot budget {}/{} {}; plan {} via {})", name, sessionMs / 1000,
                scheduler.awakeCount(), config.maxAwake(), planning.budget().inUse(), planning.budget().cap(),
                planning.budget().holders(), bot.profile.focus(), plan.decision().path());
    }

    private void onExit(ResidentBot bot) {
        String name = bot.profile.name();
        running.remove(name);
        long now = System.currentTimeMillis();
        long next = bot.inWorld ? scheduler.ended(name, now) : scheduler.retryLater(name, now, RETRY_AFTER_FAILED_WAKE_MS);
        log.info("Resident {} logged out ({}); next wake in {}s", name, bot.outcome, (next - now) / 1000);
    }

    private void reapOverdue(long now) {
        for (ResidentBot bot : running.values()) {
            if (bot.stopRequested) {
                bot.forceClose();
                continue;
            }
            boolean loginStuck = !bot.inWorld && bot.loginStartedAt > 0 && now - bot.loginStartedAt > LOGIN_DEADLINE_MS;
            boolean overdue = now - bot.startedAt > config.sessionMaxMs() + OVERDUE_GRACE_MS;
            if (loginStuck || overdue) {
                log.warn("Resident {} {}; stopping it", bot.profile.name(), loginStuck ? "didn't enter the world within 60s" : "overran its session");
                bot.requestStop();
                bot.forceClose();
            }
        }
    }

    private void shutdown() {
        shutDown = true;
        ticker.shutdownNow();
        planningThread.shutdownNow();
        running.values().forEach(ResidentBot::requestStop);
        long deadline = System.currentTimeMillis() + STOP_GRACE_MS;
        while (!running.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        running.values().forEach(ResidentBot::forceClose);
        chatThread.shutdownNow();
        log.info("Residents stopped");
    }
}
