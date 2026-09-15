package bot;

import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void aDropSomeoneElsePicksUpLeavesTheMap() {
        WorldState world = new WorldState(1);
        feed(world, drop(500, 4001008));
        assertEquals(1, world.getItemDrops().size());
        feed(world, pickedUp(500, 2));
        assertTrue(world.getItemDrops().isEmpty(), "drops left: " + world.getItemDrops());
    }
}
