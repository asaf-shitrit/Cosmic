package server.life;

import client.Job;
import config.YamlConfig;
import constants.id.MapId;
import net.packet.InPacket;
import net.server.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.TimerManager;
import server.maps.FootholdTree;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import tools.PacketCreator;
import tools.Randomizer;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spawns and walks {@link FakePlayer}s so the world doesn't feel deserted. One scheduled task
 * drives every fake player on the server.
 *
 * <p>Each fake player commits to a destination and walks there over several ticks, then stands
 * around for a while before picking a new one. Re-rolling the direction every tick instead just
 * produces a random walk with no net displacement, which reads as jittering on the spot rather
 * than going somewhere.
 *
 * @see FakePlayerActivity for how destinations and loitering times are chosen
 */
public class FakePlayerService {
    private static final Logger log = LoggerFactory.getLogger(FakePlayerService.class);
    private static final FakePlayerService instance = new FakePlayerService();

    /** Fake character ids start well above anything the characters table will ever autoincrement to. */
    private static final int FAKE_CHARACTER_ID_BASE = 90_000_000;

    private static final int WALK_TICK_MS = 2_000;
    /** How far a fake player covers in one tick. Roughly a slow walk at this tick rate. */
    private static final int STEP_PIXELS = 140;
    /** Close enough to count as arrived. Must exceed STEP_PIXELS or they oscillate around the target. */
    private static final int ARRIVAL_PIXELS = 160;
    /** How far an anchored fake player will drift from the spot it was spawned at. */
    private static final int ANCHOR_DRIFT_PIXELS = 220;

    private static final String[] NAMES = {
            "Sera", "Doyle", "Kappa", "Mirin", "Tobi", "Wisp", "Nico", "Fenn", "Rilla", "Oz",
            "Bram", "Yuki", "Pim", "Corvo", "Lune", "Hazel", "Rook", "Tove", "Ines", "Kerr",
            "Miso", "Dax", "Nell", "Pico", "Vash", "Juno", "Bex", "Odin", "Fig", "Wren"
    };

    // Equip slots as written in the spawn packet (positive positions).
    private static final short SLOT_TOP = 5;
    private static final short SLOT_BOTTOM = 6;
    private static final short SLOT_SHOES = 7;
    private static final short SLOT_WEAPON = 11;

    private static final int[] MALE_FACES = {20000, 20001, 20002, 20003, 20004, 20005, 20006, 20007};
    private static final int[] FEMALE_FACES = {21000, 21001, 21002, 21003, 21004, 21005, 21006, 21007};
    private static final int[] MALE_HAIRS = {30000, 30020, 30030, 30040, 30050, 30060, 30070, 30080};
    private static final int[] FEMALE_HAIRS = {31000, 31010, 31020, 31030, 31040, 31050, 31060, 31070};
    private static final int[] TOPS = {1040002, 1040006, 1040010, 1041002, 1041006, 1041010};
    private static final int[] BOTTOMS = {1060002, 1060006, 1061002, 1061006, 1061008};
    private static final int[] SHOES = {1072001, 1072005, 1072037, 1072038};

    /**
     * A class a fake player can appear to be, with the weapons that class would actually carry and
     * the level band that job advancement sits in. Only the three job lines that exist in v83 are
     * listed - Explorers, Cygnus Knights and Aran. Evan is deliberately absent: the Job enum still
     * carries its ids from later HeavenMS content, but v83 has no Evan.
     */
    private record Archetype(int jobId, int minLevel, int maxLevel, int[] weapons) {
    }

