package bot.ambient;

import bot.ambient.AmbientCompanionDirector.Settings;
import bot.ambient.AmbientCompanionDirector.Snapshot;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import static bot.ambient.AmbientCompanionDirector.isFieldMap;
import static bot.ambient.AmbientCompanionDirector.shouldSummon;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * When a player alone on a field is joined by a companion.
 *
 * <p>These are the rules that keep the feature charming rather than annoying, so each one is pinned
 * separately: it is far cheaper to test "don't send a second one" here than to notice it in game.
 */
class AmbientCompanionDirectorTest {
    private static final Settings SETTINGS = new Settings(true, 3_000, 600_000);

    /** A player who qualifies on every other count: on a field, alone, with capacity, long past cooldown. */
    private static Snapshot qualified() {
        return new Snapshot(5_000, true, 0, 3, Long.MAX_VALUE / 2);
    }

    @Test
    void aPlayerAloneOnAFieldLongEnoughGetsACompanion() {
        assertTrue(shouldSummon(qualified(), SETTINGS));
    }

    @Test
    void someoneJustPassingThroughIsLeftAlone() {
        Snapshot passingThrough = new Snapshot(1_000, true, 0, 3, Long.MAX_VALUE / 2);

        assertFalse(shouldSummon(passingThrough, SETTINGS), "2s on the map is a player walking across it");
    }

    @Test
    void someoneAlreadyWithCompanyIsNotToppedUp() {
        Snapshot alreadyHelped = new Snapshot(60_000, true, 1, 2, Long.MAX_VALUE / 2);

        assertFalse(shouldSummon(alreadyHelped, SETTINGS),
                "a player who summoned help is being helped; a second one would be a crowd");
    }

    @Test
    void aPlayerInsideAnEventIsNeverJoined() {
        Snapshot inEvent = new Snapshot(60_000, false, 0, 3, Long.MAX_VALUE / 2);

        assertFalse(shouldSummon(inEvent, SETTINGS), "inside a party quest the party belongs to the PQ");
    }

    @Test
    void aDismissedCompanionIsNotImmediatelyReplaced() {
        Snapshot justSummoned = new Snapshot(60_000, true, 0, 3, 30_000);

        assertFalse(shouldSummon(justSummoned, SETTINGS));
        assertTrue(shouldSummon(new Snapshot(60_000, true, 0, 3, 601_000), SETTINGS));
    }

    @Test
    void aFullBudgetMeansNobodyIsSent() {
        Snapshot noCapacity = new Snapshot(60_000, true, 0, 0, Long.MAX_VALUE / 2);

        assertFalse(shouldSummon(noCapacity, SETTINGS), "the companion reserve must not be spent silently");
    }

    @Test
    void theFeatureCanBeTurnedOff() {
        assertFalse(shouldSummon(qualified(), new Settings(false, 3_000, 600_000)));
    }

    @Test
    void aMapWithMobSpawnsIsAFieldAndOneWithoutIsATown() {
        assertTrue(isFieldMap(mapWithSpawns(true, null)), "a map with spawn points is somewhere people grind");
        assertFalse(isFieldMap(mapWithSpawns(false, null)), "a town has no spawn points at all");
        assertFalse(isFieldMap(mapWithSpawns(true, mock(scripting.event.EventInstanceManager.class))),
                "an event map belongs to its event, even with mobs on it");
        assertFalse(isFieldMap(null));
    }

    private static MapleMap mapWithSpawns(boolean hasSpawns, scripting.event.EventInstanceManager event) {
        MapleMap map = mock(MapleMap.class);
        when(map.getEventInstance()).thenReturn(event);
        when(map.findClosestSpawnpoint(any())).thenReturn(hasSpawns ? mock(server.life.SpawnPoint.class) : null);
        return map;
    }
}