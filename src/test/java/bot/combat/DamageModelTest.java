package bot.combat;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageModelTest {
    private static final int SAMPLES = 20_000;

    /** A level-30 spearman as CompanionLoadout makes one: STR 120, DEX 33, Pole Arm (32 WATK, x5.0), Polearm Mastery 1. */
    private static DamageModel.Attacker spearman(int level, int watk, int accuracyBonus) {
        return new DamageModel.Attacker(level, 120, 33, 5.0, watk, CombatMath.masteryPercentForLevel(1),
                DamageModel.accuracy(33, 4, accuracyBonus));
    }

    private static final DamageModel.Target DUMMY = new DamageModel.Target(1, 0, 0);
    /** Ligator (PC), 9300001 in Mob.wz: level 32, PDDamage 90, eva 10. */
    private static final DamageModel.Target LIGATOR = new DamageModel.Target(32, 90, 10);

    private static int[] rolls(DamageModel.Attacker a, int skillPercent, DamageModel.Target t, long seed) {
        RandomGenerator rng = new SplittableRandom(seed);
        int[] out = new int[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            out[i] = DamageModel.roll(a, skillPercent, t, rng);
        }
        return out;
    }

    @Test
    void maxMatchesTheServerFormulaAndMinUsesMastery() {
        DamageModel.Attacker a = spearman(30, 32, 0);
        // Character#calculateMaxBaseDamage: ceil((5.0 * 120 + 33) / 100 * 32) = ceil(202.56)
        assertEquals(203, DamageModel.maxBase(a));
        // ceil((5.0 * 120 * 0.9 * 0.15 + 33) / 100 * 32) = ceil(36.48)
        assertEquals(37, DamageModel.minBase(a));
        assertEquals(15, CombatMath.masteryPercentForLevel(1));
        assertEquals(60, CombatMath.masteryPercentForLevel(20));
    }

    @Test
    void undefendedRollsSpreadAcrossTheWholeRangeAndNeverPassTheCeiling() {
        DamageModel.Attacker a = spearman(30, 32, 0);
        int[] r = rolls(a, 100, DUMMY, 1);
        int min = Arrays.stream(r).min().orElseThrow();
        int max = Arrays.stream(r).max().orElseThrow();
        assertEquals(DamageModel.minBase(a), min);
        assertEquals(DamageModel.maxBase(a), max);
        assertTrue(stddev(r) > 30, "spread " + stddev(r));

        int[] skill = rolls(a, 195, DUMMY, 2);   // Power Strike level 7
        assertTrue(Arrays.stream(skill).max().orElseThrow() <= DamageModel.ceiling(a, 195));
    }

    @Test
    void betterWeaponOrMoreWatkRaisesDamage() {
        double weak = mean(rolls(spearman(30, 17, 0), 100, DUMMY, 3));
        double strong = mean(rolls(spearman(30, 32, 0), 100, DUMMY, 3));
        double raged = mean(rolls(spearman(30, 32 + 10, 0), 100, DUMMY, 3));   // a +10 WATK buff
        assertTrue(strong > weak * 1.5, weak + " vs " + strong);
        assertTrue(raged > strong, strong + " vs " + raged);
    }

    @Test
    void higherMonsterDefenceAndLevelCutDamage() {
        double plain = mean(hitsOnly(rolls(spearman(30, 32, 0), 100, new DamageModel.Target(30, 0, 0), 4)));
        double defended = mean(hitsOnly(rolls(spearman(30, 32, 0), 100, new DamageModel.Target(30, 90, 0), 4)));
        double higher = mean(hitsOnly(rolls(spearman(30, 32, 0), 100, new DamageModel.Target(40, 90, 0), 4)));
        assertTrue(defended < plain - 40, plain + " vs " + defended);
        assertTrue(higher < defended, defended + " vs " + higher);
    }

    @Test
    void missesAgainstHigherLevelOrEvasiveMonstersAndAccuracyHelps() {
        assertEquals(0, misses(rolls(spearman(30, 32, 0), 100, DUMMY, 5)));
        int ligatorMisses = misses(rolls(spearman(30, 32, 0), 100, LIGATOR, 5));
        int lowLevelMisses = misses(rolls(spearman(22, 32, 0), 100, LIGATOR, 5));
        int blessedMisses = misses(rolls(spearman(30, 32, 10), 100, LIGATOR, 5));   // Bless level 10: +10 accuracy
        assertTrue(ligatorMisses > SAMPLES / 10, "misses " + ligatorMisses);
        assertTrue(lowLevelMisses > ligatorMisses, lowLevelMisses + " vs " + ligatorMisses);
        assertTrue(blessedMisses < ligatorMisses, blessedMisses + " vs " + ligatorMisses);
    }

    @Test
    void sameSeedSameRolls() {
        assertTrue(Arrays.equals(rolls(spearman(30, 32, 0), 195, LIGATOR, 9), rolls(spearman(30, 32, 0), 195, LIGATOR, 9)));
    }

    /** A level-25 bowman as CompanionLoadout makes one: DEX 100, STR 28, Hunter's Bow (35 WATK, x3.4), no mastery. */
    private static DamageModel.Attacker bowman() {
        return new DamageModel.Attacker(25, 100, 28, 3.4, 35, DamageModel.BASE_MASTERY_PERCENT,
                DamageModel.bowmanAccuracy(100, 4, 5 + 5));     // Brown Bandana +5, Blessing of Amazon 5 +5
    }

    @Test
    void bowDamageUsesDexAndStrWithTheBowMultiplierAndBowmanAccuracy() {
        DamageModel.Attacker a = bowman();
        // ceil((3.4 * 100 + 28) / 100 * 35) = ceil(128.8); ceil((3.4 * 100 * 0.9 * 0.10 + 28) / 100 * 35) = ceil(20.51)
        assertEquals(129, DamageModel.maxBase(a));
        assertEquals(21, DamageModel.minBase(a));
        // DEX x 0.6 + LUK x 0.3 for bowmen, against DEX x 0.8 + LUK x 0.5 for warriors
        assertEquals(61, DamageModel.bowmanAccuracy(100, 4, 0));
        assertEquals(82, DamageModel.accuracy(100, 4, 0));
        assertEquals(71, a.accuracy());
    }

    @Test
    void criticalShotAddsItsBonusToTheSkillPercentAtItsRate() {
        DamageModel.Attacker a = bowman();
        DamageModel.Critical critical = new DamageModel.Critical(0.4, 100);   // Critical Shot level 20
        RandomGenerator rng = new SplittableRandom(11);
        int crits = 0;
        int critAbovePlainCeiling = 0;
        for (int i = 0; i < SAMPLES; i++) {
            DamageModel.Line line = DamageModel.roll(a, 130, critical, DUMMY, rng);   // Double Shot level 20
            if (line.critical()) {
                crits++;
                assertTrue(line.damage() <= DamageModel.ceiling(a, 230), "crit " + line.damage());
                critAbovePlainCeiling += line.damage() > DamageModel.ceiling(a, 130) ? 1 : 0;
            } else {
                assertTrue(line.damage() <= DamageModel.ceiling(a, 130), "line " + line.damage());
            }
        }
        assertEquals(0.4, (double) crits / SAMPLES, 0.02);
        assertTrue(critAbovePlainCeiling > crits / 4, critAbovePlainCeiling + " of " + crits);
        assertEquals(296, DamageModel.ceiling(a, 230));
        assertEquals(167, DamageModel.ceiling(a, 130));

        RandomGenerator none = new SplittableRandom(11);
        for (int i = 0; i < 1000; i++) {
            assertFalse(DamageModel.roll(a, 130, DamageModel.Critical.NONE, DUMMY, none).critical());
        }
    }

    @Test
    void theServerCeilingCapsLinesAndOnlyCritCapableJobsGetTwiceIt() {
        assertEquals(300, CombatMath.clampToServer(new DamageModel.Line(300, true), 167, true));
        assertEquals(334, CombatMath.clampToServer(new DamageModel.Line(400, true), 167, true));
        assertEquals(167, CombatMath.clampToServer(new DamageModel.Line(300, true), 167, false));
        assertEquals(167, CombatMath.clampToServer(new DamageModel.Line(200, false), 167, true));
        assertEquals(0, CombatMath.clampToServer(new DamageModel.Line(0, false), 167, true));
        assertTrue(CombatMath.serverAllowsCrit(client.Job.BOWMAN));
        assertTrue(CombatMath.serverAllowsCrit(client.Job.HUNTER));
        assertFalse(CombatMath.serverAllowsCrit(client.Job.WARRIOR));
        assertFalse(CombatMath.serverAllowsCrit(client.Job.MAGICIAN));
    }

    private static int[] hitsOnly(int[] r) {
        return Arrays.stream(r).filter(d -> d > 0).toArray();
    }

    private static int misses(int[] r) {
        return (int) Arrays.stream(r).filter(d -> d == 0).count();
    }

    private static double mean(int[] r) {
        return Arrays.stream(r).average().orElse(0);
    }

    private static double stddev(int[] r) {
        double m = mean(r);
        return Math.sqrt(Arrays.stream(r).mapToDouble(d -> (d - m) * (d - m)).average().orElse(0));
    }
}
