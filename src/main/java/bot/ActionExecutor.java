package bot;

import net.opcodes.RecvOpcode;
import net.packet.OutPacket;

import java.awt.Point;
import java.io.IOException;

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
}
