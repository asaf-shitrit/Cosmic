package bot.combat;

import java.util.random.RandomGenerator;

/**
 * One physical damage line the way a pre-Big-Bang client rolls it, from the attacker's own numbers
 * and the target monster's. The server does none of this - {@code AbstractDealDamageHandler} applies
 * whatever the client declares and only compares it with a ceiling - so these are the client-side
 * formulas, as the classic (2008-2010) community compilations give them:
 * <ul>
 *   <li><b>Max</b> {@code (primary * weaponMultiplier + secondary) * watk / 100} - the same expression as
 *       the server's {@code Character#calculateMaxBaseDamage(int, WeaponType)}.</li>
 *   <li><b>Min</b> {@code (primary * weaponMultiplier * 0.9 * mastery + secondary) * watk / 100}, mastery
 *       10% without a mastery skill.</li>
 *   <li>A uniform roll in [min, max], times the skill's damage %.</li>
 *   <li><b>Monster side</b>: times {@code 1 - 0.01 * D} for a monster {@code D} levels above the attacker,
 *       then minus its weapon defence times 0.5 (at the top of the range) to 0.6 (at the bottom).</li>
 *   <li><b>Hit</b>: 100% at accuracy {@code avoid / 15 * (55.2 + 2.15 * D)}, falling linearly to 0% at half
 *       of that. A miss is a 0 damage line, which is what the client sends.</li>
 * </ul>
 * No criticals: warrior-line companions can't crit ({@code AbstractDealDamageHandler} only allows it for
 * bowmen, thieves and some pirates, or under Sharp Eyes). No magic: companions don't cast attack spells.
 */
public final class DamageModel {
    /** Mastery without a mastery skill. */
    public static final int BASE_MASTERY_PERCENT = 10;

    private DamageModel() {}

    /**
     * @param primaryStat   STR for swords, spears and polearms (see {@code calculateMaxBaseDamage} for the others)
     * @param accuracy      total accuracy, see {@link #accuracy}
     */
    public record Attacker(int level, int primaryStat, int secondaryStat, double weaponMultiplier, int watk,
                           int masteryPercent, int accuracy) {}

    public record Target(int level, int weaponDefense, int avoid) {}

    /** v83 base accuracy for warriors, magicians and beginners, plus gear and buffs. */
    public static int accuracy(int totalDex, int totalLuk, int bonusAccuracy) {
        return (int) (totalDex * 0.8 + totalLuk * 0.5) + bonusAccuracy;
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
        double needed = t.avoid() / 15.0 * (55.2 + 2.15 * levelGap(a, t));
        double chance = (a.accuracy() - 0.5 * needed) / (0.5 * needed);
        return Math.max(0.0, Math.min(1.0, chance));
    }

    /**
     * Rolls one line: 0 for a miss, otherwise at least 1 and never above {@link #ceiling}.
     *
     * @param skillDamagePercent 100 for a basic attack, else the skill's {@code StatEffect#getDamage()}
     */
    public static int roll(Attacker a, int skillDamagePercent, Target t, RandomGenerator rng) {
        if (rng.nextDouble() >= hitChance(a, t)) {
            return 0;
        }
        int min = minBase(a);
        int max = maxBase(a);
        int base = min + rng.nextInt(max - min + 1);
        double damage = (double) base * skillDamagePercent / 100.0;
        damage *= Math.max(0.0, 1.0 - 0.01 * levelGap(a, t));
        // Where the roll sits in its range decides the defence factor: 0.6 at min, 0.5 at max.
        double position = max == min ? 1.0 : (double) (base - min) / (max - min);
        damage -= t.weaponDefense() * (0.6 - 0.1 * position);
        return (int) Math.max(1, Math.min(ceiling(a, skillDamagePercent), Math.floor(damage)));
    }

    private static int levelGap(Attacker a, Target t) {
        return Math.max(0, t.level() - a.level());
    }
}
