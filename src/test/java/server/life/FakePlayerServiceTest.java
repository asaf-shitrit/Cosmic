package server.life;

import org.junit.jupiter.api.Test;
import server.maps.FootholdTree;
import server.maps.MapObjectType;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where {@link FakePlayerService} puts the fake players it scatters over a training field.
 *
 * <p>The map is stubbed rather than loaded out of Map.wz: loading a real one drags in the WZ
 * providers and the database, and what is under test is only how the service reads map data back
 * out - mob spawn points for the grinders, and the spot furthest from them for the resters. Only a
 * live client can show how any of it looks.
 */
class FakePlayerServiceTest {
    private static final int MAP_MIN_X = 0;
    private static final int MAP_MAX_X = 1000;
    private static final int MAP_TOP_Y = 200;
    private static final int GROUND_Y = 300;

    /**
     * A map with flat ground across its whole width and mob spawn points at {@code spawnXs}. The
     * spawn lookup answers by nearest x, the way {@link MapleMap#findClosestSpawnpoint} does.
     */
    private static MapleMap flatMap(int... spawnXs) {
        FootholdTree footholds = mock(FootholdTree.class);
        when(footholds.getMinDropX()).thenReturn(MAP_MIN_X);
        when(footholds.getMaxDropX()).thenReturn(MAP_MAX_X);
        when(footholds.getY1()).thenReturn(MAP_TOP_Y);

        MapleMap map = mock(MapleMap.class);
        when(map.getFootholds()).thenReturn(footholds);
        when(map.getGroundBelow(any(Point.class))).thenAnswer(call -> {
            Point from = call.getArgument(0);
            return new Point(from.x, GROUND_Y);
        });
        when(map.findClosestSpawnpoint(any(Point.class))).thenAnswer(call -> {
            if (spawnXs.length == 0) {
                return null;
            }
            int fromX = ((Point) call.getArgument(0)).x;
            int nearest = spawnXs[0];
            for (int x : spawnXs) {
                if (Math.abs(x - fromX) < Math.abs(nearest - fromX)) {
                    nearest = x;
                }
            }
            SpawnPoint spawn = mock(SpawnPoint.class);
            when(spawn.getPosition()).thenReturn(new Point(nearest, GROUND_Y));
            return spawn;
        });
        return map;
    }

    private static Point placement(MapleMap map, FakePlayerActivity activity) {
        return FakePlayerService.getInstance().chooseSpawnPosition(map, activity, MAP_MIN_X, MAP_MAX_X);
    }

    @Test
    void grindingPlayersStandWhereTheMobsSpawn() {
        MapleMap map = flatMap(700);

        for (int i = 0; i < 8; i++) {
            Point spot = placement(map, FakePlayerActivity.GRINDING);
            assertEquals(700, spot.x, "a grinder should be placed on a mob spawn, not wherever it fell");
            assertEquals(GROUND_Y, spot.y, "and on the floor the spawn point sits on");
        }
    }

    @Test
    void restingPlayersSitClearOfTheMobs() {
        MapleMap map = flatMap(0);

        Point spot = placement(map, FakePlayerActivity.RESTING);

        // A rester takes the furthest of a dozen sampled spots, so landing within 200px of a spawn
        // it is sampling around is a ~1 in 200 million event. Anything that stops resters reading
        // the spawn points turns this red.
        assertTrue(spot.x > 200, "a rester should not be parked on the mobs, was at x=" + spot.x);
    }

    @Test
    void fieldsWithoutMobSpawnsStillGetPlacements() {
        MapleMap map = flatMap();

        for (FakePlayerActivity activity : new FakePlayerActivity[]{
                FakePlayerActivity.GRINDING, FakePlayerActivity.RESTING}) {
            Point spot = placement(map, activity);
            assertTrue(spot.x >= MAP_MIN_X && spot.x < MAP_MAX_X,
                    activity + " should still be scattered over the map, was at x=" + spot.x);
        }
    }

    /**
     * A map with a roof, a platform and the ground, the way a real field is built: several stacked
     * surfaces, each covering only part of the width. {@code getGroundBelow} answers with the highest
     * surface at or below the point it is given, and throws when there is none - which is what
     * {@code MapleMap} does, since it dereferences the foothold it looked for.
     *
     * @param roofX2 how far the roof reaches from x=0; it is a band along the top, not the whole map
     */
    private static MapleMap stackedMap(int roofX2, int platformX1, int platformX2, int roofY, int platformY) {
        FootholdTree footholds = mock(FootholdTree.class);
        when(footholds.getMinDropX()).thenReturn(MAP_MIN_X);
        when(footholds.getMaxDropX()).thenReturn(MAP_MAX_X);
        when(footholds.getY1()).thenReturn(roofY);          // highest surface, as MapleMap reports it

        MapleMap map = mock(MapleMap.class);
        when(map.getFootholds()).thenReturn(footholds);
        when(map.getGroundBelow(any(Point.class))).thenAnswer(call -> {
            Point from = call.getArgument(0);
            int looking = from.y - 14;                       // getGroundBelow looks 14px above its point
            Integer surface = null;
            if (from.x <= roofX2 && roofY >= looking) {
                surface = roofY;
            }
            if (surface == null && from.x >= platformX1 && from.x <= platformX2 && platformY >= looking) {
                surface = platformY;
            }
            if (surface == null && GROUND_Y >= looking) {
                surface = GROUND_Y;
            }
            if (surface == null) {
                throw new IllegalStateException("no foothold below " + from);
            }
            return new Point(from.x, surface - 1);
        });
        when(map.findClosestSpawnpoint(any(Point.class))).thenAnswer(call -> {
            SpawnPoint spawn = mock(SpawnPoint.class);
            when(spawn.getPosition()).thenAnswer(ignored -> call.getArgument(0));
            return spawn;
        });
        return map;
    }

