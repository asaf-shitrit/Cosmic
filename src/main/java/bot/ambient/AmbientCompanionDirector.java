package bot.ambient;

import bot.budget.HumanPresence;
import bot.party.BotPartySupervisor;
import client.Character;
import config.ServerConfig;
import config.YamlConfig;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends a companion to a player who is training on their own.
 *
 * <p>The pieces already existed - {@link BotPartySupervisor#summon} logs a real client in at the
 * player's own level (see {@code CompanionLoadout.levelFor}), puts it in the player's party, and hands
 * it to the planner that follows and fights. What was missing was the one thing a player notices: that
 * nobody sends anyone unless the player walks to an NPC in Kerning City and asks. This class is that
 * missing half - a player alone on a field for a few seconds gets company that shows up on its own,
 * which is the difference between a feature and something to discover.
 *
 * <p>It is deliberately timid, because an uninvited bot is only charming if it never insists:
 * <ul>
 *   <li>Only on maps people grind on: spawn points present, no event instance running - so not towns,
 *       not the Free Market, and never inside a party quest, where the PQ's own team logic owns the
 *       party.</li>
 *   <li>Only after the player has stayed put for {@code dwell}, so someone crossing a map on the way
 *       somewhere is left alone.</li>
 *   <li>Only when the player has no companions out. Someone who already summoned help is being helped;
 *       topping them up to three would be a crowd, not a companion.</li>
 *   <li>Then not again for {@code cooldown}, so dismissing one is not immediately answered by another.</li>
 *   <li>At most one arrival per tick, because several characters logging in at once is the one thing
 *       that would look like a machine.</li>
 * </ul>
 *
 * <p>Whether a companion is actually sent is not this class's decision to force: {@code summon} refuses
 * for its own reasons (wrong world, no accounts free, an event instance, a player under level 10 with no
 * party), and {@link BotPartySupervisor#summonAmbient} reports back whether anything started.
 *
 * <p>The budget is not consulted here either - {@code summon}'s own capacity check already folds in
 * {@code BotBudget}, and the companion reserve exists so a player's explicit summons are never blocked
 * by this.
 */
public final class AmbientCompanionDirector {
    private static final Logger log = LoggerFactory.getLogger(AmbientCompanionDirector.class);

    static final long TICK_MS = 1_000;
    /** How long a player may be offline or away before their dwell state is forgotten. */
    static final long FORGET_AFTER_MS = 5 * 60_000;

    /**
     * @param enabled  whether players get company at all
     * @param dwellMs  how long a player must stay on one map before a companion is sent
     * @param cooldownMs how long after one companion before the same player can get another
     */
    public record Settings(boolean enabled, long dwellMs, long cooldownMs) {}

    /**
     * What the director knows about one player right now. Separated from the server lookups so the
     * decision below can be tested without a running world.
     *
     * @param dwellMs          how long this player has been on this map
     * @param onField          whether that map is one people grind on
     * @param companionsOut    companions this player already has
     * @param capacity         companions {@code summon} would still start for them
     * @param sinceLastSummonMs time since this director last sent them one, or {@code Long.MAX_VALUE}
     */
    record Snapshot(long dwellMs, boolean onField, int companionsOut, int capacity, long sinceLastSummonMs) {}

    private record Seen(int mapId, long since, long lastSeen) {}

    private final Settings settings;
    private final Map<Integer, Seen> seenByCharId = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastSummonAt = new ConcurrentHashMap<>();
    private ScheduledExecutorService ticker;

    private AmbientCompanionDirector(Settings settings) {
        this.settings = settings;
    }

    /** Starts the director if the server is configured for it. Safe to call more than once. */
    public static synchronized void startIfEnabled() {
        Settings settings = settingsFrom(YamlConfig.config.server);
        if (!settings.enabled() || instance != null) {
            return;
        }
        AmbientCompanionDirector director = new AmbientCompanionDirector(settings);
        director.start();
        instance = director;
    }

    public static synchronized void stop() {
        if (instance != null && instance.ticker != null) {
            instance.ticker.shutdownNow();
            instance = null;
        }
    }

    private static AmbientCompanionDirector instance;

    static Settings settingsFrom(ServerConfig config) {
        return new Settings(config.AMBIENT_COMPANIONS_ENABLED,
                Math.max(1, config.AMBIENT_COMPANIONS_DWELL_SECONDS) * 1000L,
                Math.max(0, config.AMBIENT_COMPANIONS_COOLDOWN_MINUTES) * 60_000L);
    }

    private void start() {
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ambient-companions");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(this::tickQuietly, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        log.info("Ambient companions on: a player alone on a field for {}s gets one, then not again for {}min",
                settings.dwellMs() / 1000, settings.cooldownMs() / 60_000);
    }

    /** A tick that throws must not kill the scheduled task - the director would go quiet for good. */
    private void tickQuietly() {
        try {
            tick();
        } catch (RuntimeException | Error e) {
            log.warn("Ambient companion tick failed", e);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Server server = Server.getInstance();
        if (server.getWorlds() == null) {
            return;
        }
        boolean sentThisTick = false;

        for (World world : server.getWorlds()) {
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (!chr.isLoggedinWorld() || chr.getMap() == null || HumanPresence.isBotName(chr.getName())) {
                    continue;
                }
                if (record(chr, now) && !sentThisTick) {
                    sentThisTick = true;    // one arrival per tick, however many players qualify
                }
            }
        }
        forgetPlayersNotSeenSince(now);
    }

    /**
     * Updates what is known about {@code chr} and sends a companion if it is time.
     *
     * @return whether one was actually started
     */
    private boolean record(Character chr, long now) {
        MapleMap map = chr.getMap();
        Seen previous = seenByCharId.get(chr.getId());
        if (previous == null || previous.mapId() != map.getId()) {
            // First sighting of this player, or they moved: start their dwell clock over.
            seenByCharId.put(chr.getId(), new Seen(map.getId(), now, now));
            return false;
        }
        seenByCharId.put(chr.getId(), new Seen(previous.mapId(), previous.since(), now));

        BotPartySupervisor supervisor = BotPartySupervisor.getInstance();
        Snapshot snapshot = new Snapshot(now - previous.since(), isFieldMap(map),
                supervisor.companionCount(chr), supervisor.summonCapacity(chr),
                now - lastSummonAt.getOrDefault(chr.getId(), Long.MIN_VALUE / 2));
        if (!shouldSummon(snapshot, settings)) {
            return false;
        }

        lastSummonAt.put(chr.getId(), now);
        boolean started = supervisor.summonAmbient(chr);
        if (started) {
            log.info("Sending a companion to {} (level {}, map {} {}) after {}s alone",
                    chr.getName(), chr.getLevel(), map.getId(), map.getMapName(), snapshot.dwellMs() / 1000);
        } else {
            // summon() refuses for its own reasons and the player is never told, so say why in the log.
            log.info("No companion for {} despite qualifying: {}", chr.getName(), supervisor.summonBlockedReason(chr));
        }
        return started;
    }

    /**
     * A map people grind on: it has mob spawn points, and no event instance is running on it. Towns, the
     * Free Market and party quest lobbies have no spawn points at all, so this needs no list of map ids
     * to keep up to date - and inside an event the party belongs to the party quest, not to us.
     */
    static boolean isFieldMap(MapleMap map) {
        return map != null && map.getEventInstance() == null
                && map.findClosestSpawnpoint(new Point(0, 0)) != null;
    }

    /**
     * Whether this player has earned some company. Package-private and free of the server so every rule
     * above can be tested directly.
     */
    static boolean shouldSummon(Snapshot snapshot, Settings settings) {
        return settings.enabled()
                && snapshot.onField()
                && snapshot.dwellMs() >= settings.dwellMs()
                && snapshot.companionsOut() == 0
                && snapshot.capacity() > 0
                && snapshot.sinceLastSummonMs() >= settings.cooldownMs();
    }

    /** Drops players who have logged out or gone quiet, so the director does not grow all session. */
    private void forgetPlayersNotSeenSince(long now) {
        for (Iterator<Map.Entry<Integer, Seen>> it = seenByCharId.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().lastSeen() > FORGET_AFTER_MS) {
                it.remove();
            }
        }
    }
}
