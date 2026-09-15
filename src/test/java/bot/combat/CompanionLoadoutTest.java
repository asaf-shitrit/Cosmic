package bot.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionLoadoutTest {
    @Test
    void firstJobWarriorsBelowThirtyThenTwoSpearmenAndACleric() {
        for (int slot = 0; slot < 3; slot++) {
            assertEquals(CompanionLoadout.Role.WARRIOR, CompanionLoadout.roleFor(slot, 29));
        }
        assertEquals(CompanionLoadout.Role.SPEARMAN, CompanionLoadout.roleFor(0, 30));
        assertEquals(CompanionLoadout.Role.SPEARMAN, CompanionLoadout.roleFor(1, 30));
        assertEquals(CompanionLoadout.Role.CLERIC, CompanionLoadout.roleFor(2, 30));
        assertThrows(IllegalArgumentException.class, () -> CompanionLoadout.roleFor(3, 30));
    }

    @Test
    void statsAreATargetForTheLevelNotAnIncrement() {
        // The regression this guards: stats were computed as a delta and added on every summon.
        CompanionLoadout.Stats warrior = CompanionLoadout.statsFor(CompanionLoadout.Role.WARRIOR, 25);
        assertEquals(new CompanionLoadout.Stats(100, 28, 4, 4), warrior);
        assertEquals(warrior, CompanionLoadout.statsFor(CompanionLoadout.Role.WARRIOR, 25));

        CompanionLoadout.Stats cleric = CompanionLoadout.statsFor(CompanionLoadout.Role.CLERIC, 30);
        assertEquals(new CompanionLoadout.Stats(4, 4, 120, 33), cleric);
    }

    @Test
    void levelFollowsOwnerWithinBounds() {
        assertEquals(1, CompanionLoadout.levelFor(0));
        assertEquals(25, CompanionLoadout.levelFor(25));
        assertEquals(70, CompanionLoadout.levelFor(120));
    }
}
