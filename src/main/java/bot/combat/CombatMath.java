package bot.combat;

import bot.WorldState;
import client.BuffStat;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.skills.Archer;
import constants.skills.Fighter;
import constants.skills.Page;
import constants.skills.Spearman;
import server.StatEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Feeds {@link DamageModel} and {@link MagicDamageModel} with a character's own numbers - level, total
 * stats, equipped weapon, gear accuracy, mastery, buffs - so anything that changes those changes the
 * damage it rolls, and clamps every line to the ceiling {@code AbstractDealDamageHandler#parseDamage}
 * computes for the same attack, so no line trips the server's damage-hack alert. Two sources:
 * a summoned companion's live server-side {@link Character}, and a standalone client's own view of
 * itself decoded off the wire ({@link WorldState.SelfStats}).
 */
public final class CombatMath {
    private static final short WEAPON_SLOT = -11;

    private CombatMath() {}

    /**
     * One melee damage line for {@code skillDamagePercent} (100 for a basic attack) against
     * {@code monsterId}: 0 for a miss. Never above the ceiling {@code AbstractDealDamageHandler#parseDamage}
     * computes for the same attack ({@code calculateMaxBaseDamage(getTotalWatk())} times the skill's
     * damage %). No criticals: melee companions are warriors, which {@code canCrit} excludes.
     */
    public static int lineDamage(Character self, int skillDamagePercent, int monsterId, RandomGenerator rng) {
        int damage = DamageModel.roll(attacker(self), skillDamagePercent, MobDefense.of(monsterId), rng);
        long serverCeiling = (long) self.calculateMaxBaseDamage(self.getTotalWatk()) * skillDamagePercent / 100;
        return (int) Math.min(damage, serverCeiling);
    }

    /**
     * A basic attack by a standalone client that knows itself only from the wire. Mastery is the base
     * 10%: such a client doesn't decode its skill list, and the KPQ test characters have no skills.
     */
    public static int basicLineDamage(WorldState.SelfStats self, int monsterId, RandomGenerator rng) {
        return basicLineDamage(self, MobDefense.of(monsterId), rng);
    }

    static int basicLineDamage(WorldState.SelfStats self, DamageModel.Target target, RandomGenerator rng) {
        DamageModel.Attacker attacker = attacker(self.level(), Job.getById(self.job()),
                self.str() + self.gearStr(), self.dex() + self.gearDex(), self.int_() + self.gearInt(),
                self.luk() + self.gearLuk(), self.weaponId(), self.gearWatk(), DamageModel.BASE_MASTERY_PERCENT,
                self.gearAcc());
        return DamageModel.roll(attacker, 100, target, rng);
    }

    /**
     * The lines of one bow attack: {@code effect} null for a plain shot (100%, one arrow), else the
     * skill's damage % over {@code max(bulletCount, attackCount)} lines (Double Shot: 2), the most lines
     * {@code parseDamage} accepts for it without a "Too many lines" autoban point. A Critical Shot crit
     * rolls with its bonus added and may pass the plain ceiling, but never twice it.
     */
    public static List<Integer> rangedLines(Character self, StatEffect effect, int monsterId, RandomGenerator rng) {
        int percent = effect == null ? 100 : effect.getDamage();
        int count = effect == null ? 1 : Math.max(effect.getBulletCount(), effect.getAttackCount());
        long serverCeiling = (long) self.calculateMaxBaseDamage(self.getTotalWatk()) * percent / 100;
        DamageModel.Attacker attacker = attacker(self);
        DamageModel.Critical critical = criticalShot(self);
        DamageModel.Target target = MobDefense.of(monsterId);
        boolean serverAllowsCrit = serverAllowsCrit(self.getJob());
        List<Integer> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(clampToServer(DamageModel.roll(attacker, percent, critical, target, rng), serverCeiling,
                    serverAllowsCrit));
        }
        return lines;
    }

    /**
     * The lines of one attack spell cast at {@code skillLevel}: {@code effect.getAttackCount()} of them
     * (Magic Claw: 2), each clamped to {@link #serverMagicCeiling}.
     */
    public static List<Integer> magicLines(Character self, StatEffect effect, int skillLevel, int monsterId,
                                           RandomGenerator rng) {
        MagicDamageModel.Caster caster = new MagicDamageModel.Caster(self.getLevel(), self.getTotalMagic(),
                self.getTotalInt(), self.getTotalLuk(), masteryPercentForLevel(skillLevel), effect.getMatk());
        long ceiling = serverMagicCeiling(self.getTotalMagic(), self.getTotalInt(), effect.getMatk());
        DamageModel.Target target = MobDefense.of(monsterId);
        int count = Math.max(1, effect.getAttackCount());
        List<Integer> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add((int) Math.min(MagicDamageModel.roll(caster, target, rng), ceiling));
        }
        return lines;
    }

    /**
     * {@code parseDamage}'s magic ceiling, which is not the client formula:
     * {@code (ceil((MAGIC * ceil(MAGIC / 1000) + MAGIC) / 30) + ceil(INT / 200)) * mad}. For any MAGIC up
     * to the server's 2000 cap it is at or above the client's max, so it only binds on a rounding edge.
     * Buffs whose {@code damage} isn't 100 would scale it too; a companion has none.
     */
    static long serverMagicCeiling(int totalMagic, int totalInt, int spellAttack) {
        long base = (long) (Math.ceil((totalMagic * Math.ceil(totalMagic / 1000.0) + totalMagic) / 30.0)
                + Math.ceil(totalInt / 200.0));
        return base * spellAttack;
    }

    /**
     * {@code parseDamage} lets a line reach {@code 2 x} its ceiling without an alert only for a job that
     * can crit ({@code canCrit}), and shows any line above the plain ceiling as a critical hit. Everyone
     * else stays at the plain ceiling.
     */
    static int clampToServer(DamageModel.Line line, long serverCeiling, boolean serverAllowsCrit) {
        long cap = line.critical() && serverAllowsCrit ? 2 * serverCeiling : serverCeiling;
        return (int) Math.min(line.damage(), cap);
    }

    /** The explorer part of {@code parseDamage}'s {@code canCrit}: bowmen and thieves. */
    static boolean serverAllowsCrit(Job job) {
        return job != null && (job.isA(Job.BOWMAN) || job.isA(Job.THIEF));
    }

    /** Critical Shot, bowmen only: its {@code prop} is the chance and its {@code damage} the crit's total %. */
    static DamageModel.Critical criticalShot(Character self) {
        StatEffect effect = bowmanPassive(self, Archer.CRITICAL_SHOT);
        return effect == null ? DamageModel.Critical.NONE
                : new DamageModel.Critical(effect.getProp(), effect.getDamage() - 100);
    }

    private static StatEffect bowmanPassive(Character self, int skillId) {
        int level = self.getJob().isA(Job.BOWMAN) ? self.getSkillLevel(skillId) : 0;
        Skill skill = level > 0 ? SkillFactory.getSkill(skillId) : null;
        return skill == null ? null : skill.getEffect(level);
    }

    static DamageModel.Attacker attacker(Character self) {
        Item weapon = self.getInventory(InventoryType.EQUIPPED).getItem(WEAPON_SLOT);
        int gearAccuracy = 0;
        for (Item item : self.getInventory(InventoryType.EQUIPPED).list()) {
            if (item instanceof Equip equip) {
                gearAccuracy += equip.getAcc();
            }
        }
        Integer buffAccuracy = self.getBuffedValue(BuffStat.ACC);
        MasterySkill mastery = masterySkill(self, weapon);
        // The Blessing of Amazon: passive accuracy, its x (300.img 3000000: level 1 -> 1, 15 -> 15).
        StatEffect amazon = bowmanPassive(self, Archer.BLESSING_OF_AMAZON);
        return attacker(self.getLevel(), self.getJob(), self.getTotalStr(), self.getTotalDex(), self.getTotalInt(),
                self.getTotalLuk(), weapon == null ? 0 : weapon.getItemId(), self.getTotalWatk(),
                masteryPercentForLevel(mastery.level()),
                gearAccuracy + (buffAccuracy == null ? 0 : buffAccuracy) + mastery.accuracy()
                        + (amazon == null ? 0 : amazon.getX()));
    }

    /** The stat pairs and weapon multipliers of {@code Character#calculateMaxBaseDamage}, which the model's max must agree with. */
    static DamageModel.Attacker attacker(int level, Job job, int str, int dex, int int_, int luk, int weaponId,
                                         int watk, int masteryPercent, int bonusAccuracy) {
        WeaponType type = weaponType(weaponId);
        if (job != null && job.isA(Job.THIEF) && type == WeaponType.DAGGER_OTHER) {
            type = WeaponType.DAGGER_THIEVES;
        }
        int primary;
        int secondary;
        if (type == WeaponType.BOW || type == WeaponType.CROSSBOW || type == WeaponType.GUN) {
            primary = dex;
            secondary = str;
        } else if (type == WeaponType.CLAW || type == WeaponType.DAGGER_THIEVES) {
            primary = luk;
            secondary = dex + str;
        } else {
            primary = str;
            secondary = dex;
        }
        int accuracy = job != null && (job.isA(Job.BOWMAN) || job.isA(Job.THIEF))
                ? DamageModel.bowmanAccuracy(dex, luk, bonusAccuracy)
                : DamageModel.accuracy(dex, luk, bonusAccuracy);
        return new DamageModel.Attacker(level, primary, secondary, type.getMaxDamageMultiplier(), watk,
                masteryPercent, accuracy);
    }

    private static final WeaponType[] WEAPON_CATEGORIES = {
            WeaponType.SWORD1H, WeaponType.GENERAL1H_SWING, WeaponType.GENERAL1H_SWING, WeaponType.DAGGER_OTHER,
            WeaponType.NOT_A_WEAPON, WeaponType.NOT_A_WEAPON, WeaponType.NOT_A_WEAPON, WeaponType.WAND,
            WeaponType.STAFF, WeaponType.NOT_A_WEAPON, WeaponType.SWORD2H, WeaponType.GENERAL2H_SWING,
            WeaponType.GENERAL2H_SWING, WeaponType.SPEAR_STAB, WeaponType.POLE_ARM_SWING, WeaponType.BOW,
            WeaponType.CROSSBOW, WeaponType.CLAW, WeaponType.KNUCKLE, WeaponType.GUN};

    /**
     * The table of {@code ItemInformationProvider#getWeaponType}, copied rather than called: that class's
     * static initialiser reads the database, and a standalone bot has no connection pool (calling it
     * killed a pure-bot KPQ run with ExceptionInInitializerError). The weapon category is just the
     * item id's 3rd-4th digits.
     */
    static WeaponType weaponType(int itemId) {
        int category = (itemId / 10000) % 100;
        return category < 30 || category > 49 ? WeaponType.NOT_A_WEAPON : WEAPON_CATEGORIES[category - 30];
    }

    /** @param accuracy the mastery skill's {@code x}, the accuracy it adds (130.img 1300001: level 1 -> 1, 20 -> 20) */
    record MasterySkill(int level, int accuracy) {}

    /**
     * The warrior mastery skill for the equipped weapon's category. Skill.wz stores each level's
     * {@code mastery} as 1..10 over levels 1..20 (e.g. 130.img 1300001: level 1 -> 1, 10 -> 5, 20 -> 10),
     * worth 5% each on top of the base 10%, so a maxed mastery skill is the familiar 60%.
     */
    static MasterySkill masterySkill(Character self, Item weapon) {
        if (weapon == null) {
            return new MasterySkill(0, 0);
        }
        int[] skills = switch ((weapon.getItemId() / 10000) % 100) {
            case 30, 40 -> new int[]{Fighter.SWORD_MASTERY, Page.SWORD_MASTERY};
            case 31, 41 -> new int[]{Fighter.AXE_MASTERY};
            case 32, 42 -> new int[]{Page.BW_MASTERY};
            case 43 -> new int[]{Spearman.SPEAR_MASTERY};
            case 44 -> new int[]{Spearman.POLEARM_MASTERY};
            default -> new int[0];
        };
        int bestId = 0;
        int best = 0;
        for (int id : skills) {
            if (self.getSkillLevel(id) > best) {
                best = self.getSkillLevel(id);
                bestId = id;
            }
        }
        if (best == 0) {
            return new MasterySkill(0, 0);
        }
        Skill skill = SkillFactory.getSkill(bestId);
        StatEffect effect = skill == null ? null : skill.getEffect(best);
        return new MasterySkill(best, effect == null ? 0 : effect.getX());
    }

    static int masteryPercentForLevel(int skillLevel) {
        return DamageModel.BASE_MASTERY_PERCENT + 5 * ((skillLevel + 1) / 2);
    }
}
