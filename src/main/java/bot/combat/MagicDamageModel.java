package bot.combat;

import java.util.random.RandomGenerator;

/**
 * One attack-spell damage line the way a pre-Big-Bang client rolls it. Like {@link DamageModel} this is
 * client-side arithmetic the server never repeats - {@code MagicDamageHandler} applies the declared
 * lines and only compares them with its own ceiling - so the formulas are the classic compilations'
 * (the "MapleStory Formula Compilation" reposted at exoot.blogspot.com, 2009-11):
 * <ul>
 *   <li><b>Max</b> {@code ((MAGIC^2 / 1000 + MAGIC) / 30 + INT / 200) * spell attack}</li>
 *   <li><b>Min</b> {@code ((MAGIC^2 / 1000 + MAGIC * mastery * 0.9) / 30 + INT / 200) * spell attack}</li>
 *   <li>{@code MAGIC} is total magic attack, which in v83 already includes INT ({@code Character#localmagic}
 *       starts from total INT and adds gear MATK); {@code INT} is total INT; spell attack is the skill's
 *       {@code mad}; mastery is the spell's own (Energy Bolt level 1: 15%, level 20: 60%).</li>
 *   <li><b>Monster side</b>: {@code MAX - MDEF * 0.5 * (1 + 0.01 * D)} at the top of the range and
 *       {@code MIN - MDEF * 0.6 * (1 + 0.01 * D)} at the bottom, {@code D} the levels the monster is above
 *       the caster (0 if it isn't). Unlike the physical formula there is no {@code 1 - 0.01 * D} factor.</li>
 *   <li><b>Hit</b> (Thikket and Nekonecat's fit, in the same compilation):
 *       {@code x = (trunc(INT / 10) + trunc(LUK / 10)) / (avoid + 1) * (1 + 0.0415 * D)} with {@code D}
 *       the caster's level minus the monster's, and hit rate {@code -2.5795x^2 + 5.2343x - 1.6749}. Weapon
 *       accuracy plays no part. The fitted parabola peaks at about 98% at {@code x = 1.0146} and falls
 *       again after it, which is an artefact of the fit, so from its peak on a spell always hits.</li>
 * </ul>
 */
public final class MagicDamageModel {
    private static final double HIT_A = -2.5795;
    private static final double HIT_B = 5.2343;
    private static final double HIT_C = -1.6749;
    private static final double HIT_PEAK_X = -HIT_B / (2 * HIT_A);

    private MagicDamageModel() {}

    /**
     * @param totalMagic {@code Character#getTotalMagic()}: INT plus gear MATK and INT, plus MATK buffs
     * @param masteryPercent the spell's mastery, see {@code CombatMath#masteryPercentForLevel}
     * @param spellAttack the spell's {@code mad} ({@code StatEffect#getMatk()})
     */
    public record Caster(int level, int totalMagic, int totalInt, int totalLuk, int masteryPercent, int spellAttack) {}

    public static double max(Caster c) {
        double m = c.totalMagic();
        return ((m * m / 1000.0 + m) / 30.0 + c.totalInt() / 200.0) * c.spellAttack();
    }

    public static double min(Caster c) {
        double m = c.totalMagic();
        double mastery = c.masteryPercent() / 100.0;
        return Math.min(max(c), ((m * m / 1000.0 + m * mastery * 0.9) / 30.0 + c.totalInt() / 200.0) * c.spellAttack());
    }

    public static int accuracy(Caster c) {
        return c.totalInt() / 10 + c.totalLuk() / 10;
    }

    public static double hitChance(Caster c, DamageModel.Target t) {
        double x = (double) accuracy(c) / (t.avoid() + 1) * (1 + 0.0415 * (c.level() - t.level()));
        if (x >= HIT_PEAK_X) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, HIT_A * x * x + HIT_B * x + HIT_C));
    }

    /** Before the monster's defence: the largest line this spell could show. */
    public static int ceiling(Caster c) {
        return (int) Math.floor(max(c));
    }

    /** Rolls one line: 0 for a miss, otherwise at least 1 and never above {@link #ceiling}. */
    public static int roll(Caster c, DamageModel.Target t, RandomGenerator rng) {
        if (rng.nextDouble() >= hitChance(c, t)) {
            return 0;
        }
        double min = min(c);
        double max = max(c);
        double position = rng.nextDouble();
        double damage = min + (max - min) * position;
        double defenceScale = 1 + 0.01 * DamageModel.levelGap(c.level(), t);
        damage -= t.magicDefense() * (0.6 - 0.1 * position) * defenceScale;
        return (int) Math.max(1, Math.min(ceiling(c), Math.floor(damage)));
    }
}
