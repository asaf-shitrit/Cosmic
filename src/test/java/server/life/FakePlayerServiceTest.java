package server.life;

import org.junit.jupiter.api.Test;
import server.maps.FootholdTree;
import server.maps.MapObjectType;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