    private static final Archetype[] ARCHETYPES = {
            // Explorers, first job
            new Archetype(Job.WARRIOR.getId(), 10, 29, new int[]{1302000, 1302007, 1312004, 1322005}),
            new Archetype(Job.MAGICIAN.getId(), 8, 29, new int[]{1372005, 1372000, 1382009}),
            new Archetype(Job.BOWMAN.getId(), 10, 29, new int[]{1452005, 1452000, 1462000}),
            new Archetype(Job.THIEF.getId(), 10, 29, new int[]{1332012, 1332000, 1472000}),
            new Archetype(Job.PIRATE.getId(), 10, 29, new int[]{1482000, 1492000}),
            // Explorers, second job
            new Archetype(Job.FIGHTER.getId(), 30, 70, new int[]{1302007, 1312004, 1322005}),
            new Archetype(Job.SPEARMAN.getId(), 30, 70, new int[]{1432000, 1442011}),
            new Archetype(Job.FP_WIZARD.getId(), 30, 70, new int[]{1382009, 1372005}),
            new Archetype(Job.CLERIC.getId(), 30, 70, new int[]{1382009, 1372005}),
            new Archetype(Job.HUNTER.getId(), 30, 70, new int[]{1452005, 1452000}),
            new Archetype(Job.CROSSBOWMAN.getId(), 30, 70, new int[]{1462000, 1462005}),
            new Archetype(Job.ASSASSIN.getId(), 30, 70, new int[]{1472000, 1472001}),
            new Archetype(Job.BANDIT.getId(), 30, 70, new int[]{1332012, 1332000}),
            // Cygnus Knights
            new Archetype(Job.DAWNWARRIOR1.getId(), 10, 45, new int[]{1302000, 1302007}),
            new Archetype(Job.BLAZEWIZARD1.getId(), 10, 45, new int[]{1372005, 1382009}),
            new Archetype(Job.WINDARCHER1.getId(), 10, 45, new int[]{1452005, 1452000}),
            new Archetype(Job.NIGHTWALKER1.getId(), 10, 45, new int[]{1472000, 1332012}),
            new Archetype(Job.THUNDERBREAKER1.getId(), 10, 45, new int[]{1482000}),
            // Aran - polearms only
            new Archetype(Job.ARAN1.getId(), 10, 45, new int[]{1442011, 1442000}),
    };

    /**
     * A map worth populating, how heavily relative to the configured per-town count, and what the
     * people there are doing. The activity array is sampled per fake player, so repeating an entry
     * weights it.
     */
    private record Crowd(int mapId, double density, FakePlayerActivity[] activities) {
    }

    private static final FakePlayerActivity[] TOWN_MIX = {
            FakePlayerActivity.BROWSING, FakePlayerActivity.BROWSING,
            FakePlayerActivity.TRAVELLING, FakePlayerActivity.VENDING,
    };

    private static final Crowd[] WORLD_CROWDS = {
            // The Free Market was always the busiest map in the game, and almost entirely parked shops.
            new Crowd(MapId.FM_ENTRANCE, 4.0, new FakePlayerActivity[]{
                    FakePlayerActivity.VENDING, FakePlayerActivity.VENDING, FakePlayerActivity.VENDING,
                    FakePlayerActivity.BROWSING, FakePlayerActivity.TRAVELLING}),
            // Party quest entrances clogged up with people waiting to fill a party.
            new Crowd(MapId.HENESYS_PQ, 2.5, new FakePlayerActivity[]{
                    FakePlayerActivity.WAITING, FakePlayerActivity.WAITING, FakePlayerActivity.TRAVELLING}),
            // Kerning City is where Kerning PQ parties formed up.
            new Crowd(MapId.KERNING_CITY, 2.0, new FakePlayerActivity[]{
                    FakePlayerActivity.WAITING, FakePlayerActivity.WAITING,
                    FakePlayerActivity.BROWSING, FakePlayerActivity.TRAVELLING}),
            new Crowd(MapId.HENESYS, 2.0, TOWN_MIX),
            new Crowd(MapId.ELLINIA, 1.0, TOWN_MIX),
            new Crowd(MapId.PERION, 1.0, TOWN_MIX),
            new Crowd(MapId.LITH_HARBOUR, 1.0, TOWN_MIX),
            new Crowd(MapId.SLEEPYWOOD, 0.5, TOWN_MIX),
            new Crowd(MapId.ORBIS, 1.0, TOWN_MIX),
            new Crowd(MapId.EL_NATH, 0.5, TOWN_MIX),
    };

