package bot.combat;

import java.util.random.RandomGenerator;

/**
 * One physical damage line the way a pre-Big-Bang client rolls it, from the attacker's own numbers
 * and the target monster's. The server does none of this - {@code AbstractDealDamageHandler} applies
 * whatever the client declares and only compares it with a ceiling - so these are the client-side
 * formulas, as the classic (2008-2010) community compilations give them (e.g. the "MapleStory Formula
 * Compilation" reposted at exoot.blogspot.com, 2009-11):
 * <ul>
 *   <li><b>Max</b> {@code (primary * weaponMultiplier + secondary) * watk / 100} - the same expression as
 *       the server's {@code Character#calculateMaxBaseDamage(int, WeaponType)}; for a bow
 *       {@code (DEX * 3.4 + STR) * watk / 100}.</li>
 *   <li><b>Min</b> {@code (primary * weaponMultiplier * 0.9 * mastery + secondary) * watk / 100}, mastery
 *       10% without a mastery skill.</li>
 *   <li>A uniform roll in [min, max], times the skill's damage %.</li>
 *   <li><b>Critical</b>: the compilation's order of operations adds the critical bonus to the damage
 *       multiplier before it multiplies the roll. Critical Shot's data gives the critical hit's total
 *       ("critical damage 200%"), so the bonus added is that minus 100: Double Shot at 130% crits for
 *       230%.</li>
 *   <li><b>Monster side</b>: times {@code 1 - 0.01 * D} for a monster {@code D} levels above the attacker,
 *       then minus its weapon defence times 0.5 (at the top of the range) to 0.6 (at the bottom).</li>
 *   <li><b>Hit</b>: 100% at accuracy {@code avoid / 15 * (55.2 + 2.15 * D)}, falling linearly to 0% at half
 *       of that. A miss is a 0 damage line, which is what the client sends.</li>
 *   <li><b>Accuracy</b>: {@code DEX * 0.8 + LUK * 0.5} for warriors, magicians and beginners,
 *       {@code DEX * 0.6 + LUK * 0.3} for bowmen and thieves, plus gear and buffs.</li>
 * </ul>
 * Magic attacks have their own formulas, in {@link MagicDamageModel}.
 */
public final class DamageModel {
    /** Mastery without a mastery skill. */
    public static final int BASE_MASTERY_PERCENT = 10;

    private DamageModel() {}

    /**
     * @param primaryStat   STR for swords, spears and polearms, DEX for bows (see {@code calculateMaxBaseDamage})
     * @param accuracy      total accuracy, see {@link #accuracy} and {@link #bowmanAccuracy}
     */
    public record Attacker(int level, int primaryStat, int secondaryStat, double weaponMultiplier, int watk,
                           int masteryPercent, int accuracy) {}

    /** A monster as the client knows it from Mob.wz: {@code level}, {@code PDDamage}, {@code eva}, {@code MDDamage}. */
    public record Target(int level, int weaponDefense, int avoid, int magicDefense) {
        public Target(int level, int weaponDefense, int avoid) {
            this(level, weaponDefense, avoid, 0);
        }
    }

    /**
     * A chance to crit and what it adds to the damage multiplier, in percent.
     *
     * @param bonusPercent Critical Shot's {@code damage} minus 100 (level 20: 200 -> +100)
     */
    public record Critical(double chance, int bonusPercent) {
        public static final Critical NONE = new Critical(0, 0);
    }

    /** One rolled line: 0 damage for a miss. */
    public record Line(int damage, boolean critical) {}

    /** v83 base accuracy for warriors, magicians and beginners, plus gear and buffs. */
    public static int accuracy(int totalDex, int totalLuk, int bonusAccuracy) {
        return (int) (totalDex * 0.8 + totalLuk * 0.5) + bonusAccuracy;
    }

    /** v83 base accuracy for bowmen and thieves, plus gear and buffs (The Blessing of Amazon's {@code x}). */
    public static int bowmanAccuracy(int totalDex, int totalLuk, int bonusAccuracy) {
        return (int) (totalDex * 0.6 + totalLuk * 0.3) + bonusAccuracy;
    }

    public static int maxBase(Attacker a) {
        return (int) Math.ceil((a.weaponMultiplier() * a.primaryStat() + a.secondaryStat()) / 100.0 * a.watk());
    }

    public static int minBase(Attacker a) {
        double mastery = a.masteryPercent() / 100.0;
        int min = (int) Math.ceil((a.weaponMultiplier() * a.primaryStat() * 0.9 * mastery + a.secondaryStat()) / 100.0 * a.watk());
        return Math.min(min, maxBase(a));
    }

    /** Before the monster's defence: the largest line this attack could show on an undefended target. */
    public static int ceiling(Attacker a, int skillDamagePercent) {
        return (int) Math.min(Integer.MAX_VALUE, (long) maxBase(a) * skillDamagePercent / 100);
    }

    public static double hitChance(Attacker a, Target t) {
        if (t.avoid() <= 0) {
            return 1.0;
        }
        double needed = t.avoid() / 15.0 * (55.2 + 2.15 * levelGap(a.level(), t));
        double chance = (a.accuracy() - 0.5 * needed) / (0.5 * needed);
        return Math.max(0.0, Math.min(1.0, chance));
    }

    /**
     * Rolls one line without a critical chance: 0 for a miss, otherwise at least 1 and never above
     * {@link #ceiling}.
     *
     * @param skillDamagePercent 100 for a basic attack, else the skill's {@code StatEffect#getDamage()}
     */
    public static int roll(Attacker a, int skillDamagePercent, Target t, RandomGenerator rng) {
        return roll(a, skillDamagePercent, Critical.NONE, t, rng).damage();
    }

    /**
     * Rolls one line: a hit check, then a critical check, then the damage. A critical line never goes
     * above {@code ceiling(a, skillDamagePercent + bonus)}, a normal one never above
     * {@code ceiling(a, skillDamagePercent)}.
     */
    public static Line roll(Attacker a, int skillDamagePercent, Critical critical, Target t, RandomGenerator rng) {
        if (rng.nextDouble() >= hitChance(a, t)) {
            return new Line(0, false);
        }
        boolean crit = critical.chance() > 0 && rng.nextDouble() < critical.chance();
        int percent = skillDamagePercent + (crit ? critical.bonusPercent() : 0);
        int min = minBase(a);
        int max = maxBase(a);
        int base = min + rng.nextInt(max - min + 1);
        double damage = (double) base * percent / 100.0;
        damage *= Math.max(0.0, 1.0 - 0.01 * levelGap(a.level(), t));
        // Where the roll sits in its range decides the defence factor: 0.6 at min, 0.5 at max.
        double position = max == min ? 1.0 : (double) (base - min) / (max - min);
        damage -= t.weaponDefense() * (0.6 - 0.1 * position);
        return new Line((int) Math.max(1, Math.min(ceiling(a, percent), Math.floor(damage))), crit);
    }

    /** How many levels the monster is above the attacker, 0 if it isn't. */
    static int levelGap(int attackerLevel, Target t) {
        return Math.max(0, t.level() - attackerLevel);
    }
}
