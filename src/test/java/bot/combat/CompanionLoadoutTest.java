package bot.combat;

import constants.skills.Archer;
import constants.skills.Cleric;
import constants.skills.Magician;
import constants.skills.Spearman;
import constants.skills.Warrior;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionLoadoutTest {
    @Test
    void mixedFirstJobPartyBelowThirtyThenTwoSpearmenAndACleric() {
        assertEquals(CompanionLoadout.Role.WARRIOR, CompanionLoadout.roleFor(0, 29));
        assertEquals(CompanionLoadout.Role.MAGICIAN, CompanionLoadout.roleFor(1, 29));
        assertEquals(CompanionLoadout.Role.BOWMAN, CompanionLoadout.roleFor(2, 29));

        // Each job from the level it becomes available; a warrior until then.
        assertEquals(CompanionLoadout.Role.WARRIOR, CompanionLoadout.roleFor(1, 7));
        assertEquals(CompanionLoadout.Role.MAGICIAN, CompanionLoadout.roleFor(1, 8));
        assertEquals(CompanionLoadout.Role.WARRIOR, CompanionLoadout.roleFor(2, 9));
        assertEquals(CompanionLoadout.Role.BOWMAN, CompanionLoadout.roleFor(2, 10));
        for (int slot = 0; slot < 3; slot++) {
            assertEquals(CompanionLoadout.Role.WARRIOR, CompanionLoadout.roleFor(slot, 1));
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

        assertEquals(new CompanionLoadout.Stats(4, 4, 120, 33), CompanionLoadout.statsFor(CompanionLoadout.Role.CLERIC, 30));
        assertEquals(new CompanionLoadout.Stats(4, 4, 100, 28), CompanionLoadout.statsFor(CompanionLoadout.Role.MAGICIAN, 25));
        assertEquals(new CompanionLoadout.Stats(28, 100, 4, 4), CompanionLoadout.statsFor(CompanionLoadout.Role.BOWMAN, 25));
        // Every role meets its first-job gate the level it gets the job: Magician INT 20, Bowman DEX 25.
        assertTrue(CompanionLoadout.statsFor(CompanionLoadout.Role.MAGICIAN, 8).int_() >= 20);
        assertTrue(CompanionLoadout.statsFor(CompanionLoadout.Role.BOWMAN, 10).dex() >= 25);
    }

    @Test
    void levelFollowsOwnerWithinBounds() {
        assertEquals(1, CompanionLoadout.levelFor(0));
        assertEquals(25, CompanionLoadout.levelFor(25));
        assertEquals(70, CompanionLoadout.levelFor(120));
    }

    @Test
    void magicianSpendsItsSpOnEnergyBoltThenMagicClaw() {
        assertEquals(Map.of(Magician.ENERGY_BOLT, 1), CompanionLoadout.skillsFor(CompanionLoadout.Role.MAGICIAN, 8));
        assertEquals(Map.of(Magician.ENERGY_BOLT, 1, Magician.MAGIC_CLAW, 3),
                CompanionLoadout.skillsFor(CompanionLoadout.Role.MAGICIAN, 9));
        assertEquals(Map.of(Magician.ENERGY_BOLT, 1, Magician.MAGIC_CLAW, 20),
                CompanionLoadout.skillsFor(CompanionLoadout.Role.MAGICIAN, 25));
    }

    @Test
    void bowmanSpendsItsSpOnArrowBlowDoubleShotCriticalShotThenAmazon() {
        assertEquals(Map.of(Archer.ARROW_BLOW, 1), CompanionLoadout.skillsFor(CompanionLoadout.Role.BOWMAN, 10));
        assertEquals(Map.of(Archer.ARROW_BLOW, 1, Archer.DOUBLE_SHOT, 15),
                CompanionLoadout.skillsFor(CompanionLoadout.Role.BOWMAN, 15));
        // Level 21: 34 SP.
        assertEquals(Map.of(Archer.ARROW_BLOW, 1, Archer.DOUBLE_SHOT, 20, Archer.CRITICAL_SHOT, 13),
                CompanionLoadout.skillsFor(CompanionLoadout.Role.BOWMAN, 21));
        // Level 25: 46 SP.
        assertEquals(Map.of(Archer.ARROW_BLOW, 1, Archer.DOUBLE_SHOT, 20, Archer.CRITICAL_SHOT, 20,
                Archer.BLESSING_OF_AMAZON, 5), CompanionLoadout.skillsFor(CompanionLoadout.Role.BOWMAN, 25));
    }

    @Test
    void thirtyPlusSkillsAreUnchanged() {
        assertEquals(Map.of(Warrior.POWER_STRIKE, 7, Spearman.POLEARM_MASTERY, 1, Spearman.HYPER_BODY, 1),
                CompanionLoadout.skillsFor(CompanionLoadout.Role.SPEARMAN, 30));
        assertEquals(Map.of(Cleric.HEAL, 3, Cleric.BLESS, 3), CompanionLoadout.skillsFor(CompanionLoadout.Role.CLERIC, 40));
        assertEquals(Map.of(Warrior.POWER_STRIKE, 6), CompanionLoadout.skillsFor(CompanionLoadout.Role.WARRIOR, 25));
    }

    @Test
    void weaponsFollowTheUpgradePathOnlyWhenLevelAndStatsAllow() {
        assertEquals(1372005, CompanionLoadout.weaponFor(CompanionLoadout.Role.MAGICIAN, 8));      // Wooden Wand
        assertEquals(1372006, CompanionLoadout.weaponFor(CompanionLoadout.Role.MAGICIAN, 13));     // Hardwood Wand
        assertEquals(1372002, CompanionLoadout.weaponFor(CompanionLoadout.Role.MAGICIAN, 21));     // Metal Wand
        assertEquals(1372004, CompanionLoadout.weaponFor(CompanionLoadout.Role.MAGICIAN, 25));     // Ice Wand
        assertEquals(1372003, CompanionLoadout.weaponFor(CompanionLoadout.Role.MAGICIAN, 29));     // Mithril Wand

        assertEquals(1452002, CompanionLoadout.weaponFor(CompanionLoadout.Role.BOWMAN, 10));       // War Bow
        // Composite Bow needs STR 20: a bowman's secondary STR reaches it at 17, not at 15.
        assertEquals(1452002, CompanionLoadout.weaponFor(CompanionLoadout.Role.BOWMAN, 16));
        assertEquals(1452003, CompanionLoadout.weaponFor(CompanionLoadout.Role.BOWMAN, 17));
        assertEquals(1452003, CompanionLoadout.weaponFor(CompanionLoadout.Role.BOWMAN, 21));       // Hunter's needs STR 25
        assertEquals(1452001, CompanionLoadout.weaponFor(CompanionLoadout.Role.BOWMAN, 25));       // Battle Bow needs STR 30
        assertEquals(1452000, CompanionLoadout.weaponFor(CompanionLoadout.Role.BOWMAN, 29));

        assertEquals(1302000, CompanionLoadout.weaponFor(CompanionLoadout.Role.WARRIOR, 25));
        assertEquals(1442000, CompanionLoadout.weaponFor(CompanionLoadout.Role.SPEARMAN, 30));
        assertEquals(1372005, CompanionLoadout.weaponFor(CompanionLoadout.Role.CLERIC, 30));

        for (int level = CompanionLoadout.MAGICIAN_LEVEL; level < 30; level++) {
            int id = CompanionLoadout.weaponFor(CompanionLoadout.Role.MAGICIAN, level);
            int lvl = level;
            assertTrue(CompanionLoadout.WANDS.stream().anyMatch(w -> w.id() == id
                    && w.wearableBy(lvl, CompanionLoadout.statsFor(CompanionLoadout.Role.MAGICIAN, lvl))));
        }
        for (int level = CompanionLoadout.BOWMAN_LEVEL; level < 30; level++) {
            int id = CompanionLoadout.weaponFor(CompanionLoadout.Role.BOWMAN, level);
            int lvl = level;
            assertTrue(CompanionLoadout.BOWS.stream().anyMatch(w -> w.id() == id
                    && w.wearableBy(lvl, CompanionLoadout.statsFor(CompanionLoadout.Role.BOWMAN, lvl))));
        }
    }

    @Test
    void accuracyCapAmmoAndPotionsPerRole() {
        assertTrue(CompanionLoadout.wearsAccuracyCap(CompanionLoadout.Role.BOWMAN, 10));
        assertTrue(CompanionLoadout.wearsAccuracyCap(CompanionLoadout.Role.WARRIOR, 25));
        assertFalse(CompanionLoadout.wearsAccuracyCap(CompanionLoadout.Role.WARRIOR, 9));
        assertFalse(CompanionLoadout.wearsAccuracyCap(CompanionLoadout.Role.MAGICIAN, 25));
        assertFalse(CompanionLoadout.wearsAccuracyCap(CompanionLoadout.Role.CLERIC, 30));

        assertEquals(4000, CompanionLoadout.arrowsFor(CompanionLoadout.Role.BOWMAN));
        assertEquals(0, CompanionLoadout.arrowsFor(CompanionLoadout.Role.WARRIOR));
        assertTrue(CompanionLoadout.Role.MAGICIAN.potions > CompanionLoadout.Role.WARRIOR.potions);
    }
}
