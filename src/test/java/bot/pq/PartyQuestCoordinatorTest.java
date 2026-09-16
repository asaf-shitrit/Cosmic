package bot.pq;

import bot.Action;
import bot.WorldState;
import bot.combat.CombatController;
import client.Character;
import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import java.awt.Point;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PartyQuestCoordinatorTest {
    @Test
    void theCoordinatorOwnsRegisteredMapsAndDisappearsOutsideThem() {
        Fixture fixture = fixture();
        PartyQuestCoordinator coordinator = fixture.coordinator(team -> PartyQuestTeam.ready(0, 2));

        Optional<Action> inQuest = coordinator.planIfActive(
                worldOn(HenesysPartyQuest.MAIN_MAP), new Point());

        assertTrue(inQuest.isPresent());
        assertInstanceOf(Action.Idle.class, inQuest.get());
        assertEquals("helping with Henesys Moon Bunny Party Quest", coordinator.activity());

        assertTrue(coordinator.planIfActive(worldOn(100000000), new Point()).isEmpty());
        assertNull(coordinator.activity());
    }

    @Test
    void anUnreadyTeamKeepsTheCompanionWaitingWithoutASession() {
        Fixture fixture = fixture();
        AtomicInteger resolutions = new AtomicInteger();
        PartyQuestCoordinator coordinator = fixture.coordinator(team -> {
            resolutions.incrementAndGet();
            return PartyQuestTeam.blocked("the party has 1 members; it needs 4-4");
        });

        Optional<Action> action = coordinator.planIfActive(worldOn(103000800), new Point());

        assertInstanceOf(Action.Idle.class, action.orElseThrow());
        assertEquals("waiting for Kerning Party Quest: the party has 1 members; it needs 4-4",
                coordinator.activity());
        assertEquals(1, resolutions.get());
    }

    @Test
    void kerningIsDrivenByTheCoordinatorWithItsOwnPolicyAndHoldsItsPuzzlePosition() {
        Fixture fixture = fixture();
        PartyQuestTeamPolicy[] seen = new PartyQuestTeamPolicy[1];
        PartyQuestCoordinator coordinator = fixture.coordinator(policy -> {
            seen[0] = policy;
            return PartyQuestTeam.ready(0, 3);
        });

        WorldState world = worldOn(103000801);
        // The session only learns which stage it is on by planning once, so the first tick may still
        // be interrupted; from then on it holds its rectangle.
        Optional<Action> first = coordinator.planIfActive(world, new Point());

        assertEquals(new PartyQuestTeamPolicy(4, 4, 3, 3), seen[0]);
        assertFalse(first.orElseThrow() instanceof Action.Idle,
                "a stage 2-4 session must plan its rectangle move, not idle");

        clearInvocations(fixture.combat);
        coordinator.planIfActive(world, new Point());
        // Stage 2-4 is the positional puzzle: a heal or buff walks the companion back to its owner,
        // which is exactly what the old map-range check in CombatController used to prevent.
        verify(fixture.combat, never()).support(any(), any(), anyBoolean());
    }

    @Test
    void supportMayInterruptASessionThatDoesNotHoldAPosition() {
        Fixture fixture = fixture();
        when(fixture.combat.support(any(), any(), anyBoolean())).thenReturn(new Action.MoveTo(new Point(7, 9)));
        PartyQuestCoordinator coordinator = fixture.coordinator(team -> PartyQuestTeam.ready(0, 2));

        Optional<Action> action = coordinator.planIfActive(worldOn(HenesysPartyQuest.MAIN_MAP), new Point());

        assertEquals(new Point(7, 9), assertInstanceOf(Action.MoveTo.class, action.orElseThrow()).target());
    }

    @Test
    void theTeamOrdinalReachesTheSessionNotTheSummonSlot() {
        // Henesys divides the six seed colours round-robin from the ordinal: with two companions,
        // ordinal 1 owns colours 1, 3, 5 and so plants colour 1 first (reactor 9108001/9102003).
        WorldState world = worldOn(HenesysPartyQuest.MAIN_MAP);
        feed(world, reactorSpawn(100, 9102003, 0, new Point(-896, 210)));
        feed(world, reactorSpawn(101, 9102002, 0, new Point(-605, 211)));

        Optional<Action> action = fixture().coordinator(team -> PartyQuestTeam.ready(1, 2))
                .planIfActive(world, new Point());

        assertEquals(new Point(-896, 210),
                assertInstanceOf(Action.MoveTo.class, action.orElseThrow()).target());
    }

    @Test
    void aCompletionMapContinuesTheEstablishedSessionWithoutReResolvingTheTeam() {
        Fixture fixture = fixture();
        AtomicInteger resolutions = new AtomicInteger();
        PartyQuestCoordinator coordinator = fixture.coordinator(team -> {
            resolutions.incrementAndGet();
            return PartyQuestTeam.ready(0, 2);
        });

        coordinator.planIfActive(worldOn(HenesysPartyQuest.MAIN_MAP), new Point());
        assertEquals(1, resolutions.get());

        WorldState clearMap = worldOn(HenesysPartyQuest.MAIN_MAP);
        changeMap(clearMap, HenesysPartyQuest.CLEAR_MAP);
        feed(clearMap, npcSpawn(800, HenesysPartyQuest.TORY, new Point(0, 0)));
        Optional<Action> action = coordinator.planIfActive(clearMap, new Point());

        assertEquals(1, resolutions.get(), "the reward map must reuse the session, not re-check the team");
        assertEquals(800, assertInstanceOf(Action.TalkToNpc.class, action.orElseThrow()).npcObjectId());
    }

    /** A coordinator whose combat is inert unless a test says otherwise. */
    private static Fixture fixture() {
        Character self = mock(Character.class);
        Character owner = mock(Character.class);
        EventInstanceManager instance = mock(EventInstanceManager.class);
        when(self.getEventInstance()).thenReturn(instance);
        CombatController combat = mock(CombatController.class);
        when(combat.recover()).thenReturn(new Action.Idle());
        when(combat.support(any(), any(), anyBoolean())).thenReturn(new Action.Idle());
        return new Fixture(self, owner, combat);
    }

    private record Fixture(Character self, Character owner, CombatController combat) {
        PartyQuestCoordinator coordinator(Function<PartyQuestTeamPolicy, PartyQuestTeam> teams) {
            return new PartyQuestCoordinator(42, "Owner", () -> self, () -> owner, combat,
                    (policy, ignoredSelf, ignoredOwner) -> teams.apply(policy));
        }
    }

    private static WorldState worldOn(int mapId) {
        WorldState world = new WorldState(1);
        changeMap(world, mapId);
        return world;
    }

    private static void changeMap(WorldState world, int mapId) {
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
    }

    private static void feed(WorldState world, OutPacket packet) {
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(packet.getBytes()));
        world.accept(in.readShort() & 0xFFFF, in);
    }

    private static OutPacket reactorSpawn(int oid, int id, int state, Point point) {
        OutPacket p = OutPacket.create(SendOpcode.REACTOR_SPAWN);
        p.writeInt(oid);
        p.writeInt(id);
        p.writeByte(state);
        p.writePos(point);
        p.writeByte(0);
        p.writeShort(0);
        return p;
    }

    private static OutPacket npcSpawn(int oid, int id, Point point) {
        OutPacket p = OutPacket.create(SendOpcode.SPAWN_NPC);
        p.writeInt(oid);
        p.writeInt(id);
        p.writePos(point);
        return p;
    }
}
