package bot;

import net.opcodes.SendOpcode;
import net.packet.InPacket;

import java.awt.Point;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** A drop currently sitting on the map, as last reported by {@code DROP_ITEM_FROM_MAPOBJECT}. */
    public record ItemDrop(int objectId, int itemId, Point position) {}

    /** An unanswered {@code PARTY_OPERATION} sub-opcode 4 (invite) this bot has not yet acted on. */
    public record PartyInvite(int partyId, String fromName) {}

    /** The most recent {@code NPC_TALK} this bot received, not yet necessarily responded to. */
    public record NpcTalk(int npcId, int msgType, String text) {}

    private record EtcSlotEntry(int itemId, int quantity) {}

    private final int selfCharId;
    private final Map<Integer, NpcSighting> npcs = new ConcurrentHashMap<>();
    private final Map<Integer, MonsterSighting> monsters = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> otherPlayers = new ConcurrentHashMap<>();
    private final Map<Integer, ItemDrop> itemDrops = new ConcurrentHashMap<>();

    /** ETC inventory slot -> (itemId, quantity), kept in sync from {@code INVENTORY_OPERATION}. */
    private final Map<Integer, EtcSlotEntry> etcSlots = new ConcurrentHashMap<>();

    private volatile int partyId = -1;
    private volatile int partyLeaderId = -1;
    private final Set<Integer> partyMemberIds = ConcurrentHashMap.newKeySet();
    private volatile PartyInvite pendingPartyInvite;
    private volatile NpcTalk lastNpcTalk;

    /** Bumped on every spawn/despawn/move so a planner loop can cheaply notice "something changed". */
    private final AtomicInteger changeVersion = new AtomicInteger();
    private final AtomicInteger mapChangeCount = new AtomicInteger();
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
        if (opcode == SendOpcode.NPC_TALK.getValue()) {
            return acceptNpcTalk(p);
        }
        if (opcode == SendOpcode.PARTY_OPERATION.getValue()) {
            return acceptPartyOperation(p);
        }
        if (opcode == SendOpcode.DROP_ITEM_FROM_MAPOBJECT.getValue()) {
            return acceptItemDrop(p);
        }
        if (opcode == SendOpcode.REMOVE_ITEM_FROM_MAP.getValue()) {
            int objectId = p.readInt();
            ItemDrop removed = itemDrops.remove(objectId);
            if (removed == null) {
                return null;
            }
            changeVersion.incrementAndGet();
            return "item drop " + removed.itemId() + " (oid=" + objectId + ") removed";
        }
        if (opcode == SendOpcode.INVENTORY_OPERATION.getValue()) {
            return acceptInventoryOperation(p);
        }
        return null;
    }

    /**
     * {@code PacketCreator.getNPCTalk}: byte(4, constant), int npcId, byte msgType, byte speaker,
     * string talk, then type-specific trailing bytes this class doesn't need to decode (packets are
     * self-contained frames - see class javadoc). msgType is what a follow-up
     * {@link Action.RespondToNpc} must echo back for {@code NPCMoreTalkHandler} to route correctly:
     * 0 = OK/Next/Prev, 1 = Yes/No, 4 = a numbered "simple" list of {@code #Ln#} links.
     */
    private String acceptNpcTalk(InPacket p) {
        p.readByte();                 // constant 4
        int npcId = p.readInt();
        int msgType = p.readByte() & 0xFF;
        p.readByte();                 // speaker portrait id - irrelevant here
        String text = p.readString();
        lastNpcTalk = new NpcTalk(npcId, msgType, text);
        changeVersion.incrementAndGet();
        return "NPC " + npcId + " says (type " + msgType + "): " + text;
    }

    /**
     * {@code PARTY_OPERATION} is one opcode carrying a dozen unrelated sub-messages, tagged by a
     * leading sub-opcode byte written by whichever {@code PacketCreator} method built it. Only the
     * handful this bot actually needs to react to are decoded; everything else is left alone (safe -
     * see class javadoc on under-reading).
     */
    private String acceptPartyOperation(InPacket p) {
        int sub = p.readByte() & 0xFF;
        switch (sub) {
            case 0x04: {   // partyInvite/partySearchInvite: int partyId, string fromName, byte
                int invitePartyId = p.readInt();
                String fromName = p.readString();
                pendingPartyInvite = new PartyInvite(invitePartyId, fromName);
                changeVersion.incrementAndGet();
                return "party invite from " + fromName + " (partyId=" + invitePartyId + ")";
            }
            case 0x08: {   // partyCreated: int partyId, then 4 ints of door info (irrelevant, no door)
                partyId = p.readInt();
                partyLeaderId = selfCharId;
                partyMemberIds.add(selfCharId);
                changeVersion.incrementAndGet();
                return "party " + partyId + " created, this bot is leader";
            }
            case 0x0F: {   // updateParty(JOIN): int partyId, string joinedName, then addPartyStatus
                partyId = p.readInt();
                p.readString();       // joined member's name - membership itself comes from the ids below
                readPartyStatusMemberIdsAndLeader(p);
                changeVersion.incrementAndGet();
                return "party " + partyId + " now has " + partyMemberIds.size() + " member(s)";
            }
            case 0x07: {   // updateParty(SILENT_UPDATE/LOG_ONOFF): int partyId, then addPartyStatus
                partyId = p.readInt();
                readPartyStatusMemberIdsAndLeader(p);
                changeVersion.incrementAndGet();
                return "party " + partyId + " roster refreshed, " + partyMemberIds.size() + " member(s)";
            }
            case 0x0C: {   // updateParty(LEAVE/EXPEL/DISBAND): int partyId, int targetId, byte...
                int opPartyId = p.readInt();
                int targetId = p.readInt();
                int flag = p.readByte() & 0xFF;
                if (flag == 0) {                          // disband: byte(0), int partyId again
                    resetPartyState();
                    return "party " + opPartyId + " disbanded";
                }
                p.readByte();                              // 1 = expel, 0 = leave
                p.readString();                             // target's name
                readPartyStatusMemberIdsAndLeader(p);
                if (targetId == selfCharId) {
                    resetPartyState();
                    return "removed from party " + opPartyId;
                }
                changeVersion.incrementAndGet();
                return "party " + opPartyId + " member " + targetId + " left/was expelled";
            }
            case 0x1B: {   // updateParty(CHANGE_LEADER): int newLeaderId, byte
                partyLeaderId = p.readInt();
                changeVersion.incrementAndGet();
                return "party leader changed to " + partyLeaderId;
            }
            default:
                return null;
        }
    }

    /**
     * Common tail of {@code PacketCreator#addPartyStatus}: 6 member-id ints (0 for an empty slot),
     * 6 fixed 13-byte names, 6 job ints, 6 level ints, 6 channel ints, one leader-id int, 6 map-id
     * ints, then 6 door blocks of 4 ints each. Only the id array and the leader id are useful here.
     */
    private void readPartyStatusMemberIdsAndLeader(InPacket p) {
        int[] ids = new int[6];
        for (int i = 0; i < 6; i++) {
            ids[i] = p.readInt();
        }
        p.skip(6 * 13);              // fixed-width names - not needed, characters are identified by id
        p.skip(6 * 4 * 3);           // job, level, channel per slot
        int leader = p.readInt();
        p.skip(6 * 4);               // map id per slot
        p.skip(6 * 4 * 4);           // door blocks

        partyLeaderId = leader;
        partyMemberIds.clear();
        for (int id : ids) {
            if (id != 0) {
                partyMemberIds.add(id);
            }
        }
    }

    private void resetPartyState() {
        partyId = -1;
        partyLeaderId = -1;
        partyMemberIds.clear();
        changeVersion.incrementAndGet();
    }

    /**
     * {@code PacketCreator.dropItemFromMapObject}, mod != 2 shape (the only shape a real item/meso
     * drop from a player or monster uses - mod == 2's shorter {@code updateMapItemObject} shape is
     * only sent for party-ownership refreshes, not covered here): byte mod, int oid, bool isMeso,
     * int itemId, int ownerId, byte dropType, pos dropTo, ... (rest not needed - see class javadoc on
     * under-reading self-contained frames).
     */
    private String acceptItemDrop(InPacket p) {
        int mod = p.readByte() & 0xFF;
        if (mod == 2) {
            return null;           // ownership-refresh shape, different layout - not tracked
        }
        int objectId = p.readInt();
        boolean isMeso = p.readByte() != 0;
        int itemId = p.readInt();
        p.readInt();               // clientside owner id (char or party id) - not needed
        p.readByte();              // drop type
        Point pos = p.readPos();
        if (isMeso) {
            return null;           // mesos aren't a quest item this bot ever needs to pick up
        }
        itemDrops.put(objectId, new ItemDrop(objectId, itemId, pos));
        changeVersion.incrementAndGet();
        return "item " + itemId + " dropped at " + pointToString(pos) + " (oid=" + objectId + ")";
    }

    /**
     * {@code PacketCreator.modifyInventory}: bool updateTick, byte modCount, then per mod: byte mode,
     * byte inventoryType, short position, then mode-specific fields (0 = add: full item info; 1 =
     * update quantity: short; 2 = move; 3 = remove). Only ETC-type add/update/remove is decoded -
     * that's the only inventory type this quest's items (coupons, passes) ever occupy - and equip-type
     * adds are bailed out of early since their trailing field count differs from the mode itself, which
     * would otherwise misalign every mod after it in the same packet.
     */
    private String acceptInventoryOperation(InPacket p) {
        p.readByte();                       // updateTick bool
        int modCount = p.readByte() & 0xFF;
        boolean changed = false;
        for (int i = 0; i < modCount; i++) {
            int mode = p.readByte() & 0xFF;
            int invType = p.readByte() & 0xFF;
            int position = p.readShort();
            boolean isEtc = invType == 4;   // InventoryType.ETC

            if (mode == 0) {                // add
                int itemType = p.readByte() & 0xFF;
                int itemId = p.readInt();
                boolean isCash = p.readByte() != 0;
                if (isCash) {
                    p.readLong();
                }
                p.readLong();               // expiration
                if (itemType != 2) {        // not a plain ETC/USE-shaped item - stop decoding this packet
                    break;                  // (equip/pet layouts diverge in field count from here on)
                }
                int qty = p.readShort();
                p.readString();             // owner
                p.readShort();              // flag
                if (isEtc) {
                    etcSlots.put(position, new EtcSlotEntry(itemId, qty));
                    changed = true;
                }
            } else if (mode == 1) {         // update quantity
                int qty = p.readShort();
                if (isEtc) {
                    EtcSlotEntry existing = etcSlots.get(position);
                    if (existing != null) {
                        etcSlots.put(position, new EtcSlotEntry(existing.itemId(), qty));
                        changed = true;
                    }
                }
            } else if (mode == 3) {         // remove
                if (isEtc && etcSlots.remove(position) != null) {
                    changed = true;
                }
            } else {
                break;                      // mode 2 (move): never sent to a bot that never rearranges
            }
        }
        if (!changed) {
            return null;
        }
        changeVersion.incrementAndGet();
        return "ETC inventory updated";
    }

    /**
     * Clears everything scoped to the map this bot was just standing on. {@code SET_FIELD} carries a
     * new map id on every warp (initial login included) but in two structurally different shapes
     * ({@code getCharInfo}'s full snapshot vs {@code getWarpToMap}'s short form) that aren't worth
     * disambiguating just to read a field this bot doesn't otherwise need - the driver loop
     * (see {@code BotSession}) calls this on every {@code SET_FIELD} instead, since stale NPC/monster/
     * drop object ids from the previous map are actively dangerous (a stale oid could collide with a
     * live one on the new map) while simply forgetting them a moment early never is.
     */
    public void onMapChanged() {
        npcs.clear();
        monsters.clear();
        itemDrops.clear();
        mapChangeCount.incrementAndGet();
        changeVersion.incrementAndGet();
    }

    /**
     * How many times {@link #onMapChanged()} has fired, counting the very first one (this bot's
     * initial world entry). A planner that needs to know "did I just warp somewhere new" - without
     * decoding {@code SET_FIELD}'s map id itself, see {@link #onMapChanged()} - polls this once per
     * tick and reacts to it changing.
     */
    public int getMapChangeCount() {
        return mapChangeCount.get();
    }

    public int getPartyId() {
        return partyId;
    }

    public boolean isPartyLeader() {
        return partyId != -1 && partyLeaderId == selfCharId;
    }

    public Set<Integer> getPartyMemberIds() {
        return Set.copyOf(partyMemberIds);
    }

    public PartyInvite getPendingPartyInvite() {
        return pendingPartyInvite;
    }

    /** Clears the invite once a planner has acted on it, so the same invite isn't reprocessed. */
    public void clearPendingPartyInvite() {
        pendingPartyInvite = null;
    }

    public NpcTalk getLastNpcTalk() {
        return lastNpcTalk;
    }

    public Collection<ItemDrop> getItemDrops() {
        return Collections.unmodifiableCollection(itemDrops.values());
    }

    /** Total quantity of {@code itemId} held across the ETC inventory, or 0 if none is held. */
    public int getEtcQuantity(int itemId) {
        int total = 0;
        for (EtcSlotEntry e : etcSlots.values()) {
            if (e.itemId() == itemId) {
                total += e.quantity();
            }
        }
        return total;
    }

    /** The ETC slot currently holding {@code itemId}, or empty if none is held. */
    public Optional<Integer> getEtcSlot(int itemId) {
        for (Map.Entry<Integer, EtcSlotEntry> e : etcSlots.entrySet()) {
            if (e.getValue().itemId() == itemId) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
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
