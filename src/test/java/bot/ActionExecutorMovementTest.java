package bot;

import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.awt.Point;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The movement fragment's cosmetic fields, asserted because a live report was exactly about them: a
 * companion that moves with stance 0 and duration 0 is moved by the server all the same, but every
 * client watching snaps it to the new spot - "they just zoom around, they are not actually walking".
 * These tests read the bytes the client reads, so the walk stance and the duration cannot regress
 * silently.
 */
class ActionExecutorMovementTest {

    private static final int STANCE_WALK_RIGHT = 2;
    private static final int STANCE_WALK_LEFT = 3;
    private static final int STANCE_STAND_RIGHT = 4;
    private static final int STANCE_STAND_LEFT = 5;
    /** opcode(2) + the 9 bytes MovePlayerHandler skips + count, command, x, y, vx, vy, foothold. */
    private static final int STANCE_OFFSET = 2 + 9 + 1 + 1 + 2 + 2 + 2 + 2 + 2;

    @Test
    void aSidewaysStepWalksAtWalkingSpeed() throws Exception {
        MapleConnection conn = mock(MapleConnection.class);
        WorldState world = new WorldState(1);
        world.setSelfPosition(new Point(100, 50));

        executor(conn, world).execute(new Action.MoveTo(new Point(220, 50)));

        InPacket in = capture(conn);
        assertEquals(RecvOpcode.MOVE_PLAYER.getValue(), in.readShort() & 0xFFFF);
        in.skip(9);                     // the header MovePlayerHandler discards
        assertEquals(1, in.readByte(), "one fragment");
        assertEquals(0, in.readByte(), "absolute move");
        assertEquals(220, in.readShort());
        assertEquals(50, in.readShort());
        in.skip(6);                     // x wobble, y wobble, foothold id
        assertEquals(STANCE_WALK_RIGHT, in.readByte(), "walking right");
        assertEquals(960, in.readShort(), "120 px at 8 ms/px = the 125 px/s the client animates");
    }

    @Test
    void movingLeftFacesLeftAndKeepsDoingSoWhenTheStepIsVertical() throws Exception {
        MapleConnection conn = mock(MapleConnection.class);
        WorldState world = new WorldState(1);
        world.setSelfPosition(new Point(300, 50));
        ActionExecutor executor = executor(conn, world);

        executor.execute(new Action.MoveTo(new Point(180, 50)));
        InPacket left = capture(conn);
        left.seek(STANCE_OFFSET);
        assertEquals(STANCE_WALK_LEFT, left.readByte(), "moving left");

        executor.execute(new Action.MoveTo(new Point(180, 200)));
        InPacket down = capture(conn);
        down.seek(STANCE_OFFSET);
        assertEquals(STANCE_STAND_LEFT, down.readByte(), "a drop is not a walk, but the facing stays");
    }

    @Test
    void aLongCatchUpStepIsCappedSoThePictureDoesNotLagForSeconds() throws Exception {
        MapleConnection conn = mock(MapleConnection.class);
        WorldState world = new WorldState(1);
        world.setSelfPosition(new Point(0, 0));

        executor(conn, world).execute(new Action.MoveTo(new Point(4000, 0)));

        InPacket in = capture(conn);
        in.seek(STANCE_OFFSET + 1);
        assertEquals(1_200, in.readShort(), "4000 px would be 32 s at walking speed");
    }

    @Test
    void aStepWithNoKnownPreviousSideStandsRatherThanWalks() throws Exception {
        MapleConnection conn = mock(MapleConnection.class);

        executor(conn, new WorldState(1)).execute(new Action.MoveTo(new Point(10, 10)));

        InPacket in = capture(conn);
        in.seek(STANCE_OFFSET);
        assertEquals(STANCE_STAND_RIGHT, in.readByte());
        assertEquals(100, in.readShort(), "nothing to interpolate from, but a duration is still sent");
    }

    private static ActionExecutor executor(MapleConnection conn, WorldState world) {
        return new ActionExecutor(conn, world, new Speech(new MutableClock(1_000_000), new Random(0)));
    }

    /** The last packet sent, read back exactly the way the server's InPacket would. */
    private static InPacket capture(MapleConnection conn) throws Exception {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(conn, atLeastOnce()).send(captor.capture());
        return new ByteBufInPacket(Unpooled.wrappedBuffer(captor.getValue().getBytes()));
    }
}
