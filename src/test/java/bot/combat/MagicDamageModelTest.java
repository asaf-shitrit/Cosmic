package bot.combat;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagicDamageModelTest {
    private static final int SAMPLES = 20_000;
    /** Ligator (PC), 9300001 in Mob.wz: level 32, PDDamage 90, eva 10, MDDamage 80. */
    private static final DamageModel.Target LIGATOR = new DamageModel.Target(32, 90, 10, 80);
    private static final DamageModel.Target DUMMY = new DamageModel.Target(1, 0, 0, 0);

    /**
     * A level-25 magician as CompanionLoadout makes one: INT 100, LUK 28, an Ice Wand (MATK 38, so total
     * magic 138), Magic Claw level 20 (mad 40, mastery 60%).
     */
    private static final MagicDamageModel.Caster MAGE_25 = new MagicDamageModel.Caster(25, 138, 100, 28, 60, 40);

    @Test
    void maxAndMinFollowTheClientFormula() {
        // ((138^2 / 1000 + 138) / 30 + 100 / 200) * 40 = (5.2348 + 0.5) * 40
        assertEquals(229.39, MagicDamageModel.max(MAGE_25), 0.01);
        // ((138^2 / 1000 + 138 * 0.6 * 0.9) / 30 + 100 / 200) * 40 = (3.1188 + 0.5) * 40
        assertEquals(144.75, MagicDamageModel.min(MAGE_25), 0.01);
        assertEquals(229, MagicDamageModel.ceiling(MAGE_25));
    }

    @Test
    void theServersMagicCeilingIsItsOwnExpressionAndNeverBelowTheClientMax() {
        // (ceil((138 * ceil(138 / 1000) + 138) / 30) + ceil(100 / 200)) * 40 = (ceil(9.2) + 1) * 40
        assertEquals(440, CombatMath.serverMagicCeiling(138, 100, 40));
        for (int magic = 4; magic <= 2000; magic += 7) {
            int intel = Math.max(4, magic - 40);
            MagicDamageModel.Caster c = new MagicDamageModel.Caster(30, magic, intel, 4, 60, 40);
            assertTrue(CombatMath.serverMagicCeiling(magic, intel, 40) >= MagicDamageModel.ceiling(c), "magic " + magic);
        }
    }

    @Test
    void hitRateIsTheFittedCurveOfMagicAccuracyAvoidAndLevel() {
        // trunc(100 / 10) + trunc(28 / 10)
        assertEquals(12, MagicDamageModel.accuracy(MAGE_25));
        // x = 12 / 11 * (1 + 0.0415 * (25 - 32)) = 0.7740; -2.5795x^2 + 5.2343x - 1.6749
        assertEquals(0.8311, MagicDamageModel.hitChance(MAGE_25, LIGATOR), 0.001);
        // Level 21, INT 84, LUK 24: x = 10 / 11 * (1 - 0.0415 * 11) = 0.4941
        MagicDamageModel.Caster mage21 = new MagicDamageModel.Caster(21, 117, 84, 24, 60, 40);
        assertEquals(0.2816, MagicDamageModel.hitChance(mage21, LIGATOR), 0.001);
        // Past the parabola's peak a spell always hits; far enough below the curve's root it never does.
        assertEquals(1.0, MagicDamageModel.hitChance(new MagicDamageModel.Caster(40, 300, 250, 50, 60, 40), LIGATOR));
        assertEquals(1.0, MagicDamageModel.hitChance(MAGE_25, DUMMY));
        assertEquals(0.0, MagicDamageModel.hitChance(new MagicDamageModel.Caster(10, 60, 40, 10, 15, 20), LIGATOR));
    }

    @Test
    void rollsSpreadInsideTheDefendedRangeAndMissAtTheHitRate() {
        SplittableRandom rng = new SplittableRandom(3);
        int[] lines = new int[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            lines[i] = MagicDamageModel.roll(MAGE_25, LIGATOR, rng);
        }
        int[] hits = Arrays.stream(lines).filter(d -> d > 0).toArray();
        assertEquals(0.8311, (double) hits.length / SAMPLES, 0.015);
        // Defended by MDDamage 80 over a 7-level gap: max 229.39 - 80 * 0.5 * 1.07, min 144.75 - 80 * 0.6 * 1.07
        int min = Arrays.stream(hits).min().orElseThrow();
        int max = Arrays.stream(hits).max().orElseThrow();
        assertTrue(min >= 93 && min <= 95, "min " + min);
        assertTrue(max >= 185 && max <= 186, "max " + max);

        SplittableRandom undefended = new SplittableRandom(4);
        for (int i = 0; i < SAMPLES; i++) {
            int d = MagicDamageModel.roll(MAGE_25, DUMMY, undefended);
            assertTrue(d >= 144 && d <= 229, "line " + d);
        }
    }
}
