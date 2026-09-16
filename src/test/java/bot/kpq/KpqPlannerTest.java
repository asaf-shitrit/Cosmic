package bot.kpq;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bot.Action;
import bot.WorldState;
import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.awt.Point;

class KpqPlannerTest {
    @Test
    void recognizesOnlyTheFiveActualInstanceMaps() {
        assertTrue(KpqPlanner.isStageMap(103000800));
        assertTrue(KpqPlanner.isStageMap(103000804));
        assertFalse(KpqPlanner.isStageMap(103000000));
        assertFalse(KpqPlanner.isStageMap(103000805));
        assertFalse(KpqPlanner.isStageMap(-1));
    }

    @Test
    void onlyThePositionalStagesHoldPosition() {
        KpqPlanner positional = planner();
        assertFalse(positional.holdsPosition(), "not in a stage yet");
        positional.plan(worldOn(103000801), new Point());
        assertTrue(positional.holdsPosition(), "stage 2 is the puzzle: the companion must not leave it");

        KpqPlanner farming = planner();
        farming.plan(worldOn(103000800), new Point());
        assertFalse(farming.holdsPosition(), "stage 1 is combat and farming, not a fixed spot");
    }

    private static KpqPlanner planner() {
        return KpqPlanner.summonedMember(0, 42, "Owner", (world, position, oid) -> new Action.Idle());
    }

    private static WorldState worldOn(int mapId) {
        WorldState world = new WorldState(1);
        OutPacket p = OutPacket.create(SendOpcode.SET_FIELD);
        p.writeInt(0);
        p.writeInt(0);
        p.writeByte(0);
        p.writeInt(mapId);
        p.writeByte(0);
        p.writeShort(100);
        p.writeBool(false);
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(p.getBytes()));
        in.readShort();
        world.onSetField(in);
        return world;
    }
}