    /**
     * Where a fake player is, what it's doing, and where it's walking to. Mutable and only ever
     * touched from the single walk task, so it needs no locking of its own.
     */
    private static final class Wanderer {
        private final FakePlayer fakePlayer;
        private final MapleMap map;
        private final FakePlayerActivity activity;
        /** The spot this fake player was spawned at; anchored activities stay near it. */
        private final int anchorX;
        private int destinationX;
        private int dwellTicks;

        private Wanderer(FakePlayer fakePlayer, MapleMap map, FakePlayerActivity activity, int anchorX) {
            this.fakePlayer = fakePlayer;
            this.map = map;
            this.activity = activity;
            this.anchorX = anchorX;
            this.destinationX = anchorX;
            this.dwellTicks = activity.rollDwellTicks();
        }
    }

    private final AtomicInteger runningCharacterId = new AtomicInteger(FAKE_CHARACTER_ID_BASE);
    private final Map<Integer, Wanderer> wanderers = new ConcurrentHashMap<>();
    /** NPC x positions per map, so BROWSING fake players head for shops rather than empty ground. */
    private final Map<MapleMap, int[]> npcPositions = new ConcurrentHashMap<>();
    private ScheduledFuture<?> walkTask;

    private FakePlayerService() {
    }

    public static FakePlayerService getInstance() {
        return instance;
    }

    public synchronized void start() {
        if (walkTask != null) {
            return;
        }
        walkTask = TimerManager.getInstance().register(this::walkAll, WALK_TICK_MS, WALK_TICK_MS);
        log.info("Fake player service started");
    }

    public synchronized void stop() {
        if (walkTask != null) {
            walkTask.cancel(false);
            walkTask = null;
        }
    }

    /**
     * Populates every crowd map on a channel, weighted by {@code FAKE_PLAYERS_PER_TOWN}.
     *
     * @return how many fake players were spawned.
     */
    public int populateChannel(Channel channel) {
        int perTown = YamlConfig.config.server.FAKE_PLAYERS_PER_TOWN;
        if (perTown < 1) {
            return 0;
        }

        int spawned = 0;
        for (Crowd crowd : WORLD_CROWDS) {
            int count = (int) Math.round(perTown * crowd.density());
            if (count < 1) {
                continue;
            }

            try {
                MapleMap map = channel.getMapFactory().getMap(crowd.mapId());
                spawned += populate(map, count, crowd.activities());
            } catch (Exception e) {
                log.warn("Failed to spawn fake players in map {}", crowd.mapId(), e);
            }
        }
        return spawned;
    }

    /** Scatters {@code count} fake players across a map with a mixture of activities. */
    public int populate(MapleMap map, int count) {
        return populate(map, count, TOWN_MIX);
    }

    /**
     * Scatters {@code count} fake players across the walkable width of the map, each assigned an
     * activity sampled from {@code activities}.
     *
     * @return how many actually found ground to stand on.
     */
    public int populate(MapleMap map, int count, FakePlayerActivity[] activities) {
        FootholdTree footholds = map.getFootholds();
        int minX = footholds.getMinDropX();
        int maxX = footholds.getMaxDropX();
        int spawned = 0;

        for (int i = 0; i < count; i++) {
            int x = minX + Randomizer.nextInt(Math.max(1, maxX - minX));
            if (spawn(map, new Point(x, footholds.getY1()), pick(activities)) != null) {
                spawned++;
            }
        }
        return spawned;
    }

    /**
     * Spawns a fake player with a randomised look at {@code position}, snapped to the ground.
     *
     * @return the spawned fake player, or null if the position has no foothold under it.
     */
    public FakePlayer spawn(MapleMap map, Point position, FakePlayerActivity activity) {
        Point ground;
        try {
            ground = map.getGroundBelow(position);
        } catch (Exception e) {
            return null;    // no foothold below the requested point
        }
        if (ground == null) {
            return null;
        }

        FakePlayer fakePlayer = createRandom();
        fakePlayer.setPosition(ground);

        map.addFakePlayerMapObject(fakePlayer);
        map.broadcastMessage(PacketCreator.spawnFakePlayer(fakePlayer));

        Wanderer wanderer = new Wanderer(fakePlayer, map, activity, ground.x);
        wanderer.destinationX = chooseDestination(wanderer);
        wanderers.put(fakePlayer.getCharacterId(), wanderer);
        return fakePlayer;
    }

