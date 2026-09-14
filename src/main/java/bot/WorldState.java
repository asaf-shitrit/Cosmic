package bot;

import net.opcodes.SendOpcode;
import net.packet.InPacket;

import java.awt.Point;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A live snapshot of one channel map, built purely by observing the packets the channel server
 * volunteers after PLAYER_LOGGEDIN. There is no "describe the map" request in this protocol - the
 * real client (and so this bot) only ever learns what's on the map by listening to spawn/despawn
 * broadcasts as they happen, which is exactly what {@link #accept} does.
 *
 * <p>This deliberately only tracks the handful of fields a {@link Planner} needs (id, template id,
 * position), not a full mirror of every wire field - see {@code CreateCharHandler}-adjacent packet
 * writers in {@code PacketCreator} for the full layouts if more is ever needed.
 *
 * <p>The server never acknowledges a move (see {@link ActionExecutor#execute}), so this class is
 * also the only record of the bot's own position: {@link #setSelfPosition} is called by
 * {@link ActionExecutor} right after a move is sent, trusting the server to accept it exactly like
 * {@code MovePlayerHandler} does for a real client.
 */
public class WorldState {
    public record NpcSighting(int objectId, int npcId, Point position) {}

    public record MonsterSighting(int objectId, int monsterId, Point position) {}

    private final int selfCharId;
    private final Map<Integer, NpcSighting> npcs = new ConcurrentHashMap<>();
    private final Map<Integer, MonsterSighting> monsters = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> otherPlayers = new ConcurrentHashMap<>();

    /** Bumped on every spawn/despawn/move so a planner loop can cheaply notice "something changed". */
    private final AtomicInteger changeVersion = new AtomicInteger();
    private volatile Point selfPosition;

    public WorldState(int selfCharId) {
        this.selfCharId = selfCharId;
    }

    /**
     * Feeds one channel packet in, positioned just after its opcode short (the same convention
     * {@code BotSession}'s read loop already uses). Opcodes this class doesn't care about are simply
     * ignored - packets are self-contained frames (length comes from the header, not from how much a
     * reader chooses to parse) so under-reading one here is always safe, never a desync risk.
     *
     * @return a short human-readable description of what changed, or {@code null} if the opcode
     * wasn't one this class tracks.
     */
    public String accept(int opcode, InPacket p) {
        if (opcode == SendOpcode.SPAWN_NPC.getValue()) {
            int oid = p.readInt();
            int npcId = p.readInt();
            Point pos = p.readPos();      // spawnNPC writes x then cy as two plain shorts - same bytes as writePos
            npcs.put(oid, new NpcSighting(oid, npcId, pos));
            changeVersion.incrementAndGet();
            return "NPC " + npcId + " spawned at " + pointToString(pos) + " (oid=" + oid + ")";
        }
        if (opcode == SendOpcode.REMOVE_NPC.getValue()) {
            NpcSighting removed = npcs.remove(p.readInt());
            if (removed == null) {
                return null;
            }
            changeVersion.incrementAndGet();
            return "NPC " + removed.npcId() + " (oid=" + removed.objectId() + ") left the map";
        }
        if (opcode == SendOpcode.SPAWN_MONSTER.getValue()) {
            int oid = p.readInt();
            p.readByte();                  // controller flag - irrelevant to perception
            int monsterId = p.readInt();
            // Monster#sendSpawnData always calls PacketCreator.spawnMonster(this, false), i.e. the
            // requestController=false branch, which writes a fixed 16-byte gap here instead of the
            // variable-length buff block the controller-request branch would - always safe to skip.
            p.skip(16);
            Point pos = p.readPos();
            monsters.put(oid, new MonsterSighting(oid, monsterId, pos));
            changeVersion.incrementAndGet();
            return "monster " + monsterId + " spawned at " + pointToString(pos) + " (oid=" + oid + ")";
        }
        if (opcode == SendOpcode.KILL_MONSTER.getValue()) {
            MonsterSighting removed = monsters.remove(p.readInt());
            if (removed == null) {
                return null;
            }
            changeVersion.incrementAndGet();
            return "monster " + removed.monsterId() + " (oid=" + removed.objectId() + ") removed";
        }
        if (opcode == SendOpcode.SPAWN_PLAYER.getValue()) {
            int charId = p.readInt();      // leading field of spawnPlayerMapObject - rest is unparsed
            if (charId == selfCharId || otherPlayers.put(charId, Boolean.TRUE) != null) {
                return null;
            }
            changeVersion.incrementAndGet();
            return "player " + charId + " entered the map";
        }
        if (opcode == SendOpcode.REMOVE_PLAYER_FROM_MAP.getValue()) {
            int charId = p.readInt();
            if (otherPlayers.remove(charId) == null) {
                return null;
            }
            changeVersion.incrementAndGet();
            return "player " + charId + " left the map";
        }
        return null;
    }

    /** This bot's last known position, or {@code null} if it hasn't sent a move yet this session. */
    public Point getSelfPosition() {
        return selfPosition;
    }

    void setSelfPosition(Point position) {
        this.selfPosition = position;
        changeVersion.incrementAndGet();
    }

    public Collection<NpcSighting> getNpcs() {
        return Collections.unmodifiableCollection(npcs.values());
    }

    public Collection<MonsterSighting> getMonsters() {
        return Collections.unmodifiableCollection(monsters.values());
    }

    public int getOtherPlayerCount() {
        return otherPlayers.size();
    }

    /** Monotonically increasing counter, bumped on every tracked change. Cheap "did anything happen" check. */
    public int getChangeVersion() {
        return changeVersion.get();
    }

    private static String pointToString(Point p) {
        return "(" + p.x + "," + p.y + ")";
    }
}
