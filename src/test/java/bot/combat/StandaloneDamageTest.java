package bot.combat;

import bot.WorldState;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import provider.wz.WZFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The standalone {@code KpqBot} path runs in its own JVM with no database pool. This test runs the same
 * way - nothing here initialises {@code DatabaseConnection} - so any server singleton that reaches
 * for the database on the way (as {@code ItemInformationProvider} did) fails it.
 */
class StandaloneDamageTest {
    @Test
    void weaponCategoriesMatchTheServerTable() {
        assertEquals(WeaponType.SWORD1H, CombatMath.weaponType(1302000));
        assertEquals(WeaponType.POLE_ARM_SWING, CombatMath.weaponType(1442000));
        assertEquals(WeaponType.SPEAR_STAB, CombatMath.weaponType(1432000));
        assertEquals(WeaponType.WAND, CombatMath.weaponType(1372005));
        assertEquals(WeaponType.NOT_A_WEAPON, CombatMath.weaponType(1040002));
        assertEquals(WeaponType.NOT_A_WEAPON, CombatMath.weaponType(0));
    }

    @Test
    void readsMonsterDefenceFromWz() {
        // WZFiles fixes its directory once per JVM, and MobSkillFactoryTest points it at a temp dir, so
        // only check the real data when this JVM is reading it.
        assumeTrue(Files.exists(Path.of(WZFiles.DIRECTORY, "Mob.wz", "9300001.img.xml")));
        assertEquals(new DamageModel.Target(32, 90, 10), MobDefense.of(9300001));   // Ligator (PC)
    }

    @Test
    void rollsFromWireStatsWithoutADatabase() {
        // The KPQ test characters: level 30 Spearman, STR 120 DEX 33, a Pole Arm with 32 WATK.
        WorldState.SelfStats self = new WorldState.SelfStats(30, 130, 120, 33, 4, 4, 1442000, 0, 0, 0, 0, 32, 0);
        DamageModel.Target ligator = new DamageModel.Target(32, 90, 10);

        SplittableRandom rng = new SplittableRandom(7);
        int hits = 0;
        for (int i = 0; i < 2000; i++) {
            int d = CombatMath.basicLineDamage(self, ligator, rng);
            assertTrue(d >= 0 && d <= 203, "line " + d);
            hits += d > 0 ? 1 : 0;
        }
        assertTrue(hits > 0 && hits < 2000, "hits " + hits);
    }
}