    public int despawnAll(MapleMap map) {
        List<Wanderer> toRemove = new ArrayList<>();
        for (Wanderer wanderer : wanderers.values()) {
            if (wanderer.map == map) {
                toRemove.add(wanderer);
            }
        }

        for (Wanderer wanderer : toRemove) {
            despawn(wanderer);
        }
        npcPositions.remove(map);
        return toRemove.size();
    }

    private void despawn(Wanderer wanderer) {
        FakePlayer fakePlayer = wanderer.fakePlayer;
        wanderers.remove(fakePlayer.getCharacterId());
        wanderer.map.removeFakePlayerMapObject(fakePlayer);
        wanderer.map.broadcastMessage(PacketCreator.removePlayerFromMap(fakePlayer.getCharacterId()));
    }

    public int countIn(MapleMap map) {
        return (int) wanderers.values().stream().filter(w -> w.map == map).count();
    }

    public int countAll() {
        return wanderers.size();
    }

    private void walkAll() {
        for (Wanderer wanderer : wanderers.values()) {
            try {
                walk(wanderer);
            } catch (Exception e) {
                log.warn("Failed to walk fake player {}", wanderer.fakePlayer, e);
            }
        }
    }

    private void walk(Wanderer wanderer) {
        MapleMap map = wanderer.map;
        if (map.getAllPlayers().isEmpty()) {
            return;     // nobody there to see it
        }

        FakePlayer fakePlayer = wanderer.fakePlayer;
        Point current = fakePlayer.getPosition();

        if (wanderer.dwellTicks > 0) {
            wanderer.dwellTicks--;
            fakePlayer.faceStanding();
            broadcastMove(map, fakePlayer, current, WALK_TICK_MS);
            return;
        }

        int remaining = wanderer.destinationX - current.x;
        if (Math.abs(remaining) <= ARRIVAL_PIXELS) {
            // Arrived - stand about for a while, then head somewhere new.
            wanderer.dwellTicks = wanderer.activity.rollDwellTicks();
            wanderer.destinationX = chooseDestination(wanderer);
            fakePlayer.faceStanding();
            broadcastMove(map, fakePlayer, current, WALK_TICK_MS);
            return;
        }

        boolean left = remaining < 0;
        int step = Math.min(STEP_PIXELS, Math.abs(remaining));
        int targetX = clampToMap(map, current.x + (left ? -step : step));

        Point destination;
        try {
            destination = map.getGroundBelow(new Point(targetX, current.y));
        } catch (Exception e) {
            destination = null;     // walked off the end of a foothold
        }

        if (destination == null) {
            // Can't get there from here; give up on this destination and pick another.
            wanderer.destinationX = chooseDestination(wanderer);
            wanderer.dwellTicks = 1;
            fakePlayer.faceStanding();
            broadcastMove(map, fakePlayer, current, WALK_TICK_MS);
            return;
        }

        fakePlayer.faceWalking(left);
        fakePlayer.setPosition(destination);
        broadcastMove(map, fakePlayer, destination, WALK_TICK_MS);
    }

    /**
     * Picks where this fake player walks next, based on what it's pretending to be doing.
     * Anchored activities stay near where they spawned so crowds don't disperse over time.
     */
    private int chooseDestination(Wanderer wanderer) {
        MapleMap map = wanderer.map;
        FootholdTree footholds = map.getFootholds();
        int minX = footholds.getMinDropX();
        int maxX = footholds.getMaxDropX();

        if (wanderer.activity.isAnchored()) {
            int drift = Randomizer.nextInt(ANCHOR_DRIFT_PIXELS * 2 + 1) - ANCHOR_DRIFT_PIXELS;
            return clampToMap(map, wanderer.anchorX + drift);
        }

        if (wanderer.activity == FakePlayerActivity.BROWSING) {
            int[] npcs = npcPositionsIn(map);
            if (npcs.length > 0) {
                return clampToMap(map, npcs[Randomizer.nextInt(npcs.length)]);
            }
        }

        // Travelling: head for somewhere genuinely far away, so it reads as crossing the map.
        int width = Math.max(1, maxX - minX);
        int currentX = wanderer.fakePlayer.getPosition().x;
        int target = minX + Randomizer.nextInt(width);
        if (Math.abs(target - currentX) < width / 3) {
            // Too close to be worth walking to - go to the far side instead.
            target = (currentX - minX) > width / 2 ? minX + Randomizer.nextInt(width / 3)
                    : maxX - Randomizer.nextInt(width / 3);
        }
        return clampToMap(map, target);
    }