    /**
     * The bug this guards: placement used to ask for the map's highest foothold, which on a stacked
     * map is a roof or an upper floor. Fake players were left standing in the sky above the map they
     * were meant to populate.
     */
    @Test
    void townTrafficStandsOnTheGroundNotOnARoof() {
        MapleMap map = stackedMap(400, 500, 800, MAP_TOP_Y, 250);

        for (FakePlayerActivity activity : new FakePlayerActivity[]{
                FakePlayerActivity.TRAVELLING, FakePlayerActivity.VENDING, FakePlayerActivity.BROWSING,
                FakePlayerActivity.WAITING, FakePlayerActivity.CLEARING}) {
            Point spot = placement(map, activity);
            assertEquals(GROUND_Y - 1, spot.y,
                    activity + " should stand on the ground, was at y=" + spot.y);
        }
    }

    /** A flying mob's spawn point is mid-air; its grinder belongs on the floor under it. */
    @Test
    void grindersStandOnTheFloorBeneathTheirSpawnPoint() {
        MapleMap map = stackedMap(0, 150, 350, MAP_TOP_Y, 250);
        when(map.findClosestSpawnpoint(any(Point.class))).thenAnswer(call -> {
            SpawnPoint spawn = mock(SpawnPoint.class);
            when(spawn.getPosition()).thenReturn(new Point(200, 220));   // 30px above the platform
            return spawn;
        });

        Point spot = placement(map, FakePlayerActivity.GRINDING);

        assertEquals(200, spot.x, "a grinder stands at the spawn point's x");
        assertEquals(249, spot.y, "and on the platform under it, not in the air and not on the ground");
    }

    @Test
    void restersSitOnTheGroundToo() {
        MapleMap map = stackedMap(400, 500, 800, MAP_TOP_Y, 250);

        Point spot = placement(map, FakePlayerActivity.RESTING);

        assertEquals(GROUND_Y - 1, spot.y, "a rester sits on the ground, was at y=" + spot.y);
    }

    @Test
    void aWalkerStaysOnThePlatformItIsOn() {
        MapleMap map = stackedMap(0, 150, 800, MAP_TOP_Y, 250);

        FakePlayerService.WalkStep step = FakePlayerService.getInstance()
                .nextStep(map, new Point(200, 249), 600);   // on the platform, heading further along it

        assertEquals(340, step.position().x, "a walker takes one full step toward its destination");
        assertEquals(249, step.position().y, "and stays on the platform it is standing on");
        assertFalse(step.dropping(), "walking along a platform is not a drop");
    }

    /**
     * The case that matters for realism: a walker that runs out of platform drops onto the floor
     * underneath rather than gliding down to it over a whole walk tick.
     */
    @Test
    void aWalkerDropsOntoTheFloorWhenItsPlatformEnds() {
        MapleMap map = stackedMap(0, 150, 350, MAP_TOP_Y, 250);

        FakePlayerService.WalkStep step = FakePlayerService.getInstance()
                .nextStep(map, new Point(340, 249), 600);   // platform ends at x=350

        assertTrue(step.dropping(), "running out of platform with a floor 50px below is a drop");
        assertEquals(340, step.position().x, "a drop goes straight down, it is not also a step sideways");
        assertEquals(GROUND_Y - 1, step.position().y);
        assertTrue(step.durationMs() < 1000, "a fall is quick, not a walk: was " + step.durationMs() + "ms");
    }

    @Test
    void aWalkerGivesUpRatherThanDroppingTooFar() {
        MapleMap map = stackedMap(0, 150, 350, 20, 50);     // a platform 250px above the ground

        FakePlayerService.WalkStep step = FakePlayerService.getInstance()
                .nextStep(map, new Point(340, 49), 600);

        assertNull(step, "a 250px drop is a cliff, not a ledge - the walker should pick somewhere else");
    }

    /**
     * The GM command spawns on whatever map the GM is standing on, so the mix has to suit the map: a
     * field gets grinders on the mobs, a town gets townsfolk. It used to always get townsfolk, which
     * left a GM's crowd on a field standing on the ground instead of around the mobs.
     */
    @Test
    void aFieldIsRecognisedByHavingMobSpawns() {
        MapleMap field = flatMap(700);      // has a spawn point
        MapleMap town = flatMap();          // no spawn points at all

        assertTrue(FakePlayerService.getInstance().looksLikeField(field), "a map with mob spawns is a field");
        assertFalse(FakePlayerService.getInstance().looksLikeField(town), "a map without them is a town");
    }

    /**
     * Fake players must never register as {@code MapObjectType.PLAYER}: several call sites cast
     * player map objects straight to {@code Character}, and registering one as a player crashes
     * the server. This is the guard on that invariant, not a style preference.
     */
    @Test
    void fakePlayersAreNotPlayers() {
        FakePlayer fakePlayer = new FakePlayer(1, "Wisp", 10, 0, 0, 0, 20000, 30000, Map.of());

        assertEquals(MapObjectType.FAKE_PLAYER, fakePlayer.getType());
    }
}
