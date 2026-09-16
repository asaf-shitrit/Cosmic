package bot.pq;

import bot.Action;
import bot.MutableClock;
import bot.WorldState;
import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HenesysPartyQuestTest {
    @Test
    void registryRecognizesBothQuestsAndTheirPolicies() {
        PartyQuestDefinition kerning = PartyQuestRegistry.forMap(103000800).orElseThrow();
        PartyQuestDefinition henesys = PartyQuestRegistry.forMap(HenesysPartyQuest.MAIN_MAP).orElseThrow();

        assertEquals("kerning", kerning.id());
        assertEquals(new PartyQuestTeamPolicy(4, 4, 3, 3), kerning.teamPolicy());
        assertEquals("henesys-moon-bunny", henesys.id());
        assertEquals(new PartyQuestTeamPolicy(3, 6, 2, 3), henesys.teamPolicy());
        assertTrue(PartyQuestRegistry.forMap(100000000).isEmpty());
    }

    @Test
    void twoCompanionsDivideTheSixSeedColorsDeterministically() {
        WorldState firstWorld = worldOn(HenesysPartyQuest.MAIN_MAP);
        WorldState secondWorld = worldOn(HenesysPartyQuest.MAIN_MAP);
        feed(firstWorld, reactorSpawn(100, 9102002, 0, new Point(-605, 211)));
        feed(secondWorld, reactorSpawn(101, 9102003, 0, new Point(-896, 210)));
        MutableClock clock = new MutableClock(1_000);

        HenesysPartyQuest.Session first = session(0, 2, clock);
        HenesysPartyQuest.Session second = session(1, 2, clock);

        assertEquals(new Point(-605, 211), assertInstanceOf(Action.MoveTo.class,
                first.plan(firstWorld, new Point())).target());
        assertEquals(new Point(-896, 210), assertInstanceOf(Action.MoveTo.class,
                second.plan(secondWorld, new Point())).target());
        clock.advanceMs(800);
        assertEquals(100, assertInstanceOf(Action.HitReactor.class,
                first.plan(firstWorld, new Point(-605, 211))).objectId());
        assertEquals(101, assertInstanceOf(Action.HitReactor.class,
                second.plan(secondWorld, new Point(-896, 210))).objectId());
    }

    @Test
    void plantingDropsOneSeedAndWaitsForTheFlowerActivation() {
        WorldState world = worldOn(HenesysPartyQuest.MAIN_MAP);
        feed(world, reactorSpawn(200, 9108000, 0, new Point(4, -690)));
        feed(world, reactorSpawn(203, 9102005, 0, new Point(141, 210)));
        feed(world, addEtc(1, 4001095, 3));
        MutableClock clock = new MutableClock(1_000);
        HenesysPartyQuest.Session session = session(0, 3, clock);

        assertEquals(new Point(4, -690), assertInstanceOf(Action.MoveTo.class,
                session.plan(world, new Point())).target());
        clock.advanceMs(800);
        Action.DropItem drop = assertInstanceOf(Action.DropItem.class,
                session.plan(world, new Point(4, -690)));
        assertEquals(4001095, drop.itemId());
        assertEquals(1, drop.quantity());

        clock.advanceMs(1_000);
        assertInstanceOf(Action.Idle.class, session.plan(world, new Point(4, -690)));
        feed(world, reactorHit(200, 1, new Point(4, -690)));
        assertEquals(new Point(141, 210), assertInstanceOf(Action.MoveTo.class,
                session.plan(world, new Point(4, -690))).target());
    }

    @Test
    void defenseIgnoresPlayerDroppedCakesAndNeverTargetsTheBunny() {
        WorldState world = worldOn(HenesysPartyQuest.MAIN_MAP);
        feed(world, monsterSpawn(700, HenesysPartyQuest.MOON_BUNNY, new Point(-183, -433)));
        feed(world, itemDrop(300, HenesysPartyQuest.RICE_CAKE, 99, true, new Point(1, 1)));
        feed(world, itemDrop(301, HenesysPartyQuest.RICE_CAKE, 700, false, new Point(2, 2)));
        AtomicInteger attacked = new AtomicInteger(-1);
        MutableClock clock = new MutableClock(1_000);
        HenesysPartyQuest.Session session = new HenesysPartyQuest.Session(
                new PartyQuestContext(0, 42, "Owner", 2),
                (w, p, oid) -> { attacked.set(oid); return new Action.AttackMonster(oid, 1); },
                clock::millis);

        assertEquals(new Point(2, 2), assertInstanceOf(Action.MoveTo.class,
                session.plan(world, new Point())).target());
        clock.advanceMs(800);
        assertEquals(301, assertInstanceOf(Action.PickupItem.class,
                session.plan(world, new Point(2, 2))).objectId());

        WorldState combatWorld = worldOn(HenesysPartyQuest.MAIN_MAP);
        feed(combatWorld, monsterSpawn(700, HenesysPartyQuest.MOON_BUNNY, new Point(-183, -433)));
        feed(combatWorld, monsterSpawn(701, 9300062, new Point(100, 100)));
        HenesysPartyQuest.Session combatSession = new HenesysPartyQuest.Session(
                new PartyQuestContext(0, 42, "Owner", 2),
                (w, p, oid) -> { attacked.set(oid); return new Action.AttackMonster(oid, 1); },
                clock::millis);
        combatSession.plan(combatWorld, new Point());
        assertEquals(701, attacked.get());
    }

    @Test
    void clearMapStartsTheRewardExitDialogue() {
        WorldState world = worldOn(HenesysPartyQuest.CLEAR_MAP);
        feed(world, npcSpawn(800, HenesysPartyQuest.TORY, new Point(0, 0)));
        HenesysPartyQuest.Session session = session(0, 2, new MutableClock(3_001));

        assertEquals(800, assertInstanceOf(Action.TalkToNpc.class,
                session.plan(world, new Point())).npcObjectId());
    }

    private static HenesysPartyQuest.Session session(int ordinal, int count, MutableClock clock) {
        return new HenesysPartyQuest.Session(new PartyQuestContext(ordinal, 42, "Owner", count),
                (w, p, oid) -> new Action.AttackMonster(oid, 1), clock::millis);
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

    private static OutPacket reactorHit(int oid, int state, Point point) {
        OutPacket p = OutPacket.create(SendOpcode.REACTOR_HIT);
        p.writeInt(oid);
        p.writeByte(state);
        p.writePos(point);
        p.writeByte(0);
        p.writeShort(0);
        p.writeByte(5);
        return p;
    }

    private static OutPacket monsterSpawn(int oid, int id, Point point) {
        OutPacket p = OutPacket.create(SendOpcode.SPAWN_MONSTER);
        p.writeInt(oid);
        p.writeByte(0);
        p.writeInt(id);
        p.writeBytes(new byte[16]);
        p.writePos(point);
        return p;
    }

    private static OutPacket itemDrop(int oid, int itemId, int dropperOid,
                                      boolean playerDrop, Point point) {
        OutPacket p = OutPacket.create(SendOpcode.DROP_ITEM_FROM_MAPOBJECT);
        p.writeByte(1);
        p.writeInt(oid);
        p.writeBool(false);
        p.writeInt(itemId);
        p.writeInt(0);
        p.writeByte(2);
        p.writePos(point);
        p.writeInt(dropperOid);
        p.writePos(point);
        p.writeShort(0);
        p.writeLong(0);
        p.writeByte(playerDrop ? 0 : 1);
        return p;
    }

    private static OutPacket addEtc(int slot, int itemId, int quantity) {
        OutPacket p = OutPacket.create(SendOpcode.INVENTORY_OPERATION);
        p.writeBool(true);
        p.writeByte(1);
        p.writeByte(0);
        p.writeByte(4);
        p.writeShort(slot);
        p.writeByte(2);
        p.writeInt(itemId);
        p.writeBool(false);
        p.writeLong(0);
        p.writeShort(quantity);
        p.writeString("");
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