    /**
     * The x positions of a map's NPCs, computed once per map. Shops and quest givers are where
     * players actually congregated, so BROWSING fake players walk between them.
     */
    private int[] npcPositionsIn(MapleMap map) {
        return npcPositions.computeIfAbsent(map, m -> {
            List<MapObject> npcs = m.getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY,
                    Arrays.asList(MapObjectType.NPC));
            return npcs.stream().mapToInt(npc -> npc.getPosition().x).toArray();
        });
    }

    private static int clampToMap(MapleMap map, int x) {
        FootholdTree footholds = map.getFootholds();
        return Math.max(footholds.getMinDropX(), Math.min(footholds.getMaxDropX(), x));
    }

    private void broadcastMove(MapleMap map, FakePlayer fakePlayer, Point destination, int durationMs) {
        InPacket movement = fakePlayer.getWalkMovement(destination, durationMs);
        map.broadcastMessage(PacketCreator.movePlayer(fakePlayer.getCharacterId(), movement,
                FakePlayer.MOVEMENT_PACKET_LENGTH));
    }

    private FakePlayer createRandom() {
        int gender = Randomizer.nextInt(2);
        boolean male = gender == 0;

        int face = pick(male ? MALE_FACES : FEMALE_FACES);
        int hair = pick(male ? MALE_HAIRS : FEMALE_HAIRS);
        int skin = Randomizer.nextInt(4);

        Archetype archetype = pick(ARCHETYPES);
        int level = archetype.minLevel() + Randomizer.nextInt(archetype.maxLevel() - archetype.minLevel() + 1);

        Map<Short, Integer> equips = new LinkedHashMap<>();
        putIfKnown(equips, SLOT_TOP, pick(TOPS));
        putIfKnown(equips, SLOT_BOTTOM, pick(BOTTOMS));
        putIfKnown(equips, SLOT_SHOES, pick(SHOES));
        putIfKnown(equips, SLOT_WEAPON, pickKnown(archetype.weapons()));

        String name = pick(NAMES) + Randomizer.nextInt(100);
        return new FakePlayer(runningCharacterId.incrementAndGet(), name, level, archetype.jobId(), gender, skin,
                face, hair, equips);
    }

    /**
     * Equip ids are hardcoded above, so anything missing from this server's Item.wz is skipped
     * rather than written into the spawn packet as an id the client can't resolve.
     */
    private static void putIfKnown(Map<Short, Integer> equips, short slot, int itemId) {
        if (ItemInformationProvider.getInstance().getName(itemId) != null) {
            equips.put(slot, itemId);
        }
    }

    /**
     * Picks the first of {@code options} this server's Item.wz actually knows, so a class always
     * ends up holding something rather than being silently disarmed by {@link #putIfKnown}.
     *
     * @return a known item id, or the first option if none are known (putIfKnown then drops it).
     */
    private static int pickKnown(int[] options) {
        int start = Randomizer.nextInt(options.length);
        for (int i = 0; i < options.length; i++) {
            int itemId = options[(start + i) % options.length];
            if (ItemInformationProvider.getInstance().getName(itemId) != null) {
                return itemId;
            }
        }
        return options[start];
    }

    private static int pick(int[] options) {
        return options[Randomizer.nextInt(options.length)];
    }

    private static String pick(String[] options) {
        return options[Randomizer.nextInt(options.length)];
    }

    private static Archetype pick(Archetype[] options) {
        return options[Randomizer.nextInt(options.length)];
    }

    private static FakePlayerActivity pick(FakePlayerActivity[] options) {
        return options[Randomizer.nextInt(options.length)];
    }
}
