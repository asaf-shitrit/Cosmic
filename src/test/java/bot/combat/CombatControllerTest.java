package bot.combat;

import bot.Action;
import bot.WorldState;
import client.Character;
import client.Job;
import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The leash is the only thing standing between a PQ session and ordinary field combat: a session
 * grants {@link CombatScope#FULL_MAP} because its map is one instance, while a companion assisting
 * its owner on a shared field map stays within {@link CombatController#OWNER_LEASH}.
 */
class CombatControllerTest {
    @Test
    void fullMapScopeReachesAMonsterOutsideTheOwnerLeash() {
        WorldState world = worldWithMonsterAt(900, 9300001, new Point(1000, 0));
        CombatController controller = controller();

        assertInstanceOf(Action.Idle.class,
                controller.attackTarget(world, new Point(0, 0), 900, CombatScope.NEAR_OWNER));
        assertEquals(new Point(1000, 0), assertInstanceOf(Action.MoveTo.class,
                controller.attackTarget(world, new Point(0, 0), 900, CombatScope.FULL_MAP)).target());
    }

    @Test
    void fieldAssistStaysOwnerLeashed() {
        WorldState world = worldWithMonsterAt(900, 9300001, new Point(1000, 0));
        CombatController controller = controller();

        Optional<Action> assist = controller.assist(world, new Point(0, 0));

        assertTrue(assist.isEmpty(), "a monster past the leash must not be assisted: " + assist);
    }

    private static CombatController controller() {
        MapleMap map = mock(MapleMap.class);
        when(map.isTown()).thenReturn(false);
        Character self = mock(Character.class);
        Character owner = mock(Character.class);
        when(self.getMap()).thenReturn(map);
        when(owner.getMap()).thenReturn(map);
        when(self.getJob()).thenReturn(Job.BEGINNER);
        when(self.isAlive()).thenReturn(true);
        when(owner.isAlive()).thenReturn(true);
        when(self.getPartyId()).thenReturn(5);
        when(owner.getPartyId()).thenReturn(5);
        when(owner.getPosition()).thenReturn(new Point(0, 0));
        return new CombatController(() -> self, () -> owner);
    }

    private static WorldState worldWithMonsterAt(int oid, int monsterId, Point position) {
        WorldState world = new WorldState(1);
        OutPacket p = OutPacket.create(SendOpcode.SPAWN_MONSTER);
        p.writeInt(oid);
        p.writeByte(0);
        p.writeInt(monsterId);
        p.writeBytes(new byte[16]);
        p.writePos(position);
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(p.getBytes()));
        world.accept(in.readShort() & 0xFFFF, in);
        return world;
    }
}
