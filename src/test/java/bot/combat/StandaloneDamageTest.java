package bot.combat;

import bot.WorldState;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

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

    /**
     * The WZ entry holds the fields {@link MobDefense} reads, with the values the other tests use for
     * the Ligator. Parsed with plain DOM on purpose: the server's WZ parser initialises
     * {@code GameConstants}, which fixes {@code WZFiles.DIRECTORY} for the whole JVM, and any test that
     * ran before {@code MobSkillFactoryTest} (which points it at a temp dir) would break it.
     */
    @Test
    void ligatorWzEntryHasTheDefenceFieldsMobDefenseReads() throws Exception {
        Path ligator = Path.of("wz", "Mob.wz", "9300001.img.xml");
        assumeTrue(Files.exists(ligator));
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ligator.toFile());
        XPath xpath = XPathFactory.newInstance().newXPath();
        IntUnaryOperatorByName info = name -> Integer.parseInt(xpath.evaluate(
                "/imgdir/imgdir[@name='info']/int[@name='" + name + "']/@value", doc));
        assertEquals(new DamageModel.Target(32, 90, 10),
                new DamageModel.Target(info.apply("level"), info.apply("PDDamage"), info.apply("eva")));
    }

    @FunctionalInterface
    private interface IntUnaryOperatorByName {
        int apply(String name) throws Exception;
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
