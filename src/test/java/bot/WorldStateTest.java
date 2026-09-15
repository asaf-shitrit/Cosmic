package bot;

import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldStateTest {
    private static void feed(WorldState world, OutPacket packet) {
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(packet.getBytes()));
        world.accept(in.readShort() & 0xFFFF, in);
    }

    /** The layout of PacketCreator.dropItemFromMapObject for an item, up to what WorldState reads. */
    private static OutPacket drop(int oid, int itemId) {
        OutPacket p = OutPacket.create(SendOpcode.DROP_ITEM_FROM_MAPOBJECT);
        p.writeByte(1);
        p.writeInt(oid);
        p.writeBool(false);
        p.writeInt(itemId);
        p.writeInt(0);
        p.writeByte(2);
        p.writePos(new Point(10, 20));
        return p;
    }

    /** PacketCreator.removeItemFromMap: byte animation, int oid, and for a pickup the picker's id. */
    private static OutPacket pickedUp(int oid, int byCharId) {
        OutPacket p = OutPacket.create(SendOpcode.REMOVE_ITEM_FROM_MAP);
        p.writeByte(2);
        p.writeInt(oid);
        p.writeInt(byCharId);
        return p;
    }

    /**
     * The shape of PacketCreator.spawnPlayerMapObject that {@code WorldState} walks: the leading fields
     * matter (level, name, guild name), everything between is filler the bot skips without looking at it.
     */
    private static OutPacket spawnPlayer(int charId, String name, Point position) {
        OutPacket p = OutPacket.create(SendOpcode.SPAWN_PLAYER);
        p.writeInt(charId);
        p.writeByte(30);                  // level
        p.writeString(name);
        p.writeString("");                // guild name, empty for a guildless character
        p.writeBytes(new byte[6]);        // guild crest
        p.writeBytes(new byte[8]);        // buff mask block
        p.writeInt(0);                    // morph flag
        p.writeInt(0);                    // buff mask, high half
        p.writeBytes(new byte[4]);
        p.writeBytes(new byte[108]);      // character look
        p.writeBytes(new byte[2]);
        p.writeBytes(new byte[11]);
        p.writeByte(0xFF);                // empty equip list
        p.writeByte(0xFF);                // empty equip list
        p.writeBytes(new byte[16]);
        p.writeBytes(new byte[12]);
        p.writePos(position);
        return p;
    }

    /** PacketCreator.removePlayerFromMap: int charId. */
    private static OutPacket removedPlayer(int charId) {
        OutPacket p = OutPacket.create(SendOpcode.REMOVE_PLAYER_FROM_MAP);
        p.writeInt(charId);
        return p;
    }

    /** PacketCreator.getChatText: int cidfrom, bool gm, string text (the trailing show byte is not read). */
    private static OutPacket chat(int from, String text) {
        OutPacket p = OutPacket.create(SendOpcode.CHATTEXT);
        p.writeInt(from);
        p.writeBool(false);
        p.writeString(text);
        p.writeByte(0);                   // show
        return p;
    }

    /** PacketCreator.killMonster: int oid and the animation byte twice. */
    private static OutPacket monsterRemoved(int oid, int animation) {
        OutPacket p = OutPacket.create(SendOpcode.KILL_MONSTER);
        p.writeInt(oid);
        p.writeByte(animation);
        p.writeByte(animation);
        return p;
    }

    /**
     * A monster leaving this client's view is announced exactly like a death, so the packet cannot be
     * used to tell that anything was killed. Kept as a test because the trap is easy to walk into: an
     * earlier version of the harness counted these as kills and reported fights that never happened.
     */
    @Test
    void aMonsterLeavingViewCarriesNothingThatProvesItDied() {
        WorldState world = new WorldState(1);

        feed(world, monsterRemoved(500, 0));
        feed(world, monsterRemoved(500, 1));

        assertEquals(0, world.getMonsters().size(), "it is gone from the map either way");
    }

    @Test
    void aDropSomeoneElsePicksUpLeavesTheMap() {
        WorldState world = new WorldState(1);
        feed(world, drop(500, 4001008));
        assertEquals(1, world.getItemDrops().size());
        feed(world, pickedUp(500, 2));
        assertTrue(world.getItemDrops().isEmpty(), "drops left: " + world.getItemDrops());
    }

    @Test
    void aChatLineIsRememberedWithTheNameOfWhoSaidIt() {
        WorldState world = new WorldState(1);
        feed(world, spawnPlayer(2, "humanpal", new Point(100, 200)));

        feed(world, chat(2, "hey Marigold"));

        WorldState.ChatSince since = world.chatSince(0);
        assertEquals(1, since.lines().size(), "lines: " + since.lines());
        WorldState.ChatLine line = since.lines().get(0);
        assertEquals("humanpal", line.speaker(), "a bot that cannot name the speaker cannot tell if it was addressed");
        assertEquals("hey Marigold", line.text());
        assertEquals("humanpal", world.nameOf(2));
    }

    @Test
    void theSameChatIsNeverHandedBackTwice() {
        WorldState world = new WorldState(1);
        feed(world, spawnPlayer(2, "humanpal", new Point(100, 200)));
        feed(world, chat(2, "first"));

        WorldState.ChatSince consumed = world.chatSince(0);

        assertTrue(world.chatSince(consumed.latestSeq()).lines().isEmpty(),
                "a planner polls every tick - re-reading the same line would answer it forever");
        feed(world, chat(2, "second"));
        assertEquals(1, world.chatSince(consumed.latestSeq()).lines().size());
    }

    @Test
    void aBotsOwnChatIsNotHeardBack() {
        WorldState world = new WorldState(1);
        feed(world, spawnPlayer(2, "humanpal", new Point(100, 200)));

        feed(world, chat(1, "my own line"));          // the server broadcasts our chat to our own map too

        assertTrue(world.chatSince(0).lines().isEmpty(), "a bot answering itself would loop forever");
    }

    @Test
    void chatFromSomeoneWhoseSpawnNeverDecodedFallsBackToTheirId() {
        WorldState world = new WorldState(1);

        feed(world, chat(7, "hello?"));

        assertEquals("#7", world.chatSince(0).lines().get(0).speaker());
    }

    @Test
    void leavingTheMapForgetsWhoWasThereAndWhatWasSaid() {
        WorldState world = new WorldState(1);
        feed(world, spawnPlayer(2, "humanpal", new Point(100, 200)));
        feed(world, chat(2, "see you around"));

        feed(world, removedPlayer(2));
        world.onMapChanged();

        assertNull(world.nameOf(2));
        assertTrue(world.chatSince(0).lines().isEmpty(), "chat said on the map we left is not something to answer");
    }
}
