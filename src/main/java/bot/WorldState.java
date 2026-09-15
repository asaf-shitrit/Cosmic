package bot;

import net.opcodes.SendOpcode;
import net.packet.InPacket;

import java.awt.Point;
import java.nio.charset.StandardCharsets;
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
    /**
     * charId -> the last position the server holds for that player, from {@code SPAWN_PLAYER} and
     * then every {@code MOVE_PLAYER}. A player can be present in {@link #otherPlayers} without an
     * entry here if its spawn packet didn't decode (see {@link #readSpawnPlayerPosition}).
     */
    private final Map<Integer, Point> playerPositions = new ConcurrentHashMap<>();
    private final Map<Integer, ItemDrop> itemDrops = new ConcurrentHashMap<>();

    /** ETC inventory slot -> (itemId, quantity), kept in sync from {@code INVENTORY_OPERATION}. */
    private final Map<Integer, EtcSlotEntry> etcSlots = new ConcurrentHashMap<>();

    /**
     * One occupied slot of the party roster as the server last described it.
     *
     * @param channel zero-indexed channel, or -2 when the member is offline ({@code addPartyStatus})
     * @param mapId   the member's map, or 0 when the member is on a different channel from this bot -
     *                the server only reveals maps for same-channel members
     */
    public record PartyMember(int id, String name, int channel, int mapId) {}

    private volatile int partyId = -1;
    private volatile int partyLeaderId = -1;
    private final Map<Integer, PartyMember> partyMembers = new ConcurrentHashMap<>();

    /** Map this bot is on, from the last {@code SET_FIELD}; -1 until one has been decoded. */
    private volatile int selfMapId = -1;
    /** Portal id the server placed this bot at on arrival (the portal's WZ node index). */
    private volatile int selfSpawnPortalId = -1;
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
            int charId = p.readInt();
            if (charId == selfCharId) {
                return null;
            }
            Point pos = readSpawnPlayerPosition(p);
            if (pos != null) {
                playerPositions.put(charId, pos);
            }
            if (otherPlayers.put(charId, Boolean.TRUE) != null) {
                return null;
            }
            changeVersion.incrementAndGet();
            return "player " + charId + " entered the map at " + (pos == null ? "(undecoded)" : pointToString(pos));
        }
        if (opcode == SendOpcode.MOVE_PLAYER.getValue()) {
            int charId = p.readInt();
            p.readInt();                   // PacketCreator.movePlayer's spare int, always 0
            Point pos = readLastAbsolutePosition(p);
            if (pos == null || charId == selfCharId) {
                return null;
            }
            playerPositions.put(charId, pos);
            changeVersion.incrementAndGet();
            return null;                   // far too frequent to narrate
        }
        if (opcode == SendOpcode.REMOVE_PLAYER_FROM_MAP.getValue()) {
            int charId = p.readInt();
            playerPositions.remove(charId);
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
                partyMembers.put(selfCharId, new PartyMember(selfCharId, "", -1, selfMapId));
                changeVersion.incrementAndGet();
                return "party " + partyId + " created, this bot is leader";
            }
            case 0x0F: {   // updateParty(JOIN): int partyId, string joinedName, then addPartyStatus
                partyId = p.readInt();
                String joinedName = p.readString();
                readPartyStatus(p);
                changeVersion.incrementAndGet();
                return "party " + partyId + ": " + joinedName + " joined, roster " + describeRoster();
            }
            case 0x07: {   // updateParty(SILENT_UPDATE/LOG_ONOFF): int partyId, then addPartyStatus
                int before = rosterFingerprint();
                partyId = p.readInt();
                readPartyStatus(p);
                changeVersion.incrementAndGet();
                // Sent on every member's map change and HP-adjacent refresh - only narrate real changes.
                return before == rosterFingerprint() ? null : "party " + partyId + " roster " + describeRoster();
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
                readPartyStatus(p);
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
     * 6 fixed 13-byte NUL-padded names, 6 job ints, 6 level ints, 6 channel ints (zero-indexed, -2 =
     * offline), one leader-id int, 6 map-id ints (0 for a member on another channel than the
     * receiver), then 6 door blocks of 4 ints each.
     *
     * <p>The map ids are what let a follower learn where its leader went: {@code Character#changeMapInternal}
     * ends in {@code silentPartyUpdateInternal}, so every member's map change is broadcast to the
     * whole party as a {@code SILENT_UPDATE} carrying this block.
     */
    private void readPartyStatus(InPacket p) {
        int[] ids = new int[6];
        for (int i = 0; i < 6; i++) {
            ids[i] = p.readInt();
        }
        String[] names = new String[6];
        for (int i = 0; i < 6; i++) {
            String raw = new String(p.readBytes(13), StandardCharsets.US_ASCII);
            int nul = raw.indexOf('\0');
            names[i] = nul < 0 ? raw : raw.substring(0, nul);
        }
        p.skip(6 * 4 * 2);           // job, level per slot
        int[] channels = new int[6];
        for (int i = 0; i < 6; i++) {
            channels[i] = p.readInt();
        }
        int leader = p.readInt();
        int[] maps = new int[6];
        for (int i = 0; i < 6; i++) {
            maps[i] = p.readInt();
        }
        // door blocks (6 * 4 ints) left unread - see class javadoc on under-reading

        partyLeaderId = leader;
        partyMembers.clear();
        for (int i = 0; i < 6; i++) {
            if (ids[i] != 0) {
                partyMembers.put(ids[i], new PartyMember(ids[i], names[i], channels[i], maps[i]));
            }
        }
    }

    private String describeRoster() {
        StringBuilder sb = new StringBuilder("[");
        for (PartyMember m : partyMembers.values()) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(m.name()).append('#').append(m.id())
                    .append(m.channel() < 0 ? " offline" : " ch" + (m.channel() + 1) + " map " + m.mapId());
        }
        return sb.append("] leader ").append(partyLeaderId).toString();
    }

    /** Changes whenever membership or online state changes, but not on a mere map change. */
    private int rosterFingerprint() {
        int h = partyLeaderId;
        for (PartyMember m : partyMembers.values()) {
            h += 31 * m.id() + (m.channel() < 0 ? 7 : 0);
        }
        return h;
    }

    private void resetPartyState() {
        partyId = -1;
        partyLeaderId = -1;
        partyMembers.clear();
        changeVersion.incrementAndGet();
    }

    /**
     * Skips {@code PacketCreator.spawnPlayerMapObject} up to the position field and reads it, or
     * returns {@code null} if the packet doesn't have that shape (the cosmetic fake players share the
     * opcode but are written by a different method). Layout, after the leading charId:
     * byte level, string name, string guildName, 6 bytes guild logo (zeroed when guildless), then
     * {@code writeForeignBuffs}: int, short, byte, byte, int morphFlag (2 when morphed), int
     * buffMaskHigh, an optional buff value (short if morphed, else byte if Combo is in the high mask),
     * int buffMaskLow, then a fixed 108 bytes of energy/dash/mount/zombify blocks. Then short job,
     * {@code addCharLook} (byte gender, byte skin, int face, byte, int hair, two 0xFF-terminated
     * lists of (byte slot, int itemId), int cash weapon, 3 pet ints), 3 ints (chocolate count, item
     * effect, chair), and finally the position. When the player is entering the field right now the
     * server writes its position 42px higher (it drops onto the foothold client-side) - close enough
     * for following, not corrected here.
     */
    private static Point readSpawnPlayerPosition(InPacket p) {
        try {
            p.readByte();
            p.readString();
            p.readString();
            p.skip(6);
            p.skip(4 + 2 + 1 + 1);
            int morphFlag = p.readInt();
            int maskHigh = p.readInt();
            if (morphFlag == 2) {
                p.skip(2);
            } else if ((maskHigh & COMBO_MASK_HIGH) != 0) {
                p.skip(1);
            }
            p.skip(4);
            p.skip(108);
            p.skip(2);
            p.skip(1 + 1 + 4 + 1 + 4);
            skipEquipList(p);
            skipEquipList(p);
            p.skip(4 + 3 * 4);
            p.skip(3 * 4);
            return p.readPos();
        } catch (RuntimeException e) {
            return null;             // under-read past the end of a differently-shaped packet
        }
    }

    /** {@code BuffStat.COMBO} is bit 53, i.e. this bit of the high 32-bit half of the mask. */
    private static final int COMBO_MASK_HIGH = (int) (0x20000000000000L >>> 32);

    private static void skipEquipList(InPacket p) {
        while ((p.readByte() & 0xFF) != 0xFF) {
            p.skip(4);
        }
    }

    /**
     * Walks a rebroadcast movement list the same way {@code AbstractMovementPacketHandler#updatePosition}
     * does and returns the position the server itself kept: only the absolute fragments (commands 0,
     * 5 and 17) carry a position the server stores; every other command only changes stance. Returns
     * {@code null} if no fragment set a position, or on a command the server itself doesn't know
     * (it would have rejected that packet rather than broadcast it).
     */
    private static Point readLastAbsolutePosition(InPacket p) {
        Point last = null;
        int count = p.readByte();
        for (int i = 0; i < count; i++) {
            int command = p.readByte();
            switch (command) {
                case 0, 5, 17 -> {
                    last = p.readPos();
                    p.skip(6 + 1 + 2);   // wobble x/y, foothold, stance, duration
                }
                case 1, 2, 6, 12, 13, 16, 18, 19, 20, 22 -> p.skip(4 + 1 + 2);
                case 3, 4, 7, 8, 9, 11 -> p.skip(8 + 1);
                case 14 -> p.skip(9);
                case 10 -> p.skip(1);
                case 15 -> p.skip(12 + 1 + 2);
                case 21 -> p.skip(3);
                default -> {
                    return last;
                }
            }
        }
        return last;
    }

    /**
     * Records the map {@code SET_FIELD} put this bot on, then clears map-scoped state (see
     * {@link #onMapChanged()}). {@code p} is positioned just after the opcode. Two shapes share the
     * opcode, told apart by the byte after the leading channel int:
     * <ul>
     *   <li>{@code getWarpToMap} (every map change): int channel, int 0, byte 0, int mapId, byte
     *       spawnPortalId, short hp, bool hasExplicitPosition, [int x, int y], long time. The first
     *       byte after the channel is therefore 0.</li>
     *   <li>{@code getCharInfo} (world entry): int channel, byte 1, byte 1, short 0, 3 random ints,
     *       long -1, byte 0, then {@code addCharStats} whose map id sits at a fixed offset <em>for a
     *       job without an SP table</em> - true of every Adventurer; an Evan's variable SP block would
     *       shift it, so the id decoded for one would be wrong.</li>
     * </ul>
     */
    public void onSetField(InPacket p) {
        try {
            p.readInt();                                 // channel
            if (p.readByte() == 1) {
                p.skip(1 + 2 + 3 * 4 + 8 + 1);
                // addCharStats: id, name(13), gender, skin, face, hair, 3 pet longs, level, job,
                // str/dex/int/luk/hp/maxhp/mp/maxmp, ap, sp, exp, fame, gachaExp - then the map id
                p.skip(4 + 13 + 1 + 1 + 4 + 4 + 3 * 8 + 1 + 2 + 8 * 2 + 2 + 2 + 4 + 2 + 4);
                selfMapId = p.readInt();
                selfSpawnPortalId = p.readByte() & 0xFF;
                selfPosition = null;
            } else {
                p.skip(3 + 1);
                selfMapId = p.readInt();
                selfSpawnPortalId = p.readByte() & 0xFF;
                p.readShort();                           // hp
                selfPosition = p.readByte() != 0 ? new Point(p.readInt(), p.readInt()) : null;
            }
        } catch (RuntimeException e) {
            selfMapId = -1;
            selfSpawnPortalId = -1;
            selfPosition = null;
        }
        onMapChanged();
    }

    public int getSelfMapId() {
        return selfMapId;
    }

    /** The WZ portal index this bot arrived at on its current map, or -1 if unknown. */
    public int getSelfSpawnPortalId() {
        return selfSpawnPortalId;
    }

    /** A player's last known position on this bot's map, or {@code null} if absent or never decoded. */
    public Point getPlayerPosition(int charId) {
        return playerPositions.get(charId);
    }

    public PartyMember getPartyMember(int charId) {
        return partyMembers.get(charId);
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
     * Clears everything scoped to the map this bot was just standing on. Called for every
     * {@code SET_FIELD} via {@link #onSetField}, since stale NPC/monster/drop object ids from the
     * previous map are actively dangerous (a stale oid could collide with a live one on the new map)
     * while simply forgetting them a moment early never is.
     */
    public void onMapChanged() {
        npcs.clear();
        monsters.clear();
        itemDrops.clear();
        // The server never sends REMOVE_PLAYER_FROM_MAP to the player who is leaving, only to those
        // staying, so everyone seen on the old map would otherwise linger here forever.
        otherPlayers.clear();
        playerPositions.clear();
        mapChangeCount.incrementAndGet();
        changeVersion.incrementAndGet();
    }

    /**
     * How many times {@link #onMapChanged()} has fired, counting the very first one (this bot's
     * initial world entry). A planner that needs to know "did I just warp somewhere new" - including a
     * warp back to the same map id, which {@link #getSelfMapId()} can't show - polls this once per tick
     * and reacts to it changing.
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
        return Set.copyOf(partyMembers.keySet());
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
