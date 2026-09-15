package bot;

import net.opcodes.RecvOpcode;
import net.packet.OutPacket;

import java.awt.Point;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Turns an {@link Action} into the matching RecvOpcode packet and sends it over a channel
 * {@link MapleConnection}. This is the only class that knows the wire format for actions -
 * {@link Planner} implementations only ever see {@link Action} values, never packets.
 */
public class ActionExecutor {
    private final MapleConnection conn;
    private final WorldState world;

    public ActionExecutor(MapleConnection conn, WorldState world) {
        this.conn = conn;
        this.world = world;
    }

    public void execute(Action action) throws IOException {
        switch (action) {
            case Action.MoveTo moveTo -> moveTo(moveTo.target());
            case Action.TalkToNpc talk -> talkToNpc(talk.npcObjectId());
            case Action.Say say -> say(say.message());
            case Action.CreateParty ignored -> createParty();
            case Action.LeaveParty ignored -> leaveParty();
            case Action.InviteToParty invite -> inviteToParty(invite.characterName());
            case Action.AcceptPartyInvite accept -> acceptPartyInvite(accept.partyId());
            case Action.RespondToNpc respond -> respondToNpc(respond.lastMsgType(), respond.proceed(), respond.selection());
            case Action.AttackMonster attack -> attackMonster(attack.monsterObjectId(), attack.damage());
            case Action.SkillAttackMonster attack -> skillAttackMonster(attack.monsterObjectId(), attack.skillId(), attack.damage());
            case Action.MagicAttackMonster attack -> magicAttack(attack.monsterObjectId(), attack.skillId(), attack.damageLines());
            case Action.RangedAttackMonster attack -> rangedAttack(attack.monsterObjectId(), attack.skillId(), attack.damageLines());
            case Action.CastSkill cast -> castSkill(cast.skillId(), cast.skillLevel());
            case Action.UseItem item -> useItem(item.itemId(), item.slot());
            case Action.PickupItem pickup -> pickupItem(pickup.objectId());
            case Action.DropItem drop -> dropItem(drop.itemId(), drop.quantity());
            case Action.UsePortal usePortal -> usePortal(usePortal.portalName());
            case Action.ChangeChannel cc -> changeChannel(cc.channel());
            case Action.Idle ignored -> { /* nothing to send */ }
        }
    }

