package bot;

import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Random;
import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The wiring, not the pacing: proves that {@link Action.Say} and friends no longer write a packet
 * inline, that a driver tick is what actually puts them on the wire, and that the bytes that go out
 * are the shapes the server's handlers read.
 */
class ActionExecutorSpeechTest {

    @Test
    void aSayIsQueuedRatherThanSentAndLeavesOnATick() throws Exception {
        MutableClock clock = new MutableClock(1_000_000);
        Speech speech = new Speech(clock, new Random(0));
        MapleConnection conn = mock(MapleConnection.class);
        ActionExecutor executor = new ActionExecutor(conn, new WorldState(1), speech);

        executor.execute(new Action.Say("hello there"));

        verifyNoInteractions(conn);
        assertEquals(1, speech.queued(), "the line is waiting, not sent");

        clock.advanceMs(10_000);
        executor.tick();

        verify(conn, times(1)).send(any());
        assertEquals(0, speech.queued());
    }

    @Test
    void chatGoesOutAsGeneralChatWithTheShowFlag() throws Exception {
        MutableClock clock = new MutableClock(1_000_000);
        Speech speech = new Speech(clock, new Random(0));
        MapleConnection conn = mock(MapleConnection.class);
        ActionExecutor executor = new ActionExecutor(conn, new WorldState(1), speech);

        executor.execute(new Action.Say("hi from a bot"));
        clock.advanceMs(10_000);
        executor.tick();

        InPacket in = capture(conn);
        assertEquals(RecvOpcode.GENERAL_CHAT.getValue(), in.readShort() & 0xFFFF);
        assertEquals("hi from a bot", in.readString());
        assertEquals(0, in.readByte());
    }

    @Test
    void emoteGoesOutAsFaceExpressionCarryingTheId() throws Exception {
        MutableClock clock = new MutableClock(1_000_000);
        Speech speech = new Speech(clock, new Random(0));
        MapleConnection conn = mock(MapleConnection.class);
        ActionExecutor executor = new ActionExecutor(conn, new WorldState(1), speech);

        executor.execute(new Action.Emote(3));
        clock.advanceMs(10_000);
        executor.tick();

        InPacket in = capture(conn);
        assertEquals(RecvOpcode.FACE_EXPRESSION.getValue(), in.readShort() & 0xFFFF);
        assertEquals(3, in.readInt());
    }

    @Test
    void oneTickSendsAtMostOneChatLine() throws Exception {
        MutableClock clock = new MutableClock(1_000_000);
        Speech speech = new Speech(clock, new Random(0));
        MapleConnection conn = mock(MapleConnection.class);
        ActionExecutor executor = new ActionExecutor(conn, new WorldState(1), speech);

        executor.execute(new Action.Say("first line here"));
        executor.execute(new Action.Say("second line here"));
        clock.advanceMs(60_000);

        // Same frozen instant for both ticks: the throttle floor, not the typing time, is what holds
        // the second line back - exactly what a fast-spinning driver loop must not be able to bypass.
        executor.tick();
        executor.tick();
        verify(conn, times(1)).send(any());

        clock.advanceMs(1_000);
        executor.tick();
        verify(conn, times(2)).send(any());
    }

    @Test
    void reactorHitMatchesTheServerHandlerLayout() throws Exception {
        MapleConnection conn = mock(MapleConnection.class);
        WorldState world = new WorldState(1);
        world.setSelfPosition(new Point(123, 456));
        ActionExecutor executor = new ActionExecutor(conn, world);

        executor.execute(new Action.HitReactor(9876));

        InPacket in = capture(conn);
        assertEquals(RecvOpcode.DAMAGE_REACTOR.getValue(), in.readShort() & 0xFFFF);
        assertEquals(9876, in.readInt());
        assertEquals(123, in.readInt());
        assertEquals(0, in.readShort());
        assertEquals(0, in.readInt());
        assertEquals(0, in.readInt());
    }

    /** One packet, read back exactly the way the server's InPacket would. */
    private static InPacket capture(MapleConnection conn) throws Exception {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(conn).send(captor.capture());
        return new ByteBufInPacket(Unpooled.wrappedBuffer(captor.getValue().getBytes()));
    }
}