    /**
     * Sends a single absolute-move fragment straight to {@code target}.
     *
     * <p>{@code MovePlayerHandler} unconditionally skips its first 9 bytes and then
     * {@code AbstractMovementPacketHandler#updatePosition} trusts whatever x/y the "absolute move"
     * fragment (command 0) carries to set the player's live position - there is no foothold lookup
     * and no distance/speed check against the previous position. That's the whole reason this bot
     * doesn't need pathfinding: teleporting anywhere in one fragment is exactly as valid to the
     * server as a real walk animation would be.
     *
     * <p>There is no move acknowledgement packet, so {@link WorldState#setSelfPosition} is updated
     * optimistically the moment the packet is sent - the send succeeding is the only signal we get.
     */
    private void moveTo(Point target) throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.MOVE_PLAYER.getValue());
        p.writeBytes(new byte[9]);      // header bytes MovePlayerHandler discards via p.skip(9)
        p.writeByte(1);                  // one movement fragment follows
        p.writeByte(0);                  // fragment command 0 = absolute move
        p.writePos(target);
        p.writePos(new Point(0, 0));     // pixels-per-second wobble - cosmetic only
        p.writeShort(0);                 // foothold id - read but never validated server-side
        p.writeByte(0);                  // stance (standing)
        p.writeShort(0);                 // duration ms
        conn.send(p);
        world.setSelfPosition(target);
    }

    /**
     * NPCTalkHandler resolves the target purely via {@code getMap().getMapObject(oid)} with no
     * proximity check, so this works regardless of how close the bot's last commanded position
     * actually is to the NPC.
     */
    private void talkToNpc(int npcObjectId) throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.NPC_TALK.getValue());
        p.writeInt(npcObjectId);
        conn.send(p);
    }

    /**
     * GeneralChatHandler reads the string, then treats a leading {@code '/'} as a (server) command
     * attempt and silently drops anything that isn't a recognised one - so a real chat line must not
     * start with '/'. It also indexes {@code charAt(0)} unguarded, so an empty message would crash
     * the handler; guarded against here rather than trusting every caller to remember.
     */
    private void say(String message) throws IOException {
        if (message.isEmpty()) {
            throw new IllegalArgumentException("chat message must not be empty");
        }
        if (message.charAt(0) == '/') {
            throw new IllegalArgumentException("chat message must not start with '/' - "
                    + "GeneralChatHandler treats that as a command and drops anything unrecognised");
        }
        OutPacket p = MapleConnection.packet(RecvOpcode.GENERAL_CHAT.getValue());
        p.writeString(message);
        p.writeByte(0);                  // "show" flag - bubble style, GeneralChatHandler just forwards it
        conn.send(p);
    }

    /** {@code PartyOperationHandler} operation 1 - no further payload. */
    private void createParty() throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.PARTY_OPERATION.getValue());
        p.writeByte(1);
        conn.send(p);
    }

    /** {@code PartyOperationHandler} operation 2 - no further payload. */
    private void leaveParty() throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.PARTY_OPERATION.getValue());
        p.writeByte(2);
        conn.send(p);
    }

    /**
     * {@code PartyOperationHandler} operation 4. The handler looks {@code characterName} up by name
     * in this channel's player storage and creates a party for this bot first if it doesn't have one
     * yet - so inviting works even before an explicit {@link Action.CreateParty}.
     */
    private void inviteToParty(String characterName) throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.PARTY_OPERATION.getValue());
        p.writeByte(4);
        p.writeString(characterName);
        conn.send(p);
    }

    /** {@code PartyOperationHandler} operation 3 - joins the party {@code partyId} invited this bot to. */
    private void acceptPartyInvite(int partyId) throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.PARTY_OPERATION.getValue());
        p.writeByte(3);
        p.writeInt(partyId);
        conn.send(p);
    }

    /**
     * {@code NPCMoreTalkHandler}: byte lastMsgType, byte action (1 = proceed/yes, 0 = cancel/no), then
     * only for a "simple" numbered-list message (type 4) a trailing int selection - the handler reads
     * one only if {@code p.available() >= 4}, so a type that doesn't need one (0 = OK/Next, 1 = Yes/No)
     * must not get one written, or the handler would misread it as a selection on a type that has none.
     */
    private void respondToNpc(int lastMsgType, boolean proceed, Integer selection) throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.NPC_TALK_MORE.getValue());
        p.writeByte(lastMsgType);
        p.writeByte(proceed ? 1 : 0);
        if (selection != null) {
            p.writeInt(selection);
        }
        conn.send(p);
    }

    /**
     * A one-hit, skill-0 melee swing declaring {@code damage} outright. Mirrors
     * {@code AbstractDealDamageHandler#parseDamage}'s melee (non-ranged, non-magic) field order for
     * numAttacked=1/numDamage=1: the packed count byte, the skill id (0 - the whole MP-cost/mob-count
     * validation block in {@code applyAttack} is gated behind {@code skill != 0}), a fixed 8-byte gap,
     * display/direction/stance, the melee-only discarded byte + speed, another 4-byte gap, then per
     * target the object id, a 4-byte gap, two throwaway positions (read but never stored - see
     * {@code parseDamage}), a delay short, the declared damage int, and a final 4-byte gap.
     */
    private void attackMonster(int monsterObjectId, int damage) throws IOException {
        sendMeleeAttack(monsterObjectId, 0, damage);
    }

    private void skillAttackMonster(int monsterObjectId, int skillId, int damage) throws IOException {
        sendMeleeAttack(monsterObjectId, skillId, damage);
    }

    /** The fields before the target are the melee shape parsed by AbstractDealDamageHandler. */
    private void sendMeleeAttack(int monsterObjectId, int skillId, int damage) throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.CLOSE_RANGE_ATTACK.getValue());
        p.writeByte(0);                  // discarded leading byte
        p.writeByte(0x11);               // numAttacked=1 (high nibble), numDamage=1 (low nibble)
        p.writeInt(skillId);
        p.writeBytes(new byte[8]);       // discarded (would be a charge value for specific skills)
        p.writeByte(0);                  // display
        p.writeByte(0);                  // direction
        p.writeByte(0);                  // stance
        p.writeByte(0);                  // discarded (melee branch's extra byte before speed)
        p.writeByte(0);                  // speed
        p.writeBytes(new byte[4]);       // discarded
        p.writeInt(monsterObjectId);
        p.writeBytes(new byte[4]);       // discarded
        p.writePos(new Point(0, 0));     // curPos - read but never stored server-side
        p.writePos(new Point(0, 0));     // nextPos - ditto
        p.writeShort(0);                 // delay
        p.writeInt(damage);
        p.writeBytes(new byte[4]);       // trailing gap parseDamage always skips after the damage line(s)
        conn.send(p);
    }

    /**
     * {@code MagicDamageHandler} parses with {@code parseDamage(p, chr, ranged=false, magic=true)}, and the
     * magic flag changes nothing in the layout - only the ceiling it computes - so a spell is the melee
     * shape under the {@code MAGIC_ATTACK} opcode, with one damage int per line. The skill must be a real,
     * learned attack spell: the handler calls {@code SkillFactory.getSkill(attack.skill).getEffect(...)}
     * unconditionally, so a skill-0 "magic" attack would throw. Energy Bolt and Magic Claw have no charge
     * int (only Big Bang and the breath skills do).
     */
    private void magicAttack(int monsterObjectId, int skillId, List<Integer> damageLines) throws IOException {
        if (skillId <= 0) {
            throw new IllegalArgumentException("a magic attack needs an attack spell");
        }
        OutPacket p = attackHeader(RecvOpcode.MAGIC_ATTACK, skillId, damageLines.size());
        p.writeByte(0);                  // discarded (the non-ranged branch's byte before speed)
        p.writeByte(0);                  // speed
        p.writeBytes(new byte[4]);       // discarded
        writeTarget(p, monsterObjectId, damageLines);
        conn.send(p);
    }

    /**
     * {@code RangedAttackHandler} parses with {@code parseDamage(p, chr, ranged=true, magic=false)}, whose
     * ranged branch is longer than melee's: after display/direction/stance comes a discarded byte, speed,
     * a discarded byte, the range direction (used for the broadcast of Hurricane-style skills only), then
     * 7 discarded bytes, then the usual per-target block.
     *
     * <p>Those 7 bytes are where a real client names its projectile slot, and the server ignores them:
     * the handler walks the USE inventory from slot 1 for the first stack that fits the weapon (arrows
     * {@code 206xxxx} for a bow) and holds at least the skill's {@code bulletCount}, and removes
     * {@code bulletConsume} (else {@code bulletCount}) from that stack - Double Shot takes 2 arrows, a
     * plain shot or Arrow Blow 1. With no such stack it neither broadcasts nor applies the damage, silently.
     * It also dereferences the weapon slot unguarded, so the character must have a weapon on.
     */
    private void rangedAttack(int monsterObjectId, int skillId, List<Integer> damageLines) throws IOException {
        OutPacket p = attackHeader(RecvOpcode.RANGED_ATTACK, skillId, damageLines.size());
        p.writeByte(0);                  // discarded
        p.writeByte(0);                  // speed
        p.writeByte(0);                  // discarded
        p.writeByte(0);                  // range direction
        p.writeBytes(new byte[7]);       // discarded: the client's projectile slot fields, see above
        writeTarget(p, monsterObjectId, damageLines);
        conn.send(p);
    }

    /** {@code parseDamage}'s common start, for one target taking {@code lines} damage lines. */
    private static OutPacket attackHeader(RecvOpcode opcode, int skillId, int lines) {
        if (lines < 1 || lines > 15) {
            throw new IllegalArgumentException("an attack carries 1..15 damage lines, got " + lines);
        }
        OutPacket p = MapleConnection.packet(opcode.getValue());
        p.writeByte(0);                  // discarded leading byte
        p.writeByte(0x10 | lines);       // numAttacked=1 (high nibble), numDamage=lines (low nibble)
        p.writeInt(skillId);
        p.writeBytes(new byte[8]);       // discarded
        p.writeByte(0);                  // display
        p.writeByte(0);                  // direction
        p.writeByte(0);                  // stance
        return p;
    }

    /**
     * One target: object id, 4 discarded bytes, two positions that are read and dropped, the delay, one
     * int per damage line, and 4 trailing bytes - skipped for every skill, since the condition guarding
     * that skip ({@code skill != A || skill != B ...}) is always true.
     */
    private static void writeTarget(OutPacket p, int monsterObjectId, List<Integer> damageLines) {
        p.writeInt(monsterObjectId);
        p.writeBytes(new byte[4]);
        p.writePos(new Point(0, 0));
        p.writePos(new Point(0, 0));
        p.writeShort(0);
        for (int damage : damageLines) {
            p.writeInt(damage);
        }
        p.writeBytes(new byte[4]);
    }

    private void castSkill(int skillId, int level) throws IOException {
        if (level <= 0) {
            throw new IllegalArgumentException("cannot cast unlearned skill " + skillId);
        }
        OutPacket p = MapleConnection.packet(RecvOpcode.SPECIAL_MOVE.getValue());
        p.writeInt(0); // client tick; SpecialMoveHandler only consumes the field
        p.writeInt(skillId);
        p.writeByte(level);
        conn.send(p);
    }

    private void useItem(int itemId, int slot) throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.USE_ITEM.getValue());
        p.writeInt(0);
        p.writeShort(slot);
        p.writeInt(itemId);
        conn.send(p);
    }

    /**
     * {@code ItemPickupHandler} validates distance using {@code chr.getPosition()} (the server-side
     * position from this bot's last {@link Action.MoveTo}, not anything in this packet) against the
     * drop's own position, so the claimed position field here is filler - a planner must move the bot
     * within 800/600 px of the drop first for this to actually succeed.
     */
    private void pickupItem(int objectId) throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.ITEM_PICKUP.getValue());
        p.writeBytes(new byte[4]);       // timestamp - read but never validated
        p.writeByte(0);
        p.writePos(new Point(0, 0));     // claimed position - unused, see above
        p.writeInt(objectId);
        conn.send(p);
    }

    /**
     * Drops {@code quantity} of {@code itemId} from the ETC inventory. {@code ItemMoveHandler} routes
     * to {@code InventoryManipulator.drop} when {@code action == 0}, keyed by inventory slot rather
     * than item id - the slot is resolved from {@link WorldState#getEtcSlot}, which is kept in sync
     * from {@code INVENTORY_OPERATION} broadcasts.
     */
    private void dropItem(int itemId, int quantity) throws IOException {
        Optional<Integer> slot = world.getEtcSlot(itemId);
        if (slot.isEmpty()) {
            throw new IllegalStateException("cannot drop item " + itemId + " - not held in the ETC inventory");
        }
        OutPacket p = MapleConnection.packet(RecvOpcode.ITEM_MOVE.getValue());
        p.writeBytes(new byte[4]);       // timestamp - ItemMoveHandler discards via p.skip(4)
        p.writeByte(4);                  // InventoryType.ETC
        p.writeShort(slot.get());
        p.writeShort(0);                 // action == 0 selects InventoryManipulator.drop
        p.writeShort(quantity);
        conn.send(p);
    }

    /** {@code ChangeChannelHandler}: byte zero-indexed channel, int (read and ignored). */
    private void changeChannel(int channel) throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.CHANGE_CHANNEL.getValue());
        p.writeByte(channel - 1);
        p.writeInt(0);
        conn.send(p);
    }

    /**
     * {@code ChangeMapHandler}: byte (1 = respawn-from-death, 0 = regular), int targetMapId (only
     * honoured for a GM's direct warp - a real portal transition is resolved from
     * {@code portalName} looked up on the bot's current map, so -1 here is exactly what a real client
     * sends for a normal portal), string portalName, a discarded byte, the wheel-of-fortune flag, and
     * the GM chase flag (this bot is never GM, so always 0 with no trailing chase coordinates).
     */
    private void usePortal(String portalName) throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.CHANGE_MAP.getValue());
        p.writeByte(0);
        p.writeInt(-1);
        p.writeString(portalName);
        p.writeByte(0);
        p.writeByte(0);                  // wheel of fortune
        p.writeByte(0);                  // GM chase flag
        conn.send(p);
    }
}
